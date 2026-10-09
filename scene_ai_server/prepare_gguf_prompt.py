"""为 GGUF 冒烟推理生成与 LoRA 训练一致的 Qwen3 提示文件。

从指定 gold 中选取一条话语，但只读取 signals / utterance 构造模型输入；
expected 标签仅用于离线核对，不进入提示。默认校验本轮正式 500 条 gold
的 MD5，防止误用仓库内同名的 40 条 pilot 文件。
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from transformers import AutoTokenizer

from pipeline.contract import contract_prompt


FORMAL_GOLD_MD5 = "27a33ffbf5d2dd01d391533cb0443f3c"


def md5(path: Path) -> str:
    """按块读取文件，校验 gold 批次身份。"""
    digest = hashlib.md5()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def choose_case(path: Path, sample_id: str | None) -> dict:
    """按 ID 选案例；未指定时取第一条，避免把整份 gold 送进模型。"""
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                row = json.loads(line)
                if sample_id is None or row.get("id") == sample_id:
                    return row
    raise ValueError(f"gold 中找不到案例：{sample_id!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tokenizer-dir", type=Path, required=True,
                        help="已合并 HF 模型目录，内含 tokenizer.json")
    parser.add_argument("--gold", type=Path, required=True,
                        help="服务器正式 500 条评测 gold；不要使用仓库内 pilot")
    parser.add_argument("--sample-id", help="可选；不传则取 gold 第一条")
    parser.add_argument("--output", type=Path, required=True,
                        help="写出的 UTF-8 提示文本路径")
    parser.add_argument("--expected-gold-md5", default=FORMAL_GOLD_MD5,
                        help="默认锁定正式 gold；传空字符串才跳过批次检查")
    args = parser.parse_args()

    if not (args.tokenizer_dir / "tokenizer.json").is_file():
        parser.error(f"缺少 tokenizer.json：{args.tokenizer_dir}")
    if not args.gold.is_file():
        parser.error(f"gold 文件不存在：{args.gold}")
    actual_md5 = md5(args.gold)
    if args.expected_gold_md5 and actual_md5 != args.expected_gold_md5:
        parser.error(
            f"gold MD5 不符：expected={args.expected_gold_md5} actual={actual_md5}；"
            "请确认没有拿 40 条 pilot 代替正式评测集"
        )

    row = choose_case(args.gold, args.sample_id)
    # 这里必须复用训练与 predict_lora.py 的同一契约提示；
    # Qwen3 的 enable_thinking=False 会在 assistant 前缀中封闭思考段。
    prompt = contract_prompt(str(row["signals"]), str(row["utterance"]))
    tokenizer = AutoTokenizer.from_pretrained(
        args.tokenizer_dir, local_files_only=True, trust_remote_code=True
    )
    rendered = tokenizer.apply_chat_template(
        [{"role": "user", "content": prompt}],
        tokenize=False,
        add_generation_prompt=True,
        enable_thinking=False,
    )
    if not isinstance(rendered, str) or not rendered:
        raise ValueError("Qwen3 chat template 没有生成有效文本")

    # 原样写入，不补额外换行；llama-completion 用 -no-cnv 读取完整前缀，
    # 避免 CLI 再套一层默认聊天模板。
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(f"案例 ID：{row['id']}")
    print(f"用户输入：{row['utterance']}")
    print(f"gold MD5：{actual_md5}")
    print(f"提示文件：{args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
