"""第二轮定向补数：核对正式数据、排除评测泄漏、生成新的训练源。

新增样本为规则明确的 AI 编写样本，不冒充教师标注或人工金标准。
脚本只创建新文件，不覆盖第一轮训练集或已经锁定的评测集。
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scene_ai_server"))
from pipeline.contract import validate  # noqa: E402
from independent_eval import norm, shingles  # noqa: E402

TRAIN_MD5 = "d32b73adbc456212f42eaae2099d93ee"
PRIOR_GOLD_MD5 = "27a33ffbf5d2dd01d391533cb0443f3c"
LOCKED_GOLD_SHA256 = "b0d292fd189421d6e762b979540481ca5fb333d4ad585fe335682fa902324fa4"

SIGNALS = (
    "时间：08:16 | 前台 App：地图 | 位置：街边 | 运动：步行 | 屏幕：亮",
    "时间：12:42 | 前台 App：消息 | 位置：家中 | 运动：静止 | 屏幕：亮",
    "时间：16:23 | 前台 App：日历 | 位置：办公楼 | 运动：静止 | 屏幕：亮",
    "时间：19:05 | 前台 App：桌面 | 位置：小区 | 运动：步行 | 屏幕：亮",
)

# 此表只描述当前位置；目的地永远不能覆盖 place_type。
PLACES = (
    ("烧烤店", "餐厅"), ("拉面馆", "餐厅"), ("包子铺", "餐厅"),
    ("披萨店", "餐厅"), ("小吃店", "餐厅"),
    ("高铁检票口", "交通枢纽"), ("客运站候车区", "交通枢纽"),
    ("地铁换乘站", "交通枢纽"), ("机场安检口", "交通枢纽"),
    ("公交总站", "交通枢纽"),
    ("校内实验楼", "校园"), ("研究生宿舍楼", "校园"),
    ("学校体育馆", "校园"), ("校园图书馆", "校园"),
    ("校园教学区", "校园"),
    ("社区生鲜超市", "超市"), ("仓储式超市", "超市"),
    ("连锁超市", "超市"), ("小区超市", "超市"),
    ("百货大楼", "商场"), ("购物广场", "商场"),
    ("购物商厦", "商场"), ("商业中心", "商场"),
    ("自助洗衣房", "其他"), ("社区卫生站", "其他"),
    ("照相馆", "其他"), ("公园广场", "其他"),
)
FOODS = (
    "猕猴桃", "葡萄", "桃子", "牛奶", "鸡蛋", "生菜", "黄瓜", "豆腐",
    "酸奶", "草莓", "苹果", "蘑菇", "西兰花", "面包", "米饭", "橘子",
)
PAIRS = (
    ("鸡胸肉", "西兰花"), ("燕麦", "牛奶"), ("豆腐", "菠菜"),
    ("鸡蛋", "番茄"), ("虾仁", "冬瓜"), ("牛肉", "芹菜"),
    ("山药", "木耳"), ("南瓜", "小米"), ("蘑菇", "白菜"),
    ("三文鱼", "芦笋"), ("红薯", "酸奶"), ("豆干", "青椒"),
)


def digest(path: Path, algorithm: str) -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_jsonl(path: Path) -> list[dict]:
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows or not all(isinstance(row, dict) for row in rows):
        raise ValueError(f"文件为空或 JSONL 行不是对象：{path}")
    return rows


def make_cases() -> list[dict]:
    """生成与第一轮探针不同的话语；每条都有可复查的规则标签。"""
    rows: list[dict] = []

    def add(family: str, utterance: str, scene: str, intent: str,
            need_cloud: bool, slots: dict, reason: str) -> None:
        target = {
            "scene": scene, "intent": intent, "complexity": 0.7 if need_cloud else 0.25,
            "confidence": 0.9, "need_cloud": need_cloud, "slots": slots, "reason": reason,
        }
        errors = validate(target)
        if errors:
            raise ValueError(f"新增标签违反契约：{utterance} {errors}")
        rows.append({
            "id": f"round2_{len(rows) + 1:04d}", "family": family,
            "signals": SIGNALS[len(rows) % len(SIGNALS)],
            "utterance": utterance, "target": target,
            "label_provenance": "ai_rule_draft",
        })

    for place, place_type in PLACES:
        slots = {"place_type": place_type}
        add("location_live", f"我现在在{place}，帮我查一下附近此刻哪家药店还开门。",
            "location", "nearby_hint", True, slots, "需查询附近药店实时营业信息，地点取当前位置。")
        add("location_live", f"我在{place}，周边哪家打印店这会儿仍在营业？",
            "location", "nearby_hint", True, slots, "需查询附近打印店实时营业信息，地点取当前位置。")
        add("location_timer", f"刚到{place}，二十分钟后提醒我给家里打电话。",
            "location", "timing_reminder", False, slots, "本地定时提醒，地点取当前位置。")
        add("location_destination", f"我人在{place}，去火车站前提醒我带上证件。",
            "location", "timing_reminder", False, slots, "火车站是目的地，当前位置决定地点类型。")

    for food in FOODS:
        add("food_boundary", f"买{food}时，怎么判断它有没有变质？",
            "none", "none", True, {}, "询问食材品质而非烹饪，需要外部知识。")
        add("food_boundary", f"这份{food}拆封后应该怎么保存？",
            "none", "none", True, {}, "询问储存方法而非烹饪，需要外部知识。")
        add("food_price", f"今天{food}的市场价格比上周高吗？",
            "none", "none", True, {}, "实时价格查询不属于食物烹饪场景。")

    for first, second in PAIRS:
        slots = {"ingredients": [first, second], "meal": "晚饭"}
        add("food_diet", f"想控制晚饭的热量，只有{first}和{second}，怎么搭配更稳妥？",
            "food", "diet_advice", True, slots, "诉求是控制热量，属于饮食管理。")
        add("food_diet", f"晚饭想少摄入糖分，{first}配{second}合适吗？",
            "food", "diet_advice", True, slots, "诉求是饮食控糖，属于饮食管理。")
        add("food_diet", f"我在减脂，晚饭用{first}和{second}怎样搭配比较好？",
            "food", "diet_advice", True, slots, "诉求是减脂餐搭配，属于饮食管理。")
        add("food_recipe", f"今晚用{first}和{second}做一道家常菜，步骤怎么安排？",
            "food", "recipe_lookup", False, slots, "诉求是烹饪步骤，属于食谱查询。")

    if len({norm(row["utterance"]) for row in rows}) != len(rows):
        raise ValueError("新增样本内部出现重复话语")
    return rows


def overlap(rows: list[dict], references: list[tuple[str, dict]]) -> tuple[list[dict], list[tuple]]:
    """按与独立测试相同的正规化和三字片阈值过滤泄漏。"""
    indexed: dict[str, set[int]] = defaultdict(set)
    ref_grams: list[set[str]] = []
    exact: dict[str, tuple[str, str]] = {}
    for i, (origin, ref) in enumerate(references):
        utterance = str(ref.get("utterance", ""))
        if not utterance:
            raise ValueError(f"{origin} 缺少 utterance：{ref.get('id')}")
        exact[norm(utterance)] = (origin, str(ref.get("id", "")))
        grams = shingles(utterance)
        ref_grams.append(grams)
        for gram in grams:
            indexed[gram].add(i)

    kept, excluded = [], []
    for row in rows:
        phrase = norm(row["utterance"])
        if phrase in exact:
            origin, ref_id = exact[phrase]
            excluded.append((row["id"], "EXACT", "1.000", origin, ref_id))
            continue
        q = shingles(row["utterance"])
        candidates = set().union(*(indexed.get(gram, set()) for gram in q))
        best_id, best_score = None, 0.0
        for i in candidates:
            # 先用集合长度上界排除不可能达到 0.78 的候选。
            bound = min(len(q), len(ref_grams[i])) / max(len(q), len(ref_grams[i]))
            if bound < 0.78:
                continue
            score = len(q & ref_grams[i]) / len(q | ref_grams[i])
            if score > best_score:
                best_id, best_score = i, score
        if best_score >= 0.78:
            origin, ref = references[best_id]
            excluded.append((row["id"], "NEAR", f"{best_score:.3f}", origin, ref.get("id", "")))
        else:
            kept.append(row)
    return kept, excluded


def audit_source(train: list[dict]) -> list[tuple[str, str, str, str]]:
    """只列出明确关键词与教师标签的疑似冲突，不自动改写原标签。

    这里采用保守启发式；人工复核时需结合完整 utterance 和 signals。
    """
    findings = []
    for row in train:
        phrase = str(row.get("utterance", ""))
        target = row["target"]
        sample_id = str(row["id"])
        if (target["scene"] == "location" and target["intent"] == "nearby_hint"
                and re.search(r"(?:现在|此刻|目前).{0,12}(?:营业|开门|开放)", phrase)
                and not target["need_cloud"]):
            findings.append((sample_id, "实时营业", "need_cloud=true", "need_cloud=false"))
        if (re.search(r"控糖|控制血糖|减脂|控制热量", phrase)
                and target["scene"] == "food" and target["intent"] != "diet_advice"):
            findings.append((sample_id, "饮食管理", "food/diet_advice", target["intent"]))
        if (re.search(r"新鲜|变质|怎么保存|如何保存|价格", phrase)
                and not re.search(r"怎么做|做饭|搭配|菜谱", phrase)
                and target["scene"] == "food"):
            findings.append((sample_id, "食材品质或价格", "none/none", f"food/{target['intent']}"))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--train-file", type=Path, required=True, help="服务器正式 5500 条训练源")
    parser.add_argument("--prior-gold", type=Path, required=True, help="服务器正式 500 条评测集")
    parser.add_argument("--locked-gold", type=Path, required=True, help="锁定的 150 条独立测试集")
    parser.add_argument("--out-dir", type=Path, required=True, help="本轮新目录；必须不存在")
    args = parser.parse_args()
    if args.out_dir.exists():
        parser.error(f"输出目录已存在，拒绝覆盖：{args.out_dir}")

    checks = (
        (args.train_file, "md5", TRAIN_MD5),
        (args.prior_gold, "md5", PRIOR_GOLD_MD5),
        (args.locked_gold, "sha256", LOCKED_GOLD_SHA256),
    )
    for path, algorithm, expected in checks:
        if not path.is_file() or digest(path, algorithm) != expected:
            parser.error(f"正式数据文件缺失或 {algorithm} 不匹配：{path}")

    train = read_jsonl(args.train_file)
    prior = read_jsonl(args.prior_gold)
    locked = read_jsonl(args.locked_gold)
    if (len(train), len(prior), len(locked)) != (5500, 500, 150):
        parser.error("正式数据行数应为训练 5500、旧评测 500、独立测试 150")
    ids = [str(row.get("id", "")) for row in train]
    if not all(ids) or len(set(ids)) != len(ids):
        parser.error("正式训练源缺少 ID 或存在重复 ID")
    for row in train:
        errors = validate(row.get("target"))
        if errors:
            parser.error(f"正式训练源标签不符合契约：{row.get('id')} {errors}")

    refs = ([('train', row) for row in train] +
            [('prior_gold', row) for row in prior] +
            [('locked_gold', row) for row in locked])
    source_findings = audit_source(train)
    candidates = make_cases()
    kept, excluded = overlap(candidates, refs)
    if len(kept) < 100:
        parser.error(f"只有 {len(kept)} 条新增样本通过查重，低于 100 条；先修改样本模板")

    args.out_dir.mkdir(parents=True)
    new_path = args.out_dir / "new_train.jsonl"
    mixed_path = args.out_dir / "mixed_train.jsonl"
    with new_path.open("w", encoding="utf-8") as handle:
        for row in kept:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    with mixed_path.open("w", encoding="utf-8") as handle:
        # 原始 5500 条保留原标签；新增只占一小部分，不偷偷重标训练源。
        for row in [*train, *kept]:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    with (args.out_dir / "excluded.tsv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t")
        writer.writerow(("candidate_id", "type", "jaccard", "origin", "reference_id"))
        writer.writerows(excluded)
    with (args.out_dir / "source_audit.tsv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t")
        writer.writerow(("train_id", "疑似问题", "规则预期", "教师标签"))
        writer.writerows(source_findings)

    summary = {
        "source_train_md5": TRAIN_MD5,
        "prior_gold_md5": PRIOR_GOLD_MD5,
        "locked_gold_sha256": LOCKED_GOLD_SHA256,
        "candidate_count": len(candidates),
        "new_count": len(kept),
        "excluded_count": len(excluded),
        "source_audit_findings": len(source_findings),
        "new_families": dict(sorted(Counter(row["family"] for row in kept).items())),
        "train_scene_intent": {f"{scene}/{intent}": count for (scene, intent), count in
                               sorted(Counter((row["target"]["scene"], row["target"]["intent"])
                                              for row in train).items())},
        "new_labels": "ai_rule_draft; not teacher or human reviewed",
        "new_train_md5": digest(new_path, "md5"),
        "mixed_train_md5": digest(mixed_path, "md5"),
    }
    (args.out_dir / "manifest.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"正式训练 {len(train)} 条；候选 {len(candidates)} 条；查重排除 {len(excluded)} 条")
    print(f"新增 {len(kept)} 条；混合训练 {len(train) + len(kept)} 条")
    print(f"mixed_train md5={summary['mixed_train_md5']}")
    print(f"原始教师标签疑似冲突 {len(source_findings)} 条；只报告，不自动改写")
    print(f"训练前复核新增标签、查重明细、源标签疑点：{args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
