# 第一轮 epoch-3：合并 LoRA 与转换 GGUF

当前选用第一轮 `epoch-3` 作为项目原型的端侧模型。这里先完成**独立 HF 权重 → F16 GGUF**，暂不量化或接入 Android。生成文件统一放在 `scene_ai_server/data/model_output/`，不会提交 GitHub。底座与 LoRA adapter 保持原样。

合并使用 PEFT 的 [`merge_and_unload(safe_merge=True)`](https://huggingface.co/docs/peft/en/package_reference/peft_model#peft.PeftModel.merge_and_unload)；GGUF 转换使用 [llama.cpp 官方脚本](https://github.com/ggml-org/llama.cpp/blob/master/convert_hf_to_gguf.py)。[Qwen3 官方说明](https://github.com/QwenLM/Qwen3/blob/main/docs/source/run_locally/llama.cpp.md)确认 llama.cpp 支持 Qwen3，并说明自有 HF 权重的 GGUF 转换方式。

## 1. 上传与检查

只需把主仓库的 `scene_ai_server/merge_lora.py` 上传到服务器同名目录。第一轮 adapter **只在服务器**，因此在服务器上执行合并；不要把第二轮 `qwen3-0.6b-round2-*` 目录当作输入。

```bash
cd ~/kube-user/CodeBase_YangJunjie/Mova_AI/scene_ai_server
source ~/miniconda3/etc/profile.d/conda.sh
conda activate vllm-v19
source ~/venvs/mova-train/bin/activate

BASE_MODEL="$HOME/kube-user/CodeBase_YangJunjie/models/Qwen3-0.6B"
ADAPTER="data/model_output/qwen3-0.6b-lora-20260929_231853/epoch-3"
RELEASE="data/model_output/qwen3-0.6b-release-v1"

python3 -m py_compile merge_lora.py
python3 -c 'import torch, transformers, peft; print(torch.__version__, transformers.__version__, peft.__version__)'
test -f "$BASE_MODEL/model.safetensors" && test -f "$ADAPTER/adapter_model.safetensors"
df -h data/model_output
```

模型合并与 F16 GGUF 都是约 1 GB 量级的文件，开始前最好保留至少数 GB 空闲空间。这里的合并在 CPU 上执行，不需要占用同事共用的 B200 显存。

## 2. 合并第一轮 epoch-3

已知本地原始底座 `model.safetensors` 的 SHA-256 是 `f47f71177f32bcd101b7573ec9171e6a57f4f4d31148d38e382306f42996874b`；服务器上的同一文件此前没有单独核验，本次会在合并前校验。若不匹配，先核对底座来源，不应跳过检查继续合并。

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

只在 `merge_exit=0` 时继续。输出应包含 `merged_hf/config.json`、`model.safetensors`、`tokenizer.json` 和 `merge_manifest.json`；后者记录输入与输出权重的哈希。如果输出目录先前已经存在，脚本会拒绝覆盖。

## 3. 转换为 F16 GGUF

`llama.cpp` 放在项目目录之外，避免将上游工具和生成的大文件放进 Mova-AI 仓库。服务器 GitHub 连接慢时，可先在本地下载 [llama.cpp 官方仓库](https://github.com/ggml-org/llama.cpp)，再上传整个工具目录。

```bash
LLAMA_CPP="$HOME/kube-user/CodeBase_YangJunjie/llama.cpp"
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

`convert_exit=0` 且文件头为 `GGUF` 才说明格式转换完成；这还不等于手机端推理已跑通。下一步再做 Q4_K_M 量化及 llama.cpp/Android 加载。训练采用 Qwen3 `enable_thinking=False`，而 Qwen3 官方提醒 llama.cpp 的默认聊天模板不直接暴露这个硬开关；接入端侧时要单独处理模板，避免转换后推理提示与训练不一致。
