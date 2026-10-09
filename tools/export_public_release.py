"""从本地私有训练产物生成可公开的聚合指标与权重摘要。

原始话语、教师标签、逐条预测、服务端绝对路径和训练日志均不复制到
GitHub。此脚本只读取已下载的正式评测报告与运行配置；如果本地另有
GGUF / 合并清单，则附上经本机重新计算的摘要，便于发布时核对文件。
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "scene_ai_server" / "data"
OUTPUT = ROOT / "artifacts" / "release-v1" / "metrics.json"
FIRST = DATA / "model_output" / "qwen3-0.6b-lora-20260929_231853"
SECOND = DATA / "model_output" / "qwen3-0.6b-round2-20261007_204625"
RELEASE = DATA / "model_output" / "qwen3-0.6b-release-v1"


def read_json(path: Path) -> dict:
    """缺一份正式报告就停止，避免产出半套看似完整的指标。"""
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"报告顶层必须是对象：{path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_run_config(path: Path) -> dict:
    """只公开超参数与执行统计，不公开原始服务器路径和账号。"""
    raw = read_json(path)
    fields = (
        "epochs", "per_device_batch_size", "gradient_accumulation_steps",
        "max_seq_length", "learning_rate", "seed", "memory_fraction",
        "gpu", "free_gib_at_start", "total_steps",
    )
    return {key: raw[key] for key in fields}


def main() -> int:
    first_reports = {
        name: read_json(FIRST / f"eval_{name}" / "eval_report.json")
        for name in ("base", "epoch-1", "epoch-2", "epoch-3")
    }
    second_report = read_json(
        DATA / "independent_eval" / "round2_epoch_2" / "formal500_report.json"
    )
    stress = {
        "round1_epoch3": read_json(
            DATA / "independent_eval" / "epoch-3" / "report.json"
        ),
        "round2_epoch2": read_json(
            DATA / "independent_eval" / "round2_epoch_2"
            / "local_recheck" / "report.json"
        ),
    }
    provenance = {
        "independent_gold": read_json(
            DATA / "independent_eval" / "gold_v1.manifest.json"
        ),
        "round2_data": read_json(
            DATA / "round2" / "20261007_204212" / "manifest.json"
        ),
    }

    result = {
        "description": "Mova-AI 端侧决策模型；非短信正文生成模型",
        "selected_checkpoint": "round1/epoch-3",
        "data_note": "500 条为教师标签评测；150 条为 AI 复核压力题，均非独立人工金标准",
        "run_config": {
            "round1": safe_run_config(FIRST / "run_config.json"),
            "round2": safe_run_config(SECOND / "run_config.json"),
        },
        "formal500": {"round1": first_reports, "round2_epoch2": second_report},
        "stress150": stress,
        "provenance": provenance,
    }

    # 私有合并清单会写入绝对路径；这里只挑选摘要和版本号。
    merge_path = RELEASE / "merged_hf" / "merge_manifest.json"
    if merge_path.is_file():
        merge = read_json(merge_path)
        result["merged_hf"] = {
            "base_model_sha256": merge["base_model_sha256"],
            "adapter_sha256": merge["adapter_sha256"],
            "weights": merge["weights"],
            "torch": merge["torch"],
            "transformers": merge["transformers"],
            "peft": merge["peft"],
        }

    # 权重文件若已下载到本地，重新计算而非抄录截图中的哈希。
    for label, filename in (
        ("f16_gguf", "qwen3-0.6b-epoch3-f16.gguf"),
        ("q4_k_m_gguf", "qwen3-0.6b-epoch3-Q4_K_M.gguf"),
    ):
        path = RELEASE / filename
        if path.is_file():
            result[label] = {
                "file": filename,
                "bytes": path.stat().st_size,
                "sha256": sha256(path),
            }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"已写出脱敏聚合记录：{OUTPUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
