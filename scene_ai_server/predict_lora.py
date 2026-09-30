"""Generate prediction JSONL from one saved Mova-AI LoRA adapter."""
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
    parser.add_argument("--adapter", type=Path, required=True)
    parser.add_argument("--gold", type=Path, default=SERVER_DIR / "data/eval_gold.jsonl")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, help="smoke 测试用")
    parser.add_argument("--max-new-tokens", type=int, default=384)
    args = parser.parse_args()

    if not torch.cuda.is_available():
        parser.error("CUDA 不可用")
    tokenizer = AutoTokenizer.from_pretrained(args.base_model, local_files_only=True, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=torch.bfloat16,
        low_cpu_mem_usage=True,
        local_files_only=True,
        trust_remote_code=True,
        attn_implementation="sdpa",
    )
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
            encoded = tokenizer.apply_chat_template(
                [{"role": "user", "content": prompt}],
                tokenize=True,
                add_generation_prompt=True,
                enable_thinking=False,
                return_tensors="pt",
            )
            encoded = encoded.to("cuda")
            with torch.inference_mode(), torch.autocast("cuda", dtype=torch.bfloat16):
                generated = model.generate(
                    input_ids=encoded,
                    attention_mask=torch.ones_like(encoded),
                    do_sample=False,
                    max_new_tokens=args.max_new_tokens,
                    pad_token_id=(
                        tokenizer.pad_token_id
                        if tokenizer.pad_token_id is not None
                        else tokenizer.eos_token_id
                    ),
                    eos_token_id=tokenizer.eos_token_id,
                )
            text = tokenizer.decode(generated[0, encoded.shape[1] :], skip_special_tokens=True)
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
