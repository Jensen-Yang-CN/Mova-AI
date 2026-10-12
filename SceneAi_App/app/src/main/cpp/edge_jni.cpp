#include <jni.h>
#include <android/log.h>
#include "llama.h"
#if defined(__aarch64__)
#include <sys/auxv.h>
#include <asm/hwcap.h>
#endif

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t context_tokens = 0;
    std::atomic<int64_t> deadline_ms{0};
};

std::once_flag backend_once;

int64_t elapsed_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

bool should_abort(void * data) {
    const auto deadline = static_cast<Engine *>(data)->deadline_ms.load();
    return deadline != 0 && elapsed_ms() > deadline;
}

void fail(JNIEnv * env, const char * message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

void release(Engine * engine) {
    if (engine == nullptr) return;
    if (engine->context != nullptr) llama_free(engine->context);
    if (engine->model != nullptr) llama_model_free(engine->model);
    delete engine;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mova_sceneai_data_edge_EdgeNative_nativeLoad(
    JNIEnv * env, jobject, jstring path, jint context_tokens) {
    if (path == nullptr || context_tokens < 1024) {
        fail(env, "GGUF 路径或上下文长度无效");
        return 0;
    }
    #if defined(__aarch64__)
    const auto features = getauxval(AT_HWCAP);
    const auto required = HWCAP_ASIMDDP | HWCAP_FPHP | HWCAP_ASIMDHP | HWCAP_ATOMICS;
    if ((features & required) != required) {
        fail(env, "当前端侧演示版需要支持 FP16 与 dotprod 的 ARM CPU，可继续使用云端");
        return 0;
    }
    #endif
    std::call_once(backend_once, [] {
        // 上游默认 stderr 在手机上不便诊断，接入 Android 日志；不记录提示正文。
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
            __android_log_write(priority, "MovaEdge", text);
        }, nullptr);
        llama_backend_init();
    });
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    const std::string model_path(chars);
    env->ReleaseStringUTFChars(path, chars);

    auto * engine = new Engine();
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    engine->model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (engine->model == nullptr) {
        release(engine);
        fail(env, "llama.cpp 加载 GGUF 失败");
        return 0;
    }
    auto context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_tokens);
    context_params.n_batch = static_cast<uint32_t>(context_tokens);
    context_params.n_ubatch = 128;
    // 在前台运行时使用四线程；无需按设备逻辑核数无限增加线程。
    context_params.n_threads = 4;
    context_params.n_threads_batch = 4;
    context_params.abort_callback = should_abort;
    context_params.abort_callback_data = engine;
    engine->context = llama_init_from_model(engine->model, context_params);
    if (engine->context == nullptr) {
        release(engine);
        fail(env, "llama.cpp 创建推理上下文失败");
        return 0;
    }
    engine->vocab = llama_model_get_vocab(engine->model);
    engine->context_tokens = context_tokens;
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mova_sceneai_data_edge_EdgeNative_nativePredict(
    JNIEnv * env, jobject, jlong handle, jbyteArray prompt_utf8, jint max_new_tokens) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr || prompt_utf8 == nullptr || max_new_tokens <= 0 || max_new_tokens > 384) {
        fail(env, "端侧推理参数无效");
        return nullptr;
    }
    const jsize byte_count = env->GetArrayLength(prompt_utf8);
    std::string prompt(static_cast<size_t>(byte_count), '\0');
    env->GetByteArrayRegion(prompt_utf8, 0, byte_count, reinterpret_cast<jbyte *>(prompt.data()));
    if (env->ExceptionCheck()) return nullptr;

    // parse_special=true 必须识别模板中的 <|im_start|>、<think> 等 Qwen3 token。
    std::vector<llama_token> tokens(std::max<size_t>(prompt.size() + 8, 16));
    int32_t count = llama_tokenize(engine->vocab, prompt.data(), byte_count,
                                   tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (count < 0) {
        tokens.resize(static_cast<size_t>(-count));
        count = llama_tokenize(engine->vocab, prompt.data(), byte_count,
                               tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (count <= 0 || count + max_new_tokens > engine->context_tokens) {
        fail(env, "提示过长或分词失败；已回退云端");
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(count));
    const auto started = elapsed_ms();
    __android_log_print(ANDROID_LOG_INFO, "MovaEdge", "predict tokens=%d", count);
    // CPU 图执行期间由回调检查；生成 token 间也检查，防止手机长时间卡住。
    engine->deadline_ms.store(elapsed_ms() + 30'000);
    llama_memory_clear(llama_get_memory(engine->context), true);
    // 明确 token 位置与唯一序列，整段提示作为一个逻辑批次交给上游内部切分。
    auto prompt_batch = llama_batch_init(count, 0, 1);
    prompt_batch.n_tokens = count;
    for (int32_t i = 0; i < count; ++i) {
        prompt_batch.token[i] = tokens[i];
        prompt_batch.pos[i] = i;
        prompt_batch.n_seq_id[i] = 1;
        prompt_batch.seq_id[i][0] = 0;
        prompt_batch.logits[i] = (i == count - 1);
    }
    const int32_t status = llama_decode(engine->context, prompt_batch);
    llama_synchronize(engine->context);
    llama_batch_free(prompt_batch);
    __android_log_print(ANDROID_LOG_INFO, "MovaEdge", "prefill status=%d elapsed_ms=%lld",
        status, static_cast<long long>(elapsed_ms() - started));
    if (status != 0) {
        const std::string detail = "GGUF 提示预填充失败：status=" + std::to_string(status) +
            " tokens=" + std::to_string(count);
        fail(env, detail.c_str());
        return nullptr;
    }

    auto * sampler = llama_sampler_init_greedy();
    if (sampler == nullptr) {
        fail(env, "无法创建贪心采样器");
        return nullptr;
    }
    std::string output;
    for (int32_t i = 0; i < max_new_tokens; ++i) {
        if (should_abort(engine)) {
            llama_sampler_free(sampler);
            fail(env, "端侧推理超过 30 秒，已回退云端");
            return nullptr;
        }
        llama_token next = llama_sampler_sample(sampler, engine->context, -1);
        if (llama_vocab_is_eog(engine->vocab, next)) break;
        std::vector<char> piece(128);
        int32_t n = llama_token_to_piece(engine->vocab, next, piece.data(),
                                          static_cast<int32_t>(piece.size()), 0, false);
        if (n < 0) {
            piece.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(engine->vocab, next, piece.data(),
                                      static_cast<int32_t>(piece.size()), 0, false);
        }
        if (n < 0) {
            llama_sampler_free(sampler);
            fail(env, "GGUF token 解码失败");
            return nullptr;
        }
        output.append(piece.data(), static_cast<size_t>(n));
        llama_pos position = count + i;
        int32_t sequence_count = 1;
        llama_seq_id sequence = 0;
        llama_seq_id * sequence_ptr = &sequence;
        int8_t logits = 1;
        llama_batch batch{1, &next, nullptr, &position, &sequence_count, &sequence_ptr, &logits};
        if (llama_decode(engine->context, batch) != 0) {
            llama_sampler_free(sampler);
            fail(env, "GGUF 生成阶段失败");
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    __android_log_print(ANDROID_LOG_INFO, "MovaEdge", "predict done elapsed_ms=%lld",
        static_cast<long long>(elapsed_ms() - started));
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                reinterpret_cast<const jbyte *>(output.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mova_sceneai_data_edge_EdgeNative_nativeClose(JNIEnv *, jobject, jlong handle) {
    release(reinterpret_cast<Engine *>(handle));
}
