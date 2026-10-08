"""独立定向测试：生成审核表、记录复核来源、锁定 gold、评估预测。只使用标准库。"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "scene_ai_server"
sys.path.insert(0, str(SERVER))

from pipeline.contract import INTENTS_BY_SCENE, SLOTS_BY_SCENE, validate  # noqa: E402
from independent_cases import build_cases  # noqa: E402

TRAIN_MD5 = "d32b73adbc456212f42eaae2099d93ee"
PRIOR_GOLD_MD5 = "27a33ffbf5d2dd01d391533cb0443f3c"
REVIEW_FIELDS = (
    "id", "family", "signals", "utterance", "scene", "intent", "need_cloud",
    "slots_json", "checks", "审核", "备注",
)
NEGATIVE_FAMILIES = {
    "food_boundary", "location_other", "location_destination",
    "device_command", "file_command",
}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not all(isinstance(row, dict) for row in rows):
        raise ValueError(f"JSONL 顶层必须是对象：{path}")
    return rows


def md5(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def norm(text: str) -> str:
    """仅用于泄漏排查；统一全半角、大小写并去除标点空格。"""
    s = unicodedata.normalize("NFKC", text).lower()
    return "".join(c for c in s if c.isalnum())


def shingles(text: str) -> set[str]:
    s = norm(text)
    if len(s) < 3:
        return {s}
    return {s[i:i + 3] for i in range(len(s) - 2)}


def validate_labels(row: dict[str, Any]) -> None:
    scene = row.get("scene")
    intent = row.get("intent")
    if scene not in INTENTS_BY_SCENE or intent not in INTENTS_BY_SCENE[scene]:
        raise ValueError(f"{row['id']} scene/intent 不符合契约：{scene}/{intent}")
    slots = row.get("slots")
    if not isinstance(slots, dict):
        raise ValueError(f"{row['id']} slots_json 必须是 JSON 对象")
    specs = SLOTS_BY_SCENE[scene]
    for key, value in slots.items():
        if key not in specs:
            raise ValueError(f"{row['id']} 未定义槽位：{key}")
        kind, _required, allowed = specs[key]
        if kind == "enum" and value not in allowed:
            raise ValueError(f"{row['id']} 槽位 {key} 枚举值非法：{value!r}")
        if kind == "array[string]" and (
            not isinstance(value, list) or len(value) > 8 or not all(isinstance(x, str) for x in value)
        ):
            raise ValueError(f"{row['id']} 槽位 {key} 必须是最多 8 个字符串")
        if kind == "integer" and (not isinstance(value, int) or isinstance(value, bool)):
            raise ValueError(f"{row['id']} 槽位 {key} 必须是整数")
        if kind == "string" and not isinstance(value, str):
            raise ValueError(f"{row['id']} 槽位 {key} 必须是字符串")
    checks = row.get("checks")
    if not isinstance(checks, list) or not checks:
        raise ValueError(f"{row['id']} 缺少检查字段")
    for field in checks:
        if field not in ("scene", "intent", "need_cloud") and not field.startswith("slots."):
            raise ValueError(f"{row['id']} 未定义检查字段：{field}")
        if field.startswith("slots.") and field.removeprefix("slots.") not in slots:
            raise ValueError(f"{row['id']} 检查字段 {field} 没有复核标签")
        if field == "need_cloud" and not isinstance(row.get("need_cloud"), bool):
            raise ValueError(f"{row['id']} 检查 need_cloud 前须填写 true/false")


def draft(args: argparse.Namespace) -> None:
    if args.out.exists():
        raise FileExistsError(f"审核表已存在，避免覆盖已有修改：{args.out}")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=REVIEW_FIELDS, delimiter="\t")
        writer.writeheader()
        for item in build_cases():
            writer.writerow({
                "id": item["id"], "family": item["family"],
                "signals": item["signals"], "utterance": item["utterance"],
                "scene": item["scene"], "intent": item["intent"],
                "need_cloud": "" if item["need_cloud"] is None else str(item["need_cloud"]).lower(),
                "slots_json": json.dumps(item["slots"], ensure_ascii=False),
                "checks": ";".join(item["checks"]), "审核": "", "备注": "",
            })
    print(f"已写出 150 条待复核探针：{args.out}")
    print("逐条核对标签，必要时修改；最后在“审核”列填写“通过”或“修正”。")


def read_review(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if tuple(reader.fieldnames or ()) != REVIEW_FIELDS:
            raise ValueError("审核表列名或顺序变化；请保留原表头")
        raw = list(reader)
    if len(raw) != 150 or len({r["id"] for r in raw}) != 150:
        raise ValueError(f"审核表应为 150 条且 ID 唯一，实际 {len(raw)} 条")
    expected_families = {r["id"]: r["family"] for r in build_cases()}
    if {r["id"] for r in raw} != set(expected_families):
        raise ValueError("审核表 ID 与原探针草案不一致")
    rows = []
    for r in raw:
        if r["审核"].strip() not in ("通过", "修正"):
            raise ValueError(f"{r['id']} 尚未审核；“审核”列只能填“通过”或“修正”")
        if r["审核"].strip() == "修正" and not r["备注"].strip():
            raise ValueError(f"{r['id']} 修正后必须在“备注”列说明依据")
        cloud = r["need_cloud"].strip().lower()
        if cloud not in ("", "true", "false"):
            raise ValueError(f"{r['id']} need_cloud 只能留空或填 true/false")
        row = {
            "id": r["id"], "family": r["family"].strip(),
            "signals": r["signals"].strip(), "utterance": r["utterance"].strip(),
            "scene": r["scene"].strip(), "intent": r["intent"].strip(),
            "need_cloud": None if not cloud else cloud == "true",
            "slots": json.loads(r["slots_json"]),
            "checks": [x.strip() for x in r["checks"].split(";") if x.strip()],
            "review_note": r["备注"].strip(),
        }
        if not row["utterance"] or not row["signals"] or not row["family"]:
            raise ValueError(f"{r['id']} 家族、信号、话语不能留空")
        if row["family"] != expected_families[row["id"]]:
            raise ValueError(f"{r['id']} 问题族被改动；只应修正标签或话语")
        validate_labels(row)
        rows.append(row)
    if len({norm(r["utterance"]) for r in rows}) != len(rows):
        raise ValueError("审核表内部出现完全重复话语")
    return rows


def check_overlap(
    rows: list[dict[str, Any]], train: list[dict[str, Any]], prior: list[dict[str, Any]], report: Path
) -> tuple[int, int]:
    """精确重合禁止入集；近重复列出供复核。"""
    references = [("train", r) for r in train] + [("prior_gold", r) for r in prior]
    refs = [(origin, str(r.get("id", "")), str(r.get("utterance", ""))) for origin, r in references]
    exact = defaultdict(list)
    index: dict[str, set[int]] = defaultdict(set)
    grams = []
    for i, (origin, ref_id, utterance) in enumerate(refs):
        exact[norm(utterance)].append((origin, ref_id))
        g = shingles(utterance)
        grams.append(g)
        for token in g:
            index[token].add(i)
    matches = []
    exact_count = near_count = 0
    for row in rows:
        query = norm(row["utterance"])
        duplicate = exact.get(query, [])
        if duplicate:
            exact_count += 1
            for origin, ref_id in duplicate:
                matches.append((row["id"], "EXACT", "1.000", origin, ref_id))
            continue
        q = shingles(row["utterance"])
        candidates = Counter(i for token in q for i in index.get(token, ()))
        # 只精算共享三字片最多的候选，避免 150×6000 次字符串比较。
        ranked = candidates.most_common(80)
        if ranked:
            best_i, best_score = max(
                ((i, len(q & grams[i]) / len(q | grams[i])) for i, _ in ranked),
                key=lambda pair: pair[1],
            )
            if best_score >= 0.78:
                near_count += 1
                origin, ref_id, _ = refs[best_i]
                matches.append((row["id"], "NEAR", f"{best_score:.3f}", origin, ref_id))
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t")
        writer.writerow(("probe_id", "类型", "三字片Jaccard", "来源", "参考id"))
        writer.writerows(matches)
    return exact_count, near_count


def finalize(args: argparse.Namespace) -> None:
    if args.gold.exists():
        raise FileExistsError(f"gold 已存在；请另存新版本，勿覆盖已锁定测试集：{args.gold}")
    rows = read_review(args.review)
    if args.reviewer == "human" and any(r["review_note"].startswith("AI复核") for r in rows):
        raise ValueError("审核表记录为 AI 复核，不能用 --reviewer human 标记；如需人工金标准请重新逐条审核")
    if md5(args.train_file) != TRAIN_MD5 or md5(args.prior_gold) != PRIOR_GOLD_MD5:
        raise ValueError("正式 5500/500 数据 MD5 不匹配；不能拿仓库 60/40 pilot 做独立性检查")
    train, prior = read_jsonl(args.train_file), read_jsonl(args.prior_gold)
    if len(train) != 5500 or len(prior) != 500:
        raise ValueError("正式数据应为 5500 条训练与 500 条旧评测")
    ref_ids = {str(r.get("id")) for r in train + prior}
    if any(r["id"] in ref_ids for r in rows):
        raise ValueError("新探针 ID 与既有训练/评测集重复")
    exact, near = check_overlap(rows, train, prior, args.overlap_report)
    print(f"重复检查：完全重复 {exact} 条；近重复（Jaccard≥0.78）{near} 条。详见 {args.overlap_report}")
    if exact:
        raise ValueError("存在完全重复话语；请改写审核表后重新锁定")
    if near and not args.approve_near:
        raise ValueError("发现近重复；请检查报告，改写样本或确认后加 --approve-near 重跑")
    # 近重复只作为审阅线索；允许继续时在 manifest 中保留数量。
    gold = []
    for row in rows:
        expected = {"scene": row["scene"], "intent": row["intent"], "slots": row["slots"]}
        if row["need_cloud"] is not None:
            expected["need_cloud"] = row["need_cloud"]
        gold.append({
            "id": row["id"], "family": row["family"], "signals": row["signals"],
            "utterance": row["utterance"], "expected": expected,
            "checks": row["checks"], "reviewer": args.reviewer,
            "human_reviewed": args.reviewer == "human",
            "review_note": row["review_note"],
        })
    args.gold.parent.mkdir(parents=True, exist_ok=True)
    args.gold.write_text(
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in gold), encoding="utf-8"
    )
    manifest = {
        "gold_sha256": hashlib.sha256(args.gold.read_bytes()).hexdigest(),
        "train_md5": TRAIN_MD5, "prior_gold_md5": PRIOR_GOLD_MD5,
        "total": len(gold), "reviewer": args.reviewer,
        "signal_variants": len({r["signals"] for r in gold}),
        "need_cloud_labeled": sum("need_cloud" in r["expected"] for r in gold),
        "families": dict(sorted(Counter(r["family"] for r in gold).items())),
        "exact_overlap": exact, "near_overlap_to_review": near,
    }
    args.gold.with_suffix(".manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"已锁定 {args.reviewer} 复核 gold：{args.gold}；SHA-256 {manifest['gold_sha256']}")
    if near:
        print("请审阅近重复报告；如发现语义近复制，另建 gold 版本并排除相关样本。")


def load_predictions(path: Path, gold_ids: set[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for row in read_jsonl(path):
        key = str(row.get("id", ""))
        if not key or key in result:
            raise ValueError(f"预测 ID 缺失或重复：{key!r}")
        raw = row.get("text", row.get("output"))
        if not isinstance(raw, str):
            raise ValueError(f"{key} 预测必须包含 text 字符串")
        result[key] = raw
    if set(result) != gold_ids:
        missing, extra = gold_ids - set(result), set(result) - gold_ids
        raise ValueError(f"预测 ID 与 gold 不一致：缺失 {len(missing)} 条，额外 {len(extra)} 条")
    return result


def field_ok(field: str, actual: dict[str, Any], expected: dict[str, Any]) -> bool:
    if field.startswith("slots."):
        name = field.removeprefix("slots.")
        return (actual.get("slots") or {}).get(name) == (expected.get("slots") or {}).get(name)
    return actual.get(field) == expected.get(field)


def score(args: argparse.Namespace) -> None:
    gold = read_jsonl(args.gold)
    if len(gold) != 150 or any(
        r.get("reviewer") not in ("human", "ai")
        or r.get("human_reviewed") is not (r["reviewer"] == "human")
        or r["reviewer"] != gold[0]["reviewer"] for r in gold
    ):
        raise ValueError("只能评测带真实复核来源的 150 条 gold")
    manifest_path = args.gold.with_suffix(".manifest.json")
    if not manifest_path.is_file():
        raise ValueError(f"缺少锁定清单：{manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    gold_sha256 = hashlib.sha256(args.gold.read_bytes()).hexdigest()
    if manifest.get("gold_sha256") != gold_sha256 or manifest.get("reviewer") != gold[0]["reviewer"]:
        raise ValueError("gold 与锁定清单不一致，不能评分")
    if len({r["id"] for r in gold}) != len(gold):
        raise ValueError("gold ID 重复")
    predictions = load_predictions(args.predictions, {r["id"] for r in gold})
    families: dict[str, list[bool]] = defaultdict(list)
    correct = Counter()
    counts = Counter()
    errors = []
    for row in gold:
        expected = row["expected"]
        # 独立测试按原始输出严格解析；JSON 前后附加解释文字也算格式失败。
        try:
            actual = json.loads(predictions[row["id"]].strip())
        except json.JSONDecodeError:
            actual = None
        violations = validate(actual)
        valid = isinstance(actual, dict) and not violations
        correct["contract"] += valid
        if not valid:
            actual = actual if isinstance(actual, dict) else {}
        for field in ("scene", "intent", "need_cloud"):
            if field in expected:
                counts[field] += 1
                correct[field] += valid and field_ok(field, actual, expected)
        for name in expected.get("slots", {}):
            field = f"slots.{name}"
            counts["labeled_slots"] += 1
            correct["labeled_slots"] += valid and field_ok(field, actual, expected)
        checks = row["checks"]
        passed = valid and all(field_ok(field, actual, expected) for field in checks)
        families[row["family"]].append(passed)
        if not passed:
            wrong = [field for field in checks if not field_ok(field, actual, expected)]
            if violations:
                wrong.insert(0, "契约:" + "；".join(violations[:2]))
            errors.append((
                row["id"], row["family"], row["utterance"], ";".join(wrong),
                str(actual.get("scene", "")), str(actual.get("intent", "")),
                str(actual.get("need_cloud", "")),
                json.dumps(actual.get("slots", {}), ensure_ascii=False),
            ))
    report = {
        "gold_sha256": gold_sha256,
        "reviewer": gold[0]["reviewer"],
        "total": len(gold),
        "contract_valid": int(correct["contract"]),
        "contract_valid_rate": round(correct["contract"] / len(gold), 4),
        "field_accuracy_all_rows": {
            field: {"correct": int(correct[field]), "total": int(counts[field]),
                    "rate": round(correct[field] / counts[field], 4) if counts[field] else None}
            for field in ("scene", "intent", "need_cloud", "labeled_slots")
        },
        "families": {
            family: {
                "correct": sum(results), "total": len(results),
                "rate": round(sum(results) / len(results), 4),
                "target": 0.95 if family in NEGATIVE_FAMILIES else 0.90,
                "passed": sum(results) / len(results) >= (0.95 if family in NEGATIVE_FAMILIES else 0.90),
            }
            for family, results in sorted(families.items())
        },
        "failed_ids": len(errors),
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    lines = ["# 独立定向测试", "", f"- gold SHA-256：`{report['gold_sha256']}`",
             f"- 标签复核来源：`{report['reviewer']}`",
             f"- 契约合法率：{report['contract_valid']}/{len(gold)}（{report['contract_valid_rate']:.1%}）",
             "- 所有准确率以完整测试集为分母；无效输出计错，不沿用旧评测的‘仅合法输出’分母。", ""]
    for field, stats in report["field_accuracy_all_rows"].items():
        if stats["total"]:
            lines.append(f"- {field}：{stats['correct']}/{stats['total']}（{stats['rate']:.1%}）")
    lines += ["", "| 问题族 | 正确/总数 | 通过率 | 目标 | 达标 |", "|---|---:|---:|---:|---|"]
    for family, stats in report["families"].items():
        lines.append(
            f"| {family} | {stats['correct']}/{stats['total']} | {stats['rate']:.1%} | "
            f"{stats['target']:.0%} | {'是' if stats['passed'] else '否'} |"
        )
    (args.out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    with (args.out_dir / "errors.tsv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t")
        writer.writerow(("id", "family", "utterance", "失败字段", "预测scene", "预测intent", "预测need_cloud", "预测slots"))
        writer.writerows(errors)
    print(f"评测完成：{args.out_dir}；契约 {report['contract_valid']}/150，问题族未通过 {sum(not r['passed'] for r in report['families'].values())} 个")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    p = subs.add_parser("draft", help="生成待审核 TSV，不生成 gold")
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(func=draft)
    p = subs.add_parser("finalize", help="复核后查重并锁定 gold")
    p.add_argument("--review", type=Path, required=True)
    p.add_argument("--train-file", type=Path, required=True, help="正式 5500 条训练源，不接受 pilot")
    p.add_argument("--prior-gold", type=Path, required=True, help="正式 500 条旧评测集，不接受 pilot")
    p.add_argument("--gold", type=Path, required=True)
    p.add_argument("--overlap-report", type=Path, required=True)
    p.add_argument("--reviewer", choices=("human", "ai"), required=True, help="如实记录复核来源")
    p.add_argument("--approve-near", action="store_true", help="检查近重复报告后才可使用")
    p.set_defaults(func=finalize)
    p = subs.add_parser("score", help="按已锁定 gold 对预测做严格分族评测")
    p.add_argument("--gold", type=Path, required=True)
    p.add_argument("--predictions", type=Path, required=True)
    p.add_argument("--out-dir", type=Path, required=True)
    p.set_defaults(func=score)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
