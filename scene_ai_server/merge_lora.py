"""把指定的 LoRA adapter 合并进本地 Qwen3-0.6B 底座，导出独立 HF 模型。

用法：python merge_lora.py --base-model ... --adapter ... --output-dir ...
本脚本只读取底座和 adapter；输出目录必须是新目录，避免覆盖训练权重。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def sha256(path: Path) -> str:
    """流式计算权重摘要，避免把约 1 GB 的文件一次读入内存。"""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-model", required=True, type=Path, help="本地 Qwen3-0.6B 底座目录")
    parser.add_argument("--adapter", required=True, type=Path, help="第一轮 epoch-3 LoRA 目录")
    parser.add_argument("--output-dir", required=True, type=Path, help="新建的独立模型目录")
    parser.add_argument(
        "--expected-base-sha256", default="",
        help="可选：底座 model.safetensors 的已知 SHA-256，不一致则停止",
    )
    args = parser.parse_args()

    base = args.base_model.resolve()
    adapter = args.adapter.resolve()
    output = args.output_dir.resolve()
    base_weights = base / "model.safetensors"
    adapter_weights = adapter / "adapter_model.safetensors"
    # 合并前要求底座、分词器与 adapter 配置齐全；不能只凭目录存在判定可用。
    for path in (base / "config.json", base_weights, base / "tokenizer.json",
                 adapter / "adapter_config.json", adapter_weights):
        if not path.is_file():
            parser.error(f"缺少文件：{path}")
    if output.exists():
        parser.error(f"输出目录已存在，拒绝覆盖：{output}")
    if output.is_relative_to(base) or output.is_relative_to(adapter):
        parser.error("输出目录不能放在底座或 adapter 目录里面")

    base_config = json.loads((base / "config.json").read_text(encoding="utf-8"))
    adapter_config = json.loads((adapter / "adapter_config.json").read_text(encoding="utf-8"))
    if base_config.get("model_type") != "qwen3" or adapter_config.get("peft_type") != "LORA":
        parser.error("输入不是预期的 Qwen3 底座与 LoRA adapter")

    base_sha = sha256(base_weights)
    # 固定底座哈希是为了避免把 LoRA 错合并到同名但不同版本的权重上。
    if args.expected_base_sha256 and base_sha.lower() != args.expected_base_sha256.lower():
        parser.error(f"底座 SHA-256 不匹配：实际 {base_sha}")
    adapter_sha = sha256(adapter_weights)
    print(f"底座 SHA-256：{base_sha}", flush=True)
    print(f"LoRA SHA-256：{adapter_sha}", flush=True)

    # 训练使用 BF16；合并时保留这个精度，GGUF 转换再显式输出 F16。
    import torch
    import peft
    import transformers
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(base, local_files_only=True, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        base, torch_dtype=torch.bfloat16, low_cpu_mem_usage=True,
        local_files_only=True, trust_remote_code=True,
    )
    model = PeftModel.from_pretrained(model, adapter, local_files_only=True)
    # safe_merge 会检查异常权重；卸载后产物可独立加载，不再依赖 LoRA 文件。
    model = model.merge_and_unload(safe_merge=True)

    output.mkdir(parents=True)
    model.save_pretrained(output, safe_serialization=True, max_shard_size="2GB")
    tokenizer.save_pretrained(output)
    if (base / "LICENSE").is_file():
        # 如需单独分发衍生权重，保留底座随附的许可证文件。
        shutil.copy2(base / "LICENSE", output / "LICENSE")

    saved_weights = sorted(output.glob("*.safetensors"))
    if not saved_weights or not (output / "config.json").is_file() or not (output / "tokenizer.json").is_file():
        raise RuntimeError(f"合并输出缺少模型权重、配置或 tokenizer：{output}")
    if (output / "adapter_config.json").exists():
        raise RuntimeError("输出仍含 adapter_config.json，不是独立合并模型")

    manifest = {
        # manifest 记录输入与输出权重、版本，便于证明 GGUF 来自哪次选模。
        "base_model": str(base),
        "base_model_sha256": base_sha,
        "adapter": str(adapter),
        "adapter_sha256": adapter_sha,
        "output_dir": str(output),
        "weights": [
            {"file": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)}
            for path in saved_weights
        ],
        "torch": torch.__version__,
        "transformers": transformers.__version__,
        "peft": peft.__version__,
    }
    (output / "merge_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"已合并为独立模型：{output}", flush=True)
    for item in manifest["weights"]:
        print(f"权重 {item['file']}：{item['bytes']} 字节，SHA-256 {item['sha256']}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
