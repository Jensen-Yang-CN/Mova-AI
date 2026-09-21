"""配额配比求解：把"简单 30% / 中等 50% / 困难 20%"落成可执行的选样规则。

## 为什么不能直接按比例随机抽

因为有些格子里的合格样本根本不够。按比例抽样会在样本充足的格子里反复取样、
在样本稀少的格子里取空，最后既达不到配比、也拉低了平均质量。

## 做法：三阶段填充

    阶段一 · 覆盖保底    每个网格至少取 floor(budget · α / |𝒢|) 条（取该格质量最高的）
    阶段二 · 难度配比    按难度档的"缺口"从大到小，用该档质量最高的样本补齐
    阶段三 · 质量补足    预算若还有剩余，全局取质量最高者

## 关于最优性（说清楚，不吹）

- 阶段一与阶段三在各自可行域内取当前最优，是**最优**的（目标函数线性、约束是配额）。
- 阶段二中"先补缺口最大的档"是**启发式**：各档之间唯一的耦合是"每格总量上限"，
  当上限不紧时它等价于最优；上限紧时会与最优有偏差。
- 因此本模块**同时输出实际达成的配比**，任何偏差都写在数据报告里可审计，
  而不是假装达到了目标。这比在文档里写一句"配比 30/50/20"诚实得多。

难度档目标用**最大余数法**（Hare-Niemeyer）分配整数名额，保证总和精确等于预算。
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from typing import Callable, Sequence


@dataclass
class AllocationResult:
    selected: list[int]
    #: 实际达成的难度配比
    achieved_mix: dict[str, float]
    #: 目标配比
    target_mix: dict[str, float]
    per_cell: dict[str, int]
    #: 各阶段各选了多少条，用于解释"这些样本是怎么被选进来的"
    stage_counts: dict[str, int] = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)


def largest_remainder(total: int, weights: dict[str, float]) -> dict[str, int]:
    """最大余数法：按权重把 total 个整数名额分给各类，总和精确等于 total。

    直接用 round() 会出总和不为 total 的问题；最大余数法先把整数部分分下去，
    再把剩余名额按小数部分从大到小分配，是标准的席位分配方法。
    """
    if total <= 0 or not weights:
        return {key: 0 for key in weights}

    weight_sum = sum(max(0.0, w) for w in weights.values())
    if weight_sum <= 0:
        return {key: 0 for key in weights}

    exact = {key: total * max(0.0, w) / weight_sum for key, w in weights.items()}
    quota = {key: int(value) for key, value in exact.items()}
    assigned = sum(quota.values())

    if assigned < total:
        remainders = sorted(
            exact, key=lambda key: (exact[key] - quota[key]), reverse=True
        )
        for index in range(total - assigned):
            quota[remainders[index % len(remainders)]] += 1
    return quota


def allocate(
    cells: Sequence[str],
    bands: Sequence[str],
    quality: Sequence[float],
    budget: int,
    *,
    target_mix: dict[str, float],
    coverage_floor: float = 0.25,
    max_share_per_cell: float = 0.40,
    allowed: set[int] | None = None,
) -> AllocationResult:
    """按"覆盖保底 → 难度配比 → 质量补足"三阶段选出 budget 条样本。

    allowed 用于排除不合格样本（契约校验失败、重复、难度与预设严重不符等）。
    """
    n = len(cells)
    allowed = allowed if allowed is not None else set(range(n))
    order_global = sorted(allowed, key=lambda i: -quality[i])
    rank = {index: position for position, index in enumerate(order_global)}
    cap = max(1, int(budget * max_share_per_cell))

    picked: set[int] = set()
    per_cell: dict[str, int] = defaultdict(int)
    stage_counts = {"coverage_floor": 0, "mix_fill": 0, "quality_fill": 0}
    notes: list[str] = []

    def take(index: int, stage: str) -> bool:
        cell = cells[index]
        if index in picked or index not in allowed or len(picked) >= budget:
            return False
        if per_cell[cell] >= cap:
            return False
        picked.add(index)
        per_cell[cell] += 1
        stage_counts[stage] += 1
        return True

    # ---------- 阶段一：覆盖保底 ----------
    cell_members: dict[str, list[int]] = defaultdict(list)
    for index in allowed:
        cell_members[cells[index]].append(index)
    for members in cell_members.values():
        members.sort(key=lambda i: rank[i])

    num_cells = len(cell_members)
    if num_cells:
        floor_total = int(budget * coverage_floor)
        per_cell_floor = max(1, floor_total // num_cells) if floor_total else 0
        for members in cell_members.values():
            for index in members[:per_cell_floor]:
                take(index, "coverage_floor")

    # ---------- 阶段二：难度配比 ----------
    band_targets = largest_remainder(budget, target_mix)
    band_buckets: dict[str, list[int]] = defaultdict(list)
    for index in allowed:
        band_buckets[bands[index]].append(index)
    for members in band_buckets.values():
        members.sort(key=lambda i: rank[i])
    band_cursor = {band: 0 for band in band_buckets}

    remaining = budget - len(picked)
    if remaining > 0:
        # 按当前"缺口"（目标数 − 已选数）从大到小反复填充
        guard = 0
        while len(picked) < budget and guard < budget * 4:
            guard += 1
            deficit = {
                band: band_targets.get(band, 0) - sum(1 for i in picked if bands[i] == band)
                for band in band_buckets
            }
            band = max(deficit, key=lambda b: deficit[b])
            if deficit[band] <= 0:
                break
            members = band_buckets[band]
            cursor = band_cursor[band]
            advanced = False
            while cursor < len(members):
                if take(members[cursor], "mix_fill"):
                    advanced = True
                    cursor += 1
                    break
                cursor += 1  # 被格子上限挡住或已选，跳过
            band_cursor[band] = cursor
            if not advanced and cursor >= len(members):
                # 这一档已经没有可用样本，把它的目标清零避免死循环
                band_targets[band] = sum(1 for i in picked if bands[i] == band)

    # ---------- 阶段三：质量补足 ----------
    for index in order_global:
        if len(picked) >= budget:
            break
        take(index, "quality_fill")

    if len(picked) < budget:
        notes.append(f"可用样本不足：目标 {budget} 条，实际选出 {len(picked)} 条")

    selected = sorted(picked)
    achieved = _mix_of(selected, bands)
    for band, target in target_mix.items():
        actual = achieved.get(band, 0.0)
        if abs(actual - target) > 0.06:
            notes.append(
                f"{band} 档实际占比 {actual:.1%}，目标 {target:.1%}，偏差超过 6 个百分点"
            )

    return AllocationResult(
        selected=selected,
        achieved_mix=achieved,
        target_mix=dict(target_mix),
        per_cell={cell: count for cell, count in sorted(per_cell.items())},
        stage_counts=stage_counts,
        notes=notes,
    )


def _mix_of(selected: Sequence[int], bands: Sequence[str]) -> dict[str, float]:
    if not selected:
        return {}
    counts: dict[str, int] = defaultdict(int)
    for index in selected:
        counts[bands[index]] += 1
    return {band: round(count / len(selected), 4) for band, count in sorted(counts.items())}


def difficulty_band(difficulty: float) -> str:
    if difficulty < 0.34:
        return "easy"
    if difficulty < 0.67:
        return "medium"
    return "hard"
