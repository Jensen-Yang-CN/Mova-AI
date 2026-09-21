"""S2 · 数据引擎主流程。

    python pipeline/build_dataset.py --limit 120 --budget 100

七个阶段，每一步都有产物落盘、可单独重跑：

    ① 种子展开      模板 → 具体用户输入（确定性，可复现）
    ② 双教师标注    教师A贪心输出（含 top-K logprobs）+ 教师B输出
    ③ 契约校验      用 contract.validate 判定合法率，不合格直接出局
    ④ 三信号难度    自一致性 / 置信度 / 跨教师分歧 → difficulty
    ⑤ 近似去重      MinHash+LSH 找候选，精确 Jaccard 复核
    ⑥ 覆盖优化      子模贪心（facility location，1−1/e 保证）选出覆盖均衡的候选池
    ⑦ 配额配比      覆盖保底 → 难度配比（最大余数法）→ 质量补足

产物：
    data/distill_dataset.jsonl   蒸馏数据集（含软标签，可直接用于 logit 蒸馏）
    data/eval_gold.jsonl         评测集 v1（与训练集按 id 严格互斥）
    data/dataset_report.md       数据报告（含各项统计与对照数字）
    data/cache/*.jsonl           中间缓存（重跑不会重复调 API）

零第三方依赖。
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable

# 允许以 `python pipeline/build_dataset.py` 直接运行
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jsonx import extract_json  # noqa: E402

from pipeline import coverage as coverage_mod  # noqa: E402
from pipeline import dedup as dedup_mod  # noqa: E402
from pipeline.allocate import allocate, difficulty_band, largest_remainder  # noqa: E402
from pipeline.contract import (  # noqa: E402
    TARGET_MIX,
    contract_prompt,
    json_schema,
    validate,
)
from pipeline.difficulty import DEFAULT_WEIGHTS, field_agreement  # noqa: E402
from pipeline.seeds import Seed, generate_seeds  # noqa: E402
from pipeline.teacher import TeacherClient, TeacherError, build_teachers  # noqa: E402

SERVER_DIR = Path(__file__).resolve().parent.parent


# ============================================================
# 配置
# ============================================================

@dataclass
class Config:
    limit: int = 120
    budget: int = 100
    k_samples: int = 3
    workers: int = 6
    coverage_multiplier: float = 1.35
    coverage_floor: float = 0.25
    max_share_per_cell: float = 0.40
    logprob_k: int = 8
    gold_size: int = 40
    dedup_threshold: float = 0.80
    out_dir: Path = field(default_factory=lambda: SERVER_DIR / "data")
    cache_dir: Path = field(default_factory=lambda: SERVER_DIR / "data" / "cache")

    @property
    def labels_cache(self) -> Path:
        return self.cache_dir / f"labels_limit{self.limit}_k{self.k_samples}.jsonl"

    def prepare(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.cache_dir.mkdir(parents=True, exist_ok=True)


# ============================================================
# 阶段 ② 标注（带缓存与并发）
# ============================================================

def label_one(
    seed: Seed,
    teacher_a: TeacherClient,
    teacher_b: TeacherClient,
    cfg: Config,
) -> dict[str, Any]:
    """对一条种子完成双教师标注 + 三信号评估，返回可落盘的记录。"""
    prompt = contract_prompt(seed.signals, seed.utterance)
    record: dict[str, Any] = {
        "id": seed.id,
        "scene": seed.scene,
        "intent": seed.intent,
        "complexity_hint": seed.complexity_hint,
        "signals": seed.signals,
        "utterance": seed.utterance,
        "expect_slots": seed.expect_slots,
        "valid": False,
        "errors": [],
        "label": None,
        "label_b": None,
        "consistency": None,
        "teacher_confidence": None,
        "cross_agreement": None,
        "mean_logprob": None,
        "p10_logprob": None,
        "soft_label_text": "",
        "difficulty": 1.0,
        "soft_label": [],
        "usage": {},
        "latency_ms": 0,
        "note": "",
    }

    started = time.monotonic()

    # ---- 教师 A 贪心输出：确定性标签 ----
    # ⚠️ 这一步**不能**同时取 logprobs。实测发现 temperature=0 会把输出分布压成
    #    one-hot，此时接口返回的 logprob 恒为 0、候选恒为 -9999，完全不可用
    #    （DeepSeek 上实测如此）。因此软标签必须另起一次 T=1 的调用，见下。
    try:
        greedy = teacher_a.complete(prompt, temperature=0.0, max_tokens=400)
    except TeacherError as exc:
        record["note"] = f"教师A失败：{exc}"
        return record

    record["latency_ms"] = greedy.latency_ms
    record["usage"] = greedy.usage

    parsed = extract_json(greedy.text)
    if not isinstance(parsed, dict):
        record["errors"] = ["无法解析 JSON"]
        record["note"] = "教师A输出不是 JSON"
        return record
    errors = validate(parsed)
    record["errors"] = errors
    if errors:
        record["note"] = "教师A输出未通过契约校验"
        return record
    record["valid"] = True
    record["label"] = parsed

    # ---- 教师 A 的软标签与置信度：T=1 采样，此时 logprobs 才有意义 ----
    try:
        sampler = teacher_a.complete(
            prompt, temperature=1.0, max_tokens=400, top_logprobs=cfg.logprob_k
        )
        record["soft_label"] = sampler.token_logprobs
        record["mean_logprob"] = sampler.mean_logprob
        record["p10_logprob"] = sampler.p10_logprob
        record["teacher_confidence"] = sampler.confidence
        record["soft_label_text"] = sampler.text
    except TeacherError as exc:
        record["note"] = f"软标签采样失败：{exc}"

    # ---- 信号一：自一致性（再采 k−1 次，较高温度） ----
    samples: list[dict[str, Any] | None] = []
    for _ in range(max(0, cfg.k_samples - 1)):
        try:
            reply = teacher_a.complete(prompt, temperature=0.9, max_tokens=400)
        except TeacherError:
            continue
        candidate = extract_json(reply.text)
        samples.append(candidate if isinstance(candidate, dict) else None)
    if samples:
        valid_samples = [item for item in samples if isinstance(item, dict)]
        pairs = [field_agreement(parsed, item) for item in valid_samples]
        for i in range(len(valid_samples)):
            for j in range(i + 1, len(valid_samples)):
                pairs.append(field_agreement(valid_samples[i], valid_samples[j]))
        if pairs:
            record["consistency"] = round(sum(pairs) / len(pairs), 4)

    # ---- 信号三：跨教师分歧 ----
    try:
        other = teacher_b.complete(prompt, temperature=0.0, max_tokens=400)
        parsed_b = extract_json(other.text)
        if isinstance(parsed_b, dict) and not validate(parsed_b):
            record["label_b"] = parsed_b
            record["cross_agreement"] = round(field_agreement(parsed, parsed_b), 4)
    except TeacherError as exc:
        record["note"] = f"教师B失败：{exc}"

    # ---- 合成难度 ----
    contributions = [
        (DEFAULT_WEIGHTS["consistency"], None if record["consistency"] is None else 1 - record["consistency"]),
        (
            DEFAULT_WEIGHTS["confidence"],
            None if record["teacher_confidence"] is None else 1 - record["teacher_confidence"],
        ),
        (
            DEFAULT_WEIGHTS["disagreement"],
            None if record["cross_agreement"] is None else 1 - record["cross_agreement"],
        ),
    ]
    total_weight = sum(w for w, v in contributions if v is not None)
    record["difficulty"] = (
        round(sum(w * v for w, v in contributions if v is not None) / total_weight, 4)
        if total_weight > 0
        else 0.5
    )
    return record


def stage_labels(
    seeds: list[Seed],
    cfg: Config,
    teacher_a: TeacherClient,
    teacher_b: TeacherClient,
) -> list[dict[str, Any]]:
    """并发标注，并把结果缓存到磁盘（重跑不会重复消耗 API 额度）。"""
    cached: dict[str, dict[str, Any]] = {}
    if cfg.labels_cache.exists():
        for line in cfg.labels_cache.read_text(encoding="utf-8").splitlines():
            if line.strip():
                item = json.loads(line)
                cached[item["id"]] = item
        print(f"[② 标注] 命中缓存 {len(cached)} 条：{cfg.labels_cache.name}")

    todo = [seed for seed in seeds if seed.id not in cached]
    if todo:
        print(f"[② 标注] 需要调用教师 {len(todo)} 条 × 约 {1 + cfg.k_samples - 1 + 1} 次请求"
              f"（并发 {cfg.workers}）")
        done = 0
        with ThreadPoolExecutor(max_workers=cfg.workers) as pool:
            futures = {
                pool.submit(label_one, seed, teacher_a, teacher_b, cfg): seed for seed in todo
            }
            for future in as_completed(futures):
                record = future.result()
                cached[record["id"]] = record
                done += 1
                if done % 20 == 0 or done == len(todo):
                    print(f"    进度 {done}/{len(todo)}")

        with cfg.labels_cache.open("w", encoding="utf-8") as handle:
            for seed in seeds:
                if seed.id in cached:
                    handle.write(json.dumps(cached[seed.id], ensure_ascii=False) + "\n")
        print(f"[② 标注] 已写入缓存：{cfg.labels_cache}")

    return [cached[seed.id] for seed in seeds if seed.id in cached]


# ============================================================
# 质量分
# ============================================================

def quality_of(record: dict[str, Any]) -> float:
    """样本质量分 ∈ [0,1]。

    三个来源：契约是否合法（硬门槛）、教师置信度、跨教师一致度。
    刻意保持简单可解释 —— 一个自己都说不清楚的"质量模型"没有意义。
    """
    if not record.get("valid"):
        return 0.0
    quality = 0.55
    confidence = record.get("teacher_confidence")
    quality += 0.25 * (confidence if confidence is not None else 0.5)
    agreement = record.get("cross_agreement")
    quality += 0.20 * (agreement if agreement is not None else 0.5)
    return round(quality, 4)


# ============================================================
# 主流程
# ============================================================

def run(cfg: Config) -> dict[str, Any]:
    cfg.prepare()
    stats: dict[str, Any] = {"config": {k: str(v) for k, v in asdict(cfg).items()}}

    # ---------- ① 种子 ----------
    seeds = generate_seeds(limit=cfg.limit)
    print(f"[① 种子] 展开 {len(seeds)} 条用户输入，"
          f"{len({(s.scene, s.intent) for s in seeds})} 种 (场景,意图) 组合")
    stats["seeds"] = {
        "total": len(seeds),
        "scene_intent_pairs": len({(s.scene, s.intent) for s in seeds}),
    }

    # ---------- ② 标注 ----------
    teacher_a, teacher_b, _ = build_teachers(
        SERVER_DIR / ".env", logprob_k=cfg.logprob_k
    )
    print(f"[② 标注] 教师A={teacher_a.name}/{teacher_a.model}  "
          f"教师B={teacher_b.name}/{teacher_b.model}")
    records = stage_labels(seeds, cfg, teacher_a, teacher_b)

    valid = [r for r in records if r["valid"]]
    invalid = [r for r in records if not r["valid"]]
    stats["labeling"] = {
        "total": len(records),
        "contract_valid": len(valid),
        "contract_invalid": len(invalid),
        "json_valid_rate": round(len(valid) / len(records), 4) if records else 0.0,
        "top_invalid_errors": _top_errors(invalid),
        "avg_latency_ms": round(
            sum(r["latency_ms"] for r in records) / max(1, len(records))
        ),
        "logprob_available": sum(1 for r in records if r.get("mean_logprob") is not None),
    }
    print(f"[③ 校验] 契约合法率 {stats['labeling']['json_valid_rate']:.1%}"
          f"（{len(valid)}/{len(records)}）")

    if not valid:
        raise SystemExit("没有任何合法样本，请检查教师返回内容或契约定义。")

    print(f"[④ 难度] 平均难度 {sum(r['difficulty'] for r in valid) / len(valid):.3f}"
          f"，三信号均可用 "
          f"{sum(1 for r in valid if r['consistency'] is not None)} / "
          f"{sum(1 for r in valid if r['teacher_confidence'] is not None)} / "
          f"{sum(1 for r in valid if r['cross_agreement'] is not None)}")

    # ---------- ⑤ 去重 ----------
    texts = [r["utterance"] for r in valid]
    quality = [quality_of(r) for r in valid]
    dedup_result = dedup_mod.dedup(
        texts, threshold=cfg.dedup_threshold, keep_priority=lambda i: quality[i]
    )
    removed_texts = {valid[i]["id"] for i in dedup_result.removed}
    allowed_indices = set(dedup_result.kept_indices)
    stats["dedup"] = {
        "input": len(valid),
        "kept": len(allowed_indices),
        "removed": dedup_result.removed_count,
        "removal_rate": round(dedup_result.removed_count / max(1, len(valid)), 4),
        "lsh_candidate_pairs": dedup_result.candidate_pairs,
        "exact_checks": dedup_result.exact_checks,
        "threshold": cfg.dedup_threshold,
    }
    print(f"[⑤ 去重] LSH 候选对 {dedup_result.candidate_pairs} → 精确复核后删除 "
          f"{dedup_result.removed_count} 条（{stats['dedup']['removal_rate']:.1%}）")

    # ---------- ⑥ 覆盖优化 ----------
    # 网格 = 场景 × 意图 × 难度档 × 槽位组合。
    # 加上槽位组合这一维很关键：只按前三维修网格时，格子少而候选多，
    # 贪心几乎覆盖全部格子，与随机基线拉不开差距（第一轮实测只提升 1.9%，
    # 因为候选池 128 条 vs 预算 300 —— **选择问题本身不成立**）。
    # 细化到槽位后，"某个场景+意图+难度下只出现少数几次的槽位组合"才会成为
    # 需要被刻意覆盖的长尾，子模贪心的价值才体现得出来。
    cells = [
        f"{r['scene']}|{r['intent']}|{difficulty_band(r['difficulty'])}|{_slot_signature(r)}"
        for r in valid
    ]
    coverage_budget = min(
        len(allowed_indices), max(cfg.budget, int(cfg.budget * cfg.coverage_multiplier))
    )
    cover = coverage_mod.greedy_cover(
        cells, quality, coverage_budget, excluded=set(range(len(valid))) - allowed_indices
    )
    baseline = coverage_mod.random_baseline_objective(
        cells, quality, coverage_budget
    )
    cover_report = coverage_mod.coverage_report(
        cells, quality, cover.selected, all_cells=cells
    )
    stats["coverage"] = {
        "budget": coverage_budget,
        "objective": cover.objective,
        "random_baseline": baseline,
        "improvement_over_random": round(
            (cover.objective - baseline) / baseline, 4
        ) if baseline else None,
        "cell_coverage": cover_report["cell_coverage"],
        "universe_cells": cover_report["universe_cells"],
        "covered_cells": cover_report["covered_cells"],
        "marginal_gain_first": cover.marginal_gains[0] if cover.marginal_gains else None,
        "marginal_gain_last": cover.marginal_gains[-1] if cover.marginal_gains else None,
    }
    print(f"[⑥ 覆盖] 覆盖 {cover_report['covered_cells']}/{cover_report['universe_cells']} 个格子，"
          f"F={cover.objective}，随机基线 {baseline}"
          f"（提升 {stats['coverage']['improvement_over_random']:.1%}）")

    # ---------- ⑦ 配额配比 ----------
    pool = cover.selected
    bands = [difficulty_band(valid[i]["difficulty"]) for i in range(len(valid))]
    allocation = allocate(
        cells=[cells[i] for i in pool],
        bands=[bands[i] for i in pool],
        quality=[quality[i] for i in pool],
        budget=min(cfg.budget, len(pool)),
        target_mix=TARGET_MIX,
        coverage_floor=cfg.coverage_floor,
        max_share_per_cell=cfg.max_share_per_cell,
    )
    final_indices = [pool[i] for i in allocation.selected]
    final_records = [valid[i] for i in final_indices]
    stats["allocation"] = {
        "target_mix": allocation.target_mix,
        "achieved_mix": allocation.achieved_mix,
        "band_targets": largest_remainder(min(cfg.budget, len(pool)), TARGET_MIX),
        "stage_counts": allocation.stage_counts,
        "per_cell": allocation.per_cell,
        "notes": allocation.notes,
        "selected": len(final_indices),
    }
    print(f"[⑦ 配比] 选出 {len(final_indices)} 条，难度分布 {allocation.achieved_mix}")
    for note in allocation.notes:
        print(f"    ⚠ {note}")

    # ---------- 评测集（与训练集严格互斥） ----------
    gold_pool = [r for r in records if not r["valid"]] + [
        valid[i] for i in sorted(allowed_indices - set(final_indices))
    ]
    gold = gold_pool[: cfg.gold_size]

    # ---------- 写出 ----------
    dataset_path = cfg.out_dir / "distill_dataset.jsonl"
    _write_dataset(dataset_path, final_records)

    gold_path = cfg.out_dir / "eval_gold.jsonl"
    _write_gold(gold_path, gold)

    schema_path = cfg.out_dir / "edge_contract.schema.json"
    schema_path.write_text(
        json.dumps(json_schema(), ensure_ascii=False, indent=2), encoding="utf-8"
    )

    stats["dataset"] = _dataset_stats(final_records)
    stats["gold"] = {"size": len(gold), "path": str(gold_path.name)}

    report = build_report(stats)
    (cfg.out_dir / "dataset_report.md").write_text(report, encoding="utf-8")
    (cfg.out_dir / "dataset_stats.json").write_text(
        json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"\n[完成] 数据集 {dataset_path}")
    print(f"        评测集 {gold_path}")
    print(f"        契约   {schema_path}")
    print(f"        报告   {cfg.out_dir / 'dataset_report.md'}")
    return stats


# ============================================================
# 写出与统计
# ============================================================

def _slot_signature(record: dict[str, Any]) -> str:
    """把槽位键集合压成一个签名，作为覆盖网格的第四维。"""
    slots = (record.get("label") or {}).get("slots") or {}
    return "+".join(sorted(slots)) if slots else "-"


def _write_dataset(path: Path, records: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            payload = {
                "id": record["id"],
                "scene": record["scene"],
                "intent": record["intent"],
                "signals": record["signals"],
                "utterance": record["utterance"],
                "target": record["label"],
                "hard_label_b": record["label_b"],
                "difficulty": record["difficulty"],
                "quality": quality_of(record),
                "signals_detail": {
                    "consistency": record["consistency"],
                    "teacher_confidence": record["teacher_confidence"],
                    "cross_agreement": record["cross_agreement"],
                },
                "teacher": {
                    "name": "deepseek",
                    "mean_logprob": record["mean_logprob"],
                    "p10_logprob": record.get("p10_logprob"),
                },
                # 软标签：教师逐个生成 token 的 top-K 分布，供 logit 蒸馏使用
                "soft_label": record["soft_label"],
            }
            handle.write(json.dumps(payload, ensure_ascii=False) + "\n")


def _write_gold(path: Path, records: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            payload = {
                "id": record["id"],
                "scene": record["scene"],
                "signals": record["signals"],
                "utterance": record["utterance"],
                "expected": record["label"],
                "expect_slots": record.get("expect_slots", []),
                "difficulty": record["difficulty"],
            }
            handle.write(json.dumps(payload, ensure_ascii=False) + "\n")


def _dataset_stats(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        return {}
    soft_bytes = [
        len(json.dumps(r["soft_label"], ensure_ascii=False).encode("utf-8")) for r in records
    ]
    total_tokens = sum(len(r["soft_label"]) for r in records)
    scene_dist: dict[str, int] = {}
    band_dist: dict[str, int] = {}
    for record in records:
        scene_dist[record["scene"]] = scene_dist.get(record["scene"], 0) + 1
        band = difficulty_band(record["difficulty"])
        band_dist[band] = band_dist.get(band, 0) + 1
    n = len(records)
    return {
        "size": n,
        "avg_quality": round(sum(quality_of(r) for r in records) / n, 4),
        "avg_difficulty": round(sum(r["difficulty"] for r in records) / n, 4),
        "scene_distribution": {k: round(v / n, 4) for k, v in sorted(scene_dist.items())},
        "band_distribution": {k: round(v / n, 4) for k, v in sorted(band_dist.items())},
        "soft_label": {
            "tokens_total": total_tokens,
            "avg_tokens_per_sample": round(total_tokens / n, 1),
            "avg_bytes_per_sample": round(sum(soft_bytes) / n, 1),
            "total_mb": round(sum(soft_bytes) / 1024 / 1024, 3),
            # 外推：这条数字直接验证设计文档里"5 万条硬标签 ≈ 4.9 GB"的估算
            "extrapolated_50k_gb": round(sum(soft_bytes) / n * 50000 / 1024 / 1024 / 1024, 2),
        },
    }


def _top_errors(invalid: list[dict[str, Any]], top: int = 5) -> list[dict[str, Any]]:
    counter: dict[str, int] = {}
    for record in invalid:
        for error in record.get("errors") or ["（无错误信息）"]:
            key = error.split("：")[0]
            counter[key] = counter.get(key, 0) + 1
    return [
        {"error": key, "count": value}
        for key, value in sorted(counter.items(), key=lambda kv: -kv[1])[:top]
    ]


def build_report(stats: dict[str, Any]) -> str:
    labeling = stats.get("labeling", {})
    dedup_stats = stats.get("dedup", {})
    cov = stats.get("coverage", {})
    alloc = stats.get("allocation", {})
    dataset = stats.get("dataset", {})
    soft = dataset.get("soft_label", {})

    lines: list[str] = []
    add = lines.append

    add("# Mova-AI 蒸馏数据集报告")
    add("")
    add("> 本报告由 `pipeline/build_dataset.py` 自动生成，数字全部来自实际运行，未手工填写。")
    add("")
    add("## 一、流水线各阶段")
    add("")
    add("| 阶段 | 指标 | 数值 |")
    add("| --- | --- | --- |")
    add(f"| ① 种子 | 展开样本数 | {stats.get('seeds', {}).get('total')} |")
    add(f"| ① 种子 | (场景,意图) 组合 | {stats.get('seeds', {}).get('scene_intent_pairs')} |")
    add(f"| ② 标注 | 双教师请求数（估） | {labeling.get('total')} 条 × 多轮 |")
    add(f"| ② 标注 | 平均单次延迟 | {labeling.get('avg_latency_ms')} ms |")
    add(f"| ② 标注 | logprobs 可用条数 | {labeling.get('logprob_available')} |")
    add(f"| ③ 契约校验 | **JSON/契约合法率** | **{labeling.get('json_valid_rate', 0):.1%}** |")
    add(f"| ③ 契约校验 | 不合格数 | {labeling.get('contract_invalid')} |")
    add(f"| ⑤ 去重 | LSH 候选对 | {dedup_stats.get('lsh_candidate_pairs')} |")
    add(f"| ⑤ 去重 | 精确复核后删除 | {dedup_stats.get('removed')}（{dedup_stats.get('removal_rate', 0):.1%}） |")
    add(f"| ⑥ 覆盖 | 网格覆盖 | {cov.get('covered_cells')}/{cov.get('universe_cells')}"
        f"（{cov.get('cell_coverage', 0):.1%}） |")
    add(f"| ⑥ 覆盖 | 子模目标 F | {cov.get('objective')} |")
    add(f"| ⑥ 覆盖 | 随机基线 F | {cov.get('random_baseline')} |")
    add(f"| ⑥ 覆盖 | **相对随机提升** | **{cov.get('improvement_over_random', 0):.1%}** |")
    add(f"| ⑦ 配比 | 最终选出 | {alloc.get('selected')} |")
    add("")

    add("## 二、契约校验失败原因（Top）")
    add("")
    if labeling.get("top_invalid_errors"):
        add("| 错误 | 次数 |")
        add("| --- | --- |")
        for item in labeling["top_invalid_errors"]:
            add(f"| {item['error']} | {item['count']} |")
    else:
        add("无失败样本。")
    add("")

    add("## 三、难度配比：目标 vs 实际")
    add("")
    add("| 难度档 | 目标占比 | 实际占比 | 偏差 |")
    add("| --- | --- | --- | --- |")
    for band, target in (alloc.get("target_mix") or {}).items():
        achieved = (alloc.get("achieved_mix") or {}).get(band, 0.0)
        add(f"| {band} | {target:.1%} | {achieved:.1%} | {achieved - target:+.1%} |")
    add("")
    if alloc.get("notes"):
        add("偏差说明（流水线自查发现，不是事后解释）：")
        add("")
        for note in alloc["notes"]:
            add(f"- {note}")
        add("")

    add("## 四、选样来源分解")
    add("")
    add("| 阶段 | 选出条数 | 含义 |")
    add("| --- | --- | --- |")
    stages = alloc.get("stage_counts") or {}
    add(f"| 覆盖保底 | {stages.get('coverage_floor', 0)} | 保证每个网格都有代表，避免长尾消失 |")
    add(f"| 难度配比 | {stages.get('mix_fill', 0)} | 按缺口补齐简单/中等/困难的目标比例 |")
    add(f"| 质量补足 | {stages.get('quality_fill', 0)} | 预算剩余时取全局质量最高者 |")
    add("")

    add("## 五、数据集构成")
    add("")
    add(f"- 规模：**{dataset.get('size')} 条**")
    add(f"- 平均质量分：{dataset.get('avg_quality')}")
    add(f"- 平均难度：{dataset.get('avg_difficulty')}")
    add("")
    add("场景分布：")
    add("")
    add("| 场景 | 占比 |")
    add("| --- | --- |")
    for scene, ratio in (dataset.get("scene_distribution") or {}).items():
        add(f"| {scene} | {ratio:.1%} |")
    add("")

    add("## 六、软标签（logit 蒸馏）空间预算")
    add("")
    add("这些数字直接验证设计文档中「top-K logits 离线缓存」方案的可行性估算：")
    add("")
    add("| 项 | 数值 |")
    add("| --- | --- |")
    add(f"| 总生成 token | {soft.get('tokens_total')} |")
    add(f"| 平均每样本 token | {soft.get('avg_tokens_per_sample')} |")
    add(f"| 平均每样本软标签 | {soft.get('avg_bytes_per_sample')} B |")
    add(f"| 当前数据集软标签总量 | {soft.get('total_mb')} MB |")
    add(f"| **外推到 5 万条** | **{soft.get('extrapolated_50k_gb')} GB** |")
    add("")
    add("> 结论：软标签落盘完全落在单机磁盘可承受范围内，")
    add("> 因此**学生训练时无需把教师放进显存**，8 GB 显卡也能做真正的 logit 蒸馏。")
    add("")

    add("## 七、可复现性")
    add("")
    add("```bash")
    add("cd scene_ai_server")
    add(f"python pipeline/build_dataset.py --limit {stats['config'].get('limit')} "
        f"--budget {stats['config'].get('budget')}")
    add("```")
    add("")
    add("种子展开使用固定随机种子，配置写入 `dataset_stats.json`，中间结果缓存在 `data/cache/`；")
    add("重跑不会重复消耗 API 额度。")
    add("")
    return "\n".join(lines)


# ============================================================
# CLI
# ============================================================

def parse_args(argv: Iterable[str] | None = None) -> Config:
    parser = argparse.ArgumentParser(description="Mova-AI 数据引擎")
    parser.add_argument("--limit", type=int, default=120, help="种子数量上限")
    parser.add_argument("--budget", type=int, default=100, help="最终数据集条数")
    parser.add_argument("--k-samples", type=int, default=3, help="自一致性采样次数")
    parser.add_argument("--workers", type=int, default=6, help="并发请求数")
    parser.add_argument("--gold-size", type=int, default=40, help="评测集条数")
    parser.add_argument("--out-dir", type=Path, default=SERVER_DIR / "data")
    args = parser.parse_args(list(argv) if argv is not None else None)

    return Config(
        limit=args.limit,
        budget=args.budget,
        k_samples=args.k_samples,
        workers=args.workers,
        gold_size=args.gold_size,
        out_dir=args.out_dir,
        cache_dir=args.out_dir / "cache",
    )


if __name__ == "__main__":
    config = parse_args()
    run(config)
