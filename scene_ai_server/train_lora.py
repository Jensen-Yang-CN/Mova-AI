"""对 Qwen3-0.6B 执行端侧决策契约的 LoRA 监督微调。

直接读取 build_sft.py 生成的 JSONL，不依赖 datasets。训练目标是结构化
场景、意图、上云判断与槽位，而不是短信、菜谱等长文本。只对 assistant
JSON 的 token 计算损失，user 提示部分用 -100 屏蔽。
"""
from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path
from typing import Any

import torch
from peft import LoraConfig, TaskType, get_peft_model
from torch.utils.data import DataLoader, Dataset
from transformers import AutoModelForCausalLM, AutoTokenizer

SERVER_DIR = Path(__file__).resolve().parent
DEFAULT_DATA = SERVER_DIR / "data/sft_train.jsonl"
DEFAULT_OUTPUT = SERVER_DIR / "data/model_output/qwen3-0.6b-lora"
TARGET_MODULES = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]
# 前四个是注意力投影层；后三个是前馈网络层。仅训练这些模块的 LoRA 增量，
# 底座参数保持冻结，因此可训练参数约占总参数量的 1.665%。

class SFTDataset(Dataset):
    """构造模型输入，并严格保持训练/推理共用的 Qwen3 非思考模式前缀。"""

    def __init__(self, path: Path, tokenizer: Any, max_length: int, limit: int | None = None):
        self.items: list[dict[str, Any]] = []
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                row = json.loads(line)
                messages = row.get("messages")
                if not isinstance(messages, list) or len(messages) != 2:
                    raise ValueError(f"第 {line_no} 行 messages 格式错误")
                if messages[0].get("role") != "user" or messages[1].get("role") != "assistant":
                    raise ValueError(f"第 {line_no} 行必须是 user → assistant 对话")
                prompt_text = tokenizer.apply_chat_template(
                    messages[:1], tokenize=False, add_generation_prompt=True, enable_thinking=False
                )
                full_text = tokenizer.apply_chat_template(
                    messages, tokenize=False, add_generation_prompt=False, enable_thinking=False
                )
                if not isinstance(prompt_text, str) or not isinstance(full_text, str):
                    raise TypeError("Qwen3 chat template 必须返回字符串")
                if not full_text.startswith(prompt_text):
                    raise ValueError(
                        f"{row.get('id')} chat template 的文本 generation prefix 与完整对话不一致；"
                        "请先检查 Qwen3 tokenizer/template"
                    )

                # 文本前缀一致即可；不能要求完整对话 token ID 以 prompt token ID 开头。
                # BPE 可能跨「提示末尾换行 / JSON 开头左花括号」边界合并 token，
                # 从而误报模板不一致。分别编码提示与 assistant 后缀，既规避误报，
                # 又让训练输入与推理时 apply_chat_template 的前缀保持相同。
                prompt_encoding = tokenizer.apply_chat_template(
                    messages[:1], tokenize=True, add_generation_prompt=True, enable_thinking=False
                )
                # Transformers 版本不同，返回值可能是 token ID 列表或 BatchEncoding；
                # 必须先取出 input_ids，不能把 BatchEncoding 与 list 直接相加。
                if hasattr(prompt_encoding, "keys") and "input_ids" in prompt_encoding:
                    prompt_ids = prompt_encoding["input_ids"]
                else:
                    prompt_ids = prompt_encoding
                response_text = full_text[len(prompt_text) :]
                response_ids = tokenizer(response_text, add_special_tokens=False)["input_ids"]
                if hasattr(prompt_ids, "tolist"):
                    prompt_ids = prompt_ids.tolist()
                if hasattr(response_ids, "tolist"):
                    response_ids = response_ids.tolist()
                if prompt_ids and isinstance(prompt_ids[0], list):
                    prompt_ids = prompt_ids[0]
                if response_ids and isinstance(response_ids[0], list):
                    response_ids = response_ids[0]
                full_ids = prompt_ids + response_ids
                # 静默截断可能只留下提示、丢掉完整 JSON 标签，因此宁可中止训练。
                if len(full_ids) > max_length:
                    raise ValueError(
                        f"{row.get('id')} 长度 {len(full_ids)} 超过 max_length={max_length}；"
                        "为避免截掉 JSON target，本脚本不会静默截断"
                    )
                labels = [-100] * len(prompt_ids) + response_ids
                if not any(token != -100 for token in labels):
                    raise ValueError(f"{row.get('id')} 没有 assistant 监督 token")
                self.items.append({"input_ids": full_ids, "labels": labels})
                if limit is not None and len(self.items) >= limit:
                    break
        if not self.items:
            raise ValueError(f"训练文件为空：{path}")
        lengths = [len(item["input_ids"]) for item in self.items]
        print(
            f"训练样本 {len(self.items)} 条；token 长度 min/avg/max = "
            f"{min(lengths)}/{sum(lengths) / len(lengths):.1f}/{max(lengths)}"
        )

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, index: int) -> dict[str, list[int]]:
        return self.items[index]


