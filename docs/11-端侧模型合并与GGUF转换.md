# 第一轮 epoch-3：合并 LoRA、转换 GGUF 与 Q4_K_M 量化

当前选用第一轮 `epoch-3` 作为项目原型的端侧决策模型。已完成**LoRA 合并 → 独立 HF 权重 → F16 GGUF → Q4_K_M GGUF → 单条 CPU 推理**；Android 端的 GGUF 执行器尚未接入。生成文件统一放在 `scene_ai_server/data/model_output/`，底座与 LoRA adapter 保持原样。下文的路径是示例，运行时按实际工作目录替换。

合并使用 PEFT 的 [`merge_and_unload(safe_merge=True)`](https://huggingface.co/docs/peft/en/package_reference/peft_model#peft.PeftModel.merge_and_unload)；GGUF 转换使用 [llama.cpp 官方脚本](https://github.com/ggml-org/llama.cpp/blob/master/convert_hf_to_gguf.py)。[Qwen3 官方说明](https://github.com/QwenLM/Qwen3/blob/main/docs/source/run_locally/llama.cpp.md)确认 llama.cpp 支持 Qwen3，并说明自有 HF 权重的 GGUF 转换方式。

## 1. 上传与检查

只需把主仓库的 `scene_ai_server/merge_lora.py` 上传到服务器同名目录。第一轮 adapter **只在服务器**，因此在服务器上执行合并；不要把第二轮 `qwen3-0.6b-round2-*` 目录当作输入。

```bash
cd /path/to/Mova_AI/scene_ai_server
source ~/miniconda3/etc/profile.d/conda.sh
conda activate vllm-v19
source ~/venvs/mova-train/bin/activate

BASE_MODEL="/path/to/Qwen3-0.6B"
ADAPTER="data/model_output/qwen3-0.6b-lora-20260929_231853/epoch-3"
RELEASE="data/model_output/qwen3-0.6b-release-v1"

python3 -m py_compile merge_lora.py
python3 -c 'import torch, transformers, peft; print(torch.__version__, transformers.__version__, peft.__version__)'
test -f "$BASE_MODEL/model.safetensors" && test -f "$ADAPTER/adapter_model.safetensors"
df -h data/model_output
```

模型合并与 F16 GGUF 都是约 1 GB 量级的文件，开始前最好保留至少数 GB 空闲空间。这里的合并在 CPU 上执行，不需要占用同事共用的 B200 显存。

## 2. 合并第一轮 epoch-3

已知原始底座 `model.safetensors` 的 SHA-256 是 `f47f71177f32bcd101b7573ec9171e6a57f4f4d31148d38e382306f42996874b`；本次服务器合并前实际校验得到同一摘要。若不匹配，先核对底座来源，不应跳过检查继续合并。

```bash
mkdir -p data/train_logs
python3 -u merge_lora.py \
  --base-model "$BASE_MODEL" \
  --adapter "$ADAPTER" \
  --output-dir "$RELEASE/merged_hf" \
  --expected-base-sha256 f47f71177f32bcd101b7573ec9171e6a57f4f4d31148d38e382306f42996874b \
  2>&1 | tee data/train_logs/merge_epoch3.log
echo "merge_exit=${PIPESTATUS[0]}"
```

实际 `merge_exit=0`。输出包含 `merged_hf/config.json`、`model.safetensors`、`tokenizer.json` 和 `merge_manifest.json`；合并权重为 1,192,135,096 字节。如果输出目录先前已经存在，脚本会拒绝覆盖。`merge_manifest.json` 含服务器绝对路径，只留在私有产物目录，公开记录使用脱敏摘要。

## 3. 转换为 F16 GGUF

`llama.cpp` 放在项目目录之外，避免将上游工具和生成的大文件放进 Mova-AI 仓库。服务器 GitHub 连接慢时，可先在本地下载 [llama.cpp 官方仓库](https://github.com/ggml-org/llama.cpp)，再上传整个工具目录。

```bash
LLAMA_CPP="/path/to/llama.cpp"
if [ ! -f "$LLAMA_CPP/convert_hf_to_gguf.py" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$LLAMA_CPP"
fi
git -C "$LLAMA_CPP" rev-parse HEAD
python3 "$LLAMA_CPP/convert_hf_to_gguf.py" --help >/dev/null
```

`--help` 若报缺包，先看具体缺少什么；不要在 `mova-train` 环境里直接安装上游完整 `requirements.txt`，它可能替换已验证的 PyTorch 版本。预检查通过后转换：

```bash
GGUF="$RELEASE/qwen3-0.6b-epoch3-f16.gguf"
python3 "$LLAMA_CPP/convert_hf_to_gguf.py" \
  "$RELEASE/merged_hf" \
  --outfile "$GGUF" \
  --outtype f16 \
  2>&1 | tee data/train_logs/convert_epoch3_f16.log
echo "convert_exit=${PIPESTATUS[0]}"

python3 - "$GGUF" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
with path.open("rb") as f:
    assert f.read(4) == b"GGUF", "输出文件没有 GGUF 文件头"
print(f"GGUF 文件：{path}；大小：{path.stat().st_size:,} 字节")
PY
sha256sum "$GGUF"
```

实际 `convert_exit=0`，写出 310 个张量、约 1.2 GB 的 F16 GGUF。若 GitHub 克隆在接收对象时出现 `curl 56`、`early EOF`，这是源码下载中断；换新目录以 HTTP/1.1 浅克隆后已恢复，合并模型未受影响。

## 4. 编译 CPU 量化工具并执行 Q4_K_M

量化工具属于上游 llama.cpp，仓库只记录调用步骤；不把第三方源码复制进 Mova-AI。CPU 构建无需占用共享 GPU。

```bash
cmake -S "$LLAMA_CPP" -B "$LLAMA_CPP/build" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=OFF
cmake --build "$LLAMA_CPP/build" --config Release --target llama-quantize -j 4
test -x "$LLAMA_CPP/build/bin/llama-quantize"

Q4="$RELEASE/qwen3-0.6b-epoch3-Q4_K_M.gguf"
"$LLAMA_CPP/build/bin/llama-quantize" "$GGUF" "$Q4" Q4_K_M \
  2>&1 | tee data/train_logs/quantize_epoch3_q4km.log
echo "quantize_exit=${PIPESTATUS[0]}"
ls -lh "$GGUF" "$Q4"
sha256sum "$Q4"
```

本次量化退出码为 0。工具报告原模型 1137.00 MiB、量化后 372.65 MiB（5.24 BPW）；文件列表按十进制显示约 1.2 GB → 379 MB。Q4_K_M 是混合量化方案，不能把文件名中的“Q4”误读为每个张量恰好 4 bit。服务器量化耗时约 15.5 秒，只是该机器上的单次执行时间。

## 5. 使用正式案例做单条 GGUF 推理

训练任务是结构化判断，不是生成短信正文。为避免把 Qwen3 默认聊天模板再套一次，先用仓库脚本从**正式 500 条 gold** 生成非思考模式的完整前缀，再用 `llama-completion -no-cnv` 直接读取。

```bash
cmake --build "$LLAMA_CPP/build" --config Release --target llama-completion -j 4
python3 prepare_gguf_prompt.py \
  --tokenizer-dir "$RELEASE/merged_hf" \
  --gold data/eval_gold.jsonl \
  --sample-id chat_41123 \
  --output "$RELEASE/smoke_prompt.txt"

"$LLAMA_CPP/build/bin/llama-completion" \
  -m "$Q4" -f "$RELEASE/smoke_prompt.txt" \
  -no-cnv --no-display-prompt -ngl 0 -t 4 -c 4096 -n 384 --temp 0 \
  2>&1 | tee data/train_logs/gguf_q4_smoke.log
echo "inference_exit=${PIPESTATUS[0]}"
```

本次 `inference_exit=0`，在 CPU 上输出合法 JSON：`scene=chat`、`intent=reply_suggest`、`need_cloud=true`、`goal=拒绝`、`relation=同事`。正式 gold 的 `target_tone=礼貌`，量化模型输出 `委婉`；这是一个槽位差异，不能称为该条全字段命中。日志的 `no usable GPU` 来自主动编译的 CPU 后端，非失败。该次约 25.16 token/s 是服务器 CPU 的单条生成速度，不代表 Android 实机性能。

## 6. 功能边界与下一步

`need_cloud=true` 的含义是：端侧模型认为此请求需要云端生成。单独运行 GGUF 只会输出决策 JSON，**不会自动调用 `/chat/reply`、也不会产生短信正文**。现有 FastAPI 已实现云端生成接口，Android App 当前仍直接走云端；GGUF 执行、契约校验及 `need_cloud` 到云端接口的连接尚待实现。接入后应展示云端返回的 `reply` 给用户，而不是把路由 JSON 当作聊天结果。

本节只证明量化文件可以在服务器 CPU 加载和推理，不宣称移动端部署或真实用户集准确率。正式数据、预测、权重与含绝对路径的原始日志保留在私有数据目录；公开仓库只保留代码、脱敏的聚合指标和摘要。训练采用 Qwen3 `enable_thinking=False`；Qwen3 官方说明默认 llama.cpp 聊天模板不直接暴露这一硬开关，Android 集成要继续保证相同前缀。
