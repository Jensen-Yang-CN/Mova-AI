"""把最终教师标注 JSONL 转为 Qwen3 对话式 SFT 数据。

输入是 ``{id, signals, utterance, target}``；输出只包含一轮 user/assistant
对话。user 使用线上推理同一份能力契约提示词，assistant 是教师给出的
结构化 JSON。正式批次先校验 MD5，避免误把仓库中的 60 条 pilot 用于训练。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

SERVER_DIR = Path(__file__).resolve().parent
REPO_ROOT = SERVER_DIR.parent
sys.path.insert(0, str(SERVER_DIR))

from pipeline.contract import contract_prompt, validate  # noqa: E402

EXPECTED_DATASET_MD5 = "d32b73adbc456212f42eaae2099d93ee"


def md5(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build(source: Path, output: Path, limit: int | None, expected_md5: str) -> int:
    # 先核对源数据身份，再创建输出；批次拿错时不留下貌似可训练的文件。
    actual_md5 = md5(source)
    if expected_md5 and actual_md5 != expected_md5:
        raise SystemExit(
            f"训练集 md5 不匹配：expected={expected_md5} actual={actual_md5}；拒绝生成 SFT 文件"
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    seen: set[str] = set()
    count = 0
    with source.open("r", encoding="utf-8") as src, output.open("w", encoding="utf-8") as dst:
        for line_no, line in enumerate(src, 1):
            if not line.strip():
                continue
            row: dict[str, Any] = json.loads(line)
            if limit is not None and count >= limit:
                break
            sample_id = str(row.get("id", ""))
            if not sample_id or sample_id in seen:
                raise ValueError(f"第 {line_no} 行 id 缺失或重复：{sample_id!r}")
            seen.add(sample_id)

            target = row.get("target")
            # 教师标签要先通过场景、意图、槽位等契约检查；训练阶段不替教师纠错。
            errors = validate(target)
            if errors:
                raise ValueError(f"{sample_id} 教师标签不符合契约：{errors}")

            user = contract_prompt(str(row["signals"]), str(row["utterance"]))
            assistant = json.dumps(target, ensure_ascii=False)
            # 保留 id/scene/intent 便于追踪；训练脚本只消费 messages。
            example = {
                "id": sample_id,
                "scene": target["scene"],
                "intent": target["intent"],
                "messages": [
                    {"role": "user", "content": user},
                    {"role": "assistant", "content": assistant},
                ],
            }
            dst.write(json.dumps(example, ensure_ascii=False) + "\n")
            count += 1

    if not count:
        raise ValueError(f"没有从 {source} 读到训练样本")
    print(f"训练集 md5: {actual_md5}")
    print(f"写出 {count} 条 SFT 样本: {output}")
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=SERVER_DIR / "data/distill_dataset.jsonl")
    parser.add_argument("--output", type=Path, default=SERVER_DIR / "data/sft_train.jsonl")
    parser.add_argument("--limit", type=int, help="smoke 测试用；不传则处理全量")
    parser.add_argument(
        "--expected-md5",
        default=EXPECTED_DATASET_MD5,
        help="期望训练集 md5；传空字符串可跳过（不推荐）",
    )
    args = parser.parse_args()
    if args.limit is not None and args.limit <= 0:
        parser.error("--limit 必须大于 0")
    build(args.input, args.output, args.limit, args.expected_md5)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
