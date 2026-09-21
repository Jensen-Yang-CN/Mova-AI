"""评测：契约遵循度与任务准确率。

## 关于评测集的诚实说明

`eval_gold.jsonl` 里的标签是**教师产出、尚未经人工核验**的。
因此拿同一个教师去评它必然是 100%，这个数字没有意义。

本脚本因此提供两种真正有用的用法：

    ① **测标签噪声下限**（--predictor teacher-b）
       用另一个教师在评测集上作答，得到的分数就是"两个教师之间的一致程度"，
       也就是这份自动标注数据集的**精度天花板**。它回答的是：
       "如果端侧学生完全学会了教师 A，它最多能有多准？"
       这个数字不漂亮，但它是真的。

    ② **测自己的模型**（--predictor file --predictions x.jsonl）
       学生模型训好之后的评测入口。文件格式：每行
       {"id": "...", "text": "<模型原始输出>"}，脚本会自己解析与校验 JSON。

## 指标

    JSON/契约合法率   最重要的工程指标。端侧小模型最大的失败模式就是格式崩坏，
                      这个数字不达标，其他指标都没有意义。
    scene / intent 准确率
    need_cloud 准确率  路由决策的准确率
    complexity MAE     复杂度打分的平均绝对误差（回归视角）
    槽位 微平均 P/R/F1
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jsonx import extract_json  # noqa: E402

from pipeline.contract import validate  # noqa: E402
from pipeline.teacher import TeacherClient, TeacherError, build_teachers  # noqa: E402

SERVER_DIR = Path(__file__).resolve().parent.parent


# ============================================================
# 指标
# ============================================================

def pair_slots(obj: dict[str, Any] | None) -> set[tuple[str, Any]]:
    """把槽位摊平成 (key=value) 集合，用微平均算 P/R/F1。"""
    if not isinstance(obj, dict) or not isinstance(obj.get("slots"), dict):
        return set()
    pairs: set[tuple[str, Any]] = set()
    for key, value in obj["slots"].items():
        if isinstance(value, list):
            for item in value:
                pairs.add((key, str(item)))
        else:
            pairs.add((key, value))
    return pairs


def evaluate(predictions: list[str | None], gold: list[dict[str, Any]]) -> dict[str, Any]:
    """predictions 与 gold 一一对应；None 表示调用失败。"""
    total = len(gold)
    valid = 0
    scene_hit = intent_hit = cloud_hit = 0
    complexity_errors: list[float] = []
    tp = fp = fn = 0
    slot_type_hits = 0
    slot_type_total = 0
    per_scene: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    failures: dict[str, int] = defaultdict(int)

    for raw, item in zip(predictions, gold):
        expected = item.get("expected") or {}
        parsed = extract_json(raw) if isinstance(raw, str) else None
        if not isinstance(parsed, dict):
            failures["无法解析 JSON"] += 1
            fn += len(pair_slots(expected))
            continue
        errors = validate(parsed)
        if errors:
            failures[errors[0].split("：")[0]] += 1
            fn += len(pair_slots(expected))
            continue

        valid += 1
        scene_ok = parsed.get("scene") == expected.get("scene")
        intent_ok = parsed.get("intent") == expected.get("intent")
        scene_hit += scene_ok
        intent_hit += intent_ok
        cloud_hit += parsed.get("need_cloud") == expected.get("need_cloud")
        per_scene[str(expected.get("scene"))][1] += 1
        per_scene[str(expected.get("scene"))][0] += scene_ok and intent_ok

        try:
            complexity_errors.append(
                abs(float(parsed.get("complexity", 0.0)) - float(expected.get("complexity", 0.0)))
            )
        except (TypeError, ValueError):
            pass

        predicted_pairs = pair_slots(parsed)
        expected_pairs = pair_slots(expected)
        tp += len(predicted_pairs & expected_pairs)
        fp += len(predicted_pairs - expected_pairs)
        fn += len(expected_pairs - predicted_pairs)

        for key in set(parsed.get("slots") or {}) | set(expected.get("slots") or {}):
            slot_type_total += 1
            if (parsed.get("slots") or {}).get(key) == (expected.get("slots") or {}).get(key):
                slot_type_hits += 1

    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0

    return {
        "total": total,
        "contract_valid": valid,
        "contract_valid_rate": round(valid / total, 4) if total else 0.0,
        "scene_accuracy": round(scene_hit / valid, 4) if valid else 0.0,
        "intent_accuracy": round(intent_hit / valid, 4) if valid else 0.0,
        "need_cloud_accuracy": round(cloud_hit / valid, 4) if valid else 0.0,
        "complexity_mae": round(sum(complexity_errors) / len(complexity_errors), 4)
        if complexity_errors
        else None,
        "slot_precision": round(precision, 4),
        "slot_recall": round(recall, 4),
        "slot_f1": round(f1, 4),
        "slot_field_accuracy": round(slot_type_hits / slot_type_total, 4)
        if slot_type_total
        else 0.0,
        "per_scene_intent_accuracy": {
            scene: round(hit / count, 4) for scene, (hit, count) in sorted(per_scene.items())
        },
        "failure_reasons": dict(sorted(failures.items(), key=lambda kv: -kv[1])),
    }


def format_report(title: str, metrics: dict[str, Any]) -> str:
    lines = [f"# {title}", ""]
    lines.append(f"- 评测集规模：{metrics['total']} 条")
    lines.append(f"- **契约合法率：{metrics['contract_valid_rate']:.1%}**"
                 f"（{metrics['contract_valid']}/{metrics['total']}）")
    lines.append(f"- scene 准确率：{metrics['scene_accuracy']:.1%}")
    lines.append(f"- intent 准确率：{metrics['intent_accuracy']:.1%}")
    lines.append(f"- need_cloud 准确率：{metrics['need_cloud_accuracy']:.1%}")
    lines.append(f"- complexity MAE：{metrics['complexity_mae']}")
    lines.append(f"- 槽位 微平均 P/R/F1：{metrics['slot_precision']:.4f} / "
                 f"{metrics['slot_recall']:.4f} / **{metrics['slot_f1']:.4f}**")
    lines.append("")
    if metrics["per_scene_intent_accuracy"]:
        lines.append("分场景 (scene+intent) 全对率：")
        lines.append("")
        lines.append("| 场景 | 准确率 |")
        lines.append("| --- | --- |")
        for scene, value in metrics["per_scene_intent_accuracy"].items():
            lines.append(f"| {scene} | {value:.1%} |")
        lines.append("")
    if metrics["failure_reasons"]:
        lines.append("失败原因分布：")
        lines.append("")
        for reason, count in metrics["failure_reasons"].items():
            lines.append(f"- {reason}：{count}")
        lines.append("")
    return "\n".join(lines)


# ============================================================
# 预测来源
# ============================================================

def predict_with_teacher(
    gold: list[dict[str, Any]], which: str, workers: int = 6
) -> list[str | None]:
    """用某个教师在评测集上作答。"""
    from concurrent.futures import ThreadPoolExecutor

    from pipeline.contract import contract_prompt

    teacher_a, teacher_b, _ = build_teachers(SERVER_DIR / ".env")
    teacher = teacher_b if which == "teacher-b" else teacher_a

    def one(item: dict[str, Any]) -> str | None:
        prompt = contract_prompt(item["signals"], item["utterance"])
        try:
            return teacher.complete(prompt, temperature=0.0, max_tokens=400).text
        except TeacherError:
            return None

    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(one, gold))


def predict_from_file(path: Path, gold: list[dict[str, Any]]) -> list[str | None]:
    outputs: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        item = json.loads(line)
        outputs[str(item.get("id"))] = item.get("text") or item.get("output") or ""
    return [outputs.get(str(item["id"])) for item in gold]


# ============================================================
# CLI
# ============================================================

def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Mova-AI 端侧契约评测")
    parser.add_argument("--gold", type=Path, default=SERVER_DIR / "data" / "eval_gold.jsonl")
    parser.add_argument(
        "--predictor",
        choices=["teacher-a", "teacher-b", "file"],
        default="teacher-b",
        help="teacher-b 用于测标签噪声下限；file 用于评测自己的模型",
    )
    parser.add_argument("--predictions", type=Path, help="--predictor file 时的预测文件")
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--out", type=Path, default=SERVER_DIR / "data" / "eval_report.md")
    args = parser.parse_args(list(argv) if argv is not None else None)

    if not args.gold.exists():
        print(f"评测集不存在：{args.gold}\n请先运行 pipeline/build_dataset.py")
        return 2

    gold = [
        json.loads(line)
        for line in args.gold.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    print(f"[评测] 载入 {len(gold)} 条评测样本：{args.gold.name}")

    if args.predictor == "file":
        if not args.predictions or not args.predictions.exists():
            print("--predictor file 需要提供存在的 --predictions 文件")
            return 2
        predictions = predict_from_file(args.predictions, gold)
        title = f"端侧契约评测 · {args.predictions.name}"
    else:
        print(f"[评测] 使用 {args.predictor} 在评测集上作答（并发 {args.workers}）…")
        predictions = predict_with_teacher(gold, args.predictor, args.workers)
        if args.predictor == "teacher-b":
            title = "标签噪声下限（教师B 对 教师A 标注）"
        else:
            title = "自评（教师A 对 教师A 标注）"

    metrics = evaluate(predictions, gold)
    report = format_report(title, metrics)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(report, encoding="utf-8")
    (args.out.with_suffix(".json")).write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(report)
    print(f"\n[完成] 报告已写入 {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