class CausalCollator:
    """动态填充批次；padding 与 user 提示对应的标签均为 -100。"""

    def __init__(self, pad_token_id: int):
        self.pad_token_id = pad_token_id

    def __call__(self, rows: list[dict[str, list[int]]]) -> dict[str, torch.Tensor]:
        width = max(len(row["input_ids"]) for row in rows)
        input_ids, labels, masks = [], [], []
        for row in rows:
            pad = width - len(row["input_ids"])
            input_ids.append(row["input_ids"] + [self.pad_token_id] * pad)
            labels.append(row["labels"] + [-100] * pad)
            masks.append([1] * len(row["input_ids"]) + [0] * pad)
        return {
            "input_ids": torch.tensor(input_ids, dtype=torch.long),
            "attention_mask": torch.tensor(masks, dtype=torch.long),
            "labels": torch.tensor(labels, dtype=torch.long),
        }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-model", required=True, type=Path, help="本地 Qwen3-0.6B 底座目录")
    parser.add_argument("--train-file", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--limit", type=int, help="smoke 测试样本数")
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--per-device-batch-size", type=int, default=8)
    parser.add_argument("--gradient-accumulation-steps", type=int, default=4)
    parser.add_argument("--max-seq-length", type=int, default=2048)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--memory-fraction", type=float, default=0.17,
        help="PyTorch allocator 上限占整卡显存比例；B200 178 GiB 时约 30 GiB",
    )
    args = parser.parse_args()

    if not args.base_model.is_dir():
        parser.error(f"底座目录不存在：{args.base_model}")
    if not args.train_file.is_file():
        parser.error(f"训练文件不存在：{args.train_file}；先运行 build_sft.py")
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        parser.error(f"输出目录非空，为避免覆盖已有 checkpoint 请换目录：{args.output_dir}")
    if not torch.cuda.is_available():
        parser.error("CUDA 不可用；请在 vllm-v19 训练环境运行")
    if not torch.cuda.is_bf16_supported():
        parser.error("当前 GPU/驱动不支持 bf16")
    if not 0 < args.memory_fraction <= 1:
        parser.error("--memory-fraction 必须在 (0,1] 之间")
    free_bytes, total_bytes = torch.cuda.mem_get_info()
    print(f"GPU={torch.cuda.get_device_name(0)} free={free_bytes / 2**30:.1f} GiB total={total_bytes / 2**30:.1f} GiB")
    if free_bytes < 40 * 2**30:
        parser.error("当前实时空闲显存低于 40 GiB；先重新确认共享 GPU 状态")
    # 这里限制的是本进程 PyTorch allocator 的上限；并非给任务独占整卡。
    torch.cuda.set_per_process_memory_fraction(args.memory_fraction, 0)

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    torch.cuda.manual_seed_all(args.seed)
    tokenizer = AutoTokenizer.from_pretrained(args.base_model, local_files_only=True, trust_remote_code=True)
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token
    tokenizer.padding_side = "right"
    dataset = SFTDataset(args.train_file, tokenizer, args.max_seq_length, args.limit)
    collator = CausalCollator(tokenizer.pad_token_id)
    loader = DataLoader(
        dataset,
        batch_size=args.per_device_batch_size,
        shuffle=True,
        collate_fn=collator,
        pin_memory=True,
        num_workers=0,
    )

    model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=torch.bfloat16,
        low_cpu_mem_usage=True,
        local_files_only=True,
        trust_remote_code=True,
        attn_implementation="sdpa",
    )
    model.config.use_cache = False
    # 重算激活以换显存；这会增加部分计算量，但适合共享 GPU 的 40 GiB 预算。
    model.gradient_checkpointing_enable()
    if hasattr(model, "enable_input_require_grads"):
        model.enable_input_require_grads()
    lora = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        target_modules=TARGET_MODULES,
        bias="none",
        task_type=TaskType.CAUSAL_LM,
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()
    model.to("cuda")

    optimizer = torch.optim.AdamW(
        (parameter for parameter in model.parameters() if parameter.requires_grad),
        lr=args.learning_rate,
        weight_decay=0.0,
    )
    updates_per_epoch = math.ceil(len(loader) / args.gradient_accumulation_steps)
    total_steps = math.ceil(updates_per_epoch * args.epochs)
    warmup_steps = max(1, int(total_steps * 0.05))

    def lr_factor(step: int) -> float:
        if step < warmup_steps:
            return max(1e-8, step / warmup_steps)
        progress = min(1.0, (step - warmup_steps) / max(1, total_steps - warmup_steps))
        return 0.5 * (1.0 + math.cos(math.pi * progress))

    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, lr_factor)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    run_config = vars(args).copy()
    run_config.update({"gpu": torch.cuda.get_device_name(0), "free_gib_at_start": round(free_bytes / 2**30, 2), "total_steps": total_steps})
    (args.output_dir / "run_config.json").write_text(json.dumps(run_config, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    optimizer.zero_grad(set_to_none=True)
    global_step = 0
    for epoch in range(math.ceil(args.epochs)):
        if epoch >= args.epochs:
            break
        model.train()
        running = 0.0
        for step, batch in enumerate(loader):
            batch = {key: value.to("cuda", non_blocking=True) for key, value in batch.items()}
            with torch.autocast("cuda", dtype=torch.bfloat16):
                loss = model(**batch).loss
            running += float(loss.detach())
            remainder = len(loader) % args.gradient_accumulation_steps
            # epoch 尾部不足一个完整梯度累积窗口时，按实际微批次数归一化，
            # 防止最后一次更新的梯度被额外缩小。
            active_accumulation = (
                remainder
                if remainder and step >= len(loader) - remainder
                else args.gradient_accumulation_steps
            )
            (loss / active_accumulation).backward()
            update = (step + 1) % args.gradient_accumulation_steps == 0 or (step + 1) == len(loader)
            if update:
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
                scheduler.step()
                optimizer.zero_grad(set_to_none=True)
                global_step += 1
                if global_step % 10 == 0:
                    peak = torch.cuda.max_memory_allocated() / 2**30
                    print(f"epoch={epoch + 1} step={global_step}/{total_steps} loss={running / (step + 1):.4f} peak={peak:.2f} GiB")

        epoch_dir = args.output_dir / f"epoch-{epoch + 1}"
        # 每轮分别保存 adapter 与 tokenizer，便于独立评测和回退选模。
        epoch_dir.mkdir(parents=True, exist_ok=True)
        model.save_pretrained(epoch_dir, safe_serialization=True)
        tokenizer.save_pretrained(epoch_dir)
        avg_loss = running / max(1, len(loader))
        # peak 是 PyTorch 本进程峰值已分配张量显存，不是 nvidia-smi 整卡占用。
        print(f"saved={epoch_dir} epoch={epoch + 1} avg_loss={avg_loss:.4f} peak={torch.cuda.max_memory_allocated() / 2**30:.2f} GiB")
        torch.cuda.reset_peak_memory_stats()

    model.config.use_cache = True
    print(f"训练完成。各 epoch LoRA adapter 已保存到 {args.output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
