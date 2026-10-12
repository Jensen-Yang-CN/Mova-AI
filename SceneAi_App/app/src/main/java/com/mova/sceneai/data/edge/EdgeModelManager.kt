package com.mova.sceneai.data.edge

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.mova.sceneai.data.repo.EdgeRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class EdgeObservation(
    val decision: EdgeDecision?,
    val latencyMs: Long,
    val failure: String? = null,
)

/**
 * GGUF 保存在 App 私有目录，首次由系统文件选择器导入；不打进 APK，也不上传云端。
 * 文件先流式复制与核验；替换、加载与推理共享锁，避免 native 仍读旧模型时替换。
 */
class EdgeModelManager(private val context: Context) {
    private val descriptor = context.assets.open("edge_model.json").bufferedReader().use {
        JSONObject(it.readText())
    }
    private val modelName = descriptor.getString("model_name")
    private val expectedBytes = descriptor.getLong("bytes")
    private val expectedSha256 = descriptor.getString("sha256")
    private val file = File(File(context.filesDir, "edge"), descriptor.getString("file"))
    private val mutex = Mutex()
    private var native: EdgeNative? = null
    private val prompt = EdgePrompt(context)

    private val _state = MutableStateFlow(EdgeRuntimeState())
    val state: StateFlow<EdgeRuntimeState> = _state.asStateFlow()

    suspend fun restore() = withContext(Dispatchers.IO) {
        mutex.withLock { loadVerifiedFile() }
    }

    suspend fun installFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        val parent = file.parentFile ?: error("端侧模型目录不存在")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建端侧模型目录" }
        val temporary = File(parent, "${file.name}.part")
        try {
            _state.value = _state.value.copy(loadingError = null)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("无法读取所选 GGUF 文件")
            input.use { source ->
                temporary.outputStream().buffered().use { target ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= expectedBytes) { "所选文件超过预期大小" }
                        target.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            require(bytes == expectedBytes && digest.digest().toHex() == expectedSha256) {
                "所选模型的大小或 SHA-256 不符；请选择本轮 Q4_K_M GGUF"
            }
            mutex.withLock {
                native?.close()
                native = null
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                loadVerifiedFile(alreadyVerified = true)
            }
        } catch (e: Throwable) {
            temporary.delete()
            if (e is CancellationException) throw e
            _state.value = _state.value.copy(loadingError = e.message ?: "端侧模型导入失败")
            throw e
        }
    }

    /** 仅适用于短文本场景；JSON 校验失败时调用方自动走原有云端链路。 */
    suspend fun observe(utterance: String): EdgeObservation = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engine = native ?: return@withLock EdgeObservation(null, 0, "端侧模型未加载")
            val started = SystemClock.elapsedRealtime()
            try {
                val raw = engine.predict(prompt.render(utterance))
                EdgeObservation(EdgeDecisionParser.parse(raw), SystemClock.elapsedRealtime() - started)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                EdgeObservation(null, SystemClock.elapsedRealtime() - started, e.message ?: "端侧推理失败")
            }
        }
    }

    private fun loadVerifiedFile(alreadyVerified: Boolean = false) {
        native?.close()
        native = null
        if (!file.exists()) {
            _state.value = EdgeRuntimeState()
            return
        }
        _state.value = EdgeRuntimeState(deployed = true, name = modelName,
            sizeMb = expectedBytes / 1_000_000.0, quantization = "Q4_K_M")
        try {
            require(file.length() == expectedBytes && (alreadyVerified || sha256(file) == expectedSha256)) {
                "本地 GGUF 校验失败，请重新导入"
            }
            require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }) {
                "当前设备 ABI 不支持本项目的端侧推理"
            }
            val engine = EdgeNative()
            engine.load(file)
            native = engine
            _state.value = _state.value.copy(loaded = true, loadingError = null)
        } catch (e: Throwable) {
            native?.close()
            native = null
            _state.value = _state.value.copy(loaded = false, loadingError = e.message ?: "模型加载失败")
        }
    }

    private fun sha256(path: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

/** JNI 方法只处理原始提示与 token 解码；任务契约、模型哈希和回退策略留在 Kotlin。 */
internal class EdgeNative {
    private var handle = 0L

    init { System.loadLibrary("mova_edge") }

    fun load(model: File) {
        handle = nativeLoad(model.absolutePath, 2048)
        check(handle != 0L) { "llama.cpp 未能加载 GGUF" }
    }

    fun predict(prompt: String): String {
        check(handle != 0L) { "端侧模型尚未加载" }
        // JNI 传 UTF-8 字节，避免 Modified UTF-8 对聊天中的 emoji 产生歧义。
        return nativePredict(handle, prompt.toByteArray(Charsets.UTF_8), 256)
            .toString(Charsets.UTF_8)
    }

    fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private external fun nativeLoad(path: String, contextTokens: Int): Long
    private external fun nativePredict(handle: Long, promptUtf8: ByteArray, maxNewTokens: Int): ByteArray
    private external fun nativeClose(handle: Long)
}
