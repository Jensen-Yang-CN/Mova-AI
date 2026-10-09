"""用 Qwen3 底座或指定 LoRA adapter 生成逐条评测预测。

输入 gold 只提供案例话语与环境信号；模型不会看到 expected 标签。
输出 ``{id, text}`` JSONL 交给 pipeline/evaluate.py 独立评分。
本脚本沿用训练时的契约提示和 enable_thinking=False，避免模板漂移。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

SERVER_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SERVER_DIR))

from pipeline.contract import contract_prompt  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--adapter", type=Path, help="LoRA adapter；不传则评测未微调的底座模型")
    parser.add_argument("--gold", type=Path, default=SERVER_DIR / "data/eval_gold.jsonl")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, help="smoke 测试用")
    parser.add_argument("--max-new-tokens", type=int, default=384)
    args = parser.parse_args()

    if not torch.cuda.is_available():
        parser.error("CUDA 不可用")
    if args.adapter is not None and not args.adapter.is_dir():
        parser.error(f"LoRA adapter 目录不存在：{args.adapter}")
    tokenizer = AutoTokenizer.from_pretrained(args.base_model, local_files_only=True, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=torch.bfloat16,
        low_cpu_mem_usage=True,
        local_files_only=True,
        trust_remote_code=True,
        attn_implementation="sdpa",
    )
    if args.adapter is not None:
        # adapter=None 是未微调底座基线；同一脚本也能评测任意 epoch。
        model = PeftModel.from_pretrained(model, args.adapter, local_files_only=True)
    model.to("cuda").eval()
    args.output.parent.mkdir(parents=True, exist_ok=True)

    count = 0
    with args.gold.open("r", encoding="utf-8") as source, args.output.open("w", encoding="utf-8") as output:
        for line in source:
            if not line.strip():
                continue
            row: dict[str, Any] = json.loads(line)
            prompt = contract_prompt(str(row["signals"]), str(row["utterance"]))
            # 只把输入信息交给模型，不把 expected 泄漏进 prompt。
            encoded = tokenizer.apply_chat_template(
                [{"role": "user", "content": prompt}],
                tokenize=True,
                add_generation_prompt=True,
                enable_thinking=False,
                return_tensors="pt",
            )
            # Transformers 可能返回 Tensor 或 BatchEncoding；统一抽取 input_ids，
            # 以兼容本轮训练服务器上已验证的版本组合。
            if hasattr(encoded, "keys") and "input_ids" in encoded:
                input_ids = encoded["input_ids"]
                attention_mask = encoded.get("attention_mask")
            else:
                input_ids = encoded
                attention_mask = None
            if not isinstance(input_ids, torch.Tensor):
                input_ids = torch.as_tensor(input_ids, dtype=torch.long)
            if input_ids.ndim == 1:
                input_ids = input_ids.unsqueeze(0)
            input_ids = input_ids.to("cuda")
            if attention_mask is None:
                attention_mask = torch.ones_like(input_ids)
            else:
                if not isinstance(attention_mask, torch.Tensor):
                    attention_mask = torch.as_tensor(attention_mask, dtype=torch.long)
                if attention_mask.ndim == 1:
                    attention_mask = attention_mask.unsqueeze(0)
                attention_mask = attention_mask.to(input_ids.device)
            with torch.inference_mode(), torch.autocast("cuda", dtype=torch.bfloat16):
                # 与训练保持一致的非采样解码，便于同一 gold 上比较各 checkpoint。
                generated = model.generate(
                    input_ids=input_ids,
                    attention_mask=attention_mask,
                    do_sample=False,
                    max_new_tokens=args.max_new_tokens,
                    pad_token_id=(
                        tokenizer.pad_token_id
                        if tokenizer.pad_token_id is not None
                        else tokenizer.eos_token_id
                    ),
                    eos_token_id=tokenizer.eos_token_id,
                )
            text = tokenizer.decode(generated[0, input_ids.shape[1] :], skip_special_tokens=True)
            # 保存原始模型输出；JSON 解析与契约评分留给独立评测脚本处理。
            output.write(json.dumps({"id": row["id"], "text": text}, ensure_ascii=False) + "\n")
            count += 1
            if count % 25 == 0:
                print(f"generated {count}")
            if args.limit is not None and count >= args.limit:
                break
    print(f"写出 {count} 条预测：{args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
