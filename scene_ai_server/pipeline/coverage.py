"""覆盖度优化：把"选哪些样本"写成子模最大化。

## 目标函数

先把候选样本映射到一个网格：

    𝒢 = 场景 × 意图 × 难度档 × 槽位组合

定义覆盖函数

    F(S) = Σ_{g ∈ 𝒢} sqrt( Σ_{i ∈ S ∩ g} q_i )

其中 q_i 是样本 i 的质量分。

## 为什么用平方根

如果直接用线性求和 F(S)=Σ q_i，算法会把样本全部堆到样本最多的那个格子里 ——
因为那里"总量最大"。平方根让**边际收益递减**：

    Δ_i = sqrt(Q_g + q_i) − sqrt(Q_g)

在格子 g 已经很满时，再加一条的边际收益就变小了，于是算法会自动去补空白格子。
这正是"覆盖"想要的语义，而且它保证了 F 是**单调子模**的 ——
贪心算法因此有 (1 − 1/e) ≈ 0.632 的近似保证（Nemhauser et al., 1978）。

## 为什么这样就够了

因为边际收益是 O(1) 可算的（只需要维护每个格子的质量累加 Q_g），
不需要每次重算整个 F。所以朴素贪心是 O(N·M)，在万级候选集上只需几秒，
不必上 lazy greedy 的优先队列技巧。
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass
from typing import Callable, Sequence


@dataclass
class CoverageResult:
    selected: list[int]
    #: 每个格子的 (入选数, 质量累加)
    cells: dict[str, tuple[int, float]]
    objective: float
    #: 贪心过程中每一步的边际收益，用于画收益递减曲线
    marginal_gains: list[float]


def greedy_cover(
    cells: Sequence[str],
    quality: Sequence[float],
    budget: int,
    *,
    must_include: Sequence[int] = (),
    excluded: set[int] | None = None,
) -> CoverageResult:
    """在预算内贪心最大化 F(S)。

    must_include 会先放进 S（用于强制保底的样本），
    excluded 用于标记不可选（例如被判为重复的样本）。
    """
    excluded = excluded or set()
    n = len(cells)
    if n == 0 or budget <= 0:
        return CoverageResult([], {}, 0.0, [])

    quality_sum: dict[str, float] = defaultdict(float)
    count: dict[str, int] = defaultdict(int)
    chosen: list[int] = []
    taken: set[int] = set()

    def add(index: int) -> float:
        cell = cells[index]
        before = quality_sum[cell]
        after = before + max(0.0, quality[index])
        quality_sum[cell] = after
        count[cell] += 1
        chosen.append(index)
        taken.add(index)
        return math.sqrt(after) - math.sqrt(before)

    for index in must_include:
        if 0 <= index < n and index not in excluded and index not in taken and len(chosen) < budget:
            add(index)

    marginal_gains: list[float] = []
    while len(chosen) < budget:
        best_index = -1
        best_gain = -1.0
        for index in range(n):
            if index in taken or index in excluded:
                continue
            cell = cells[index]
            gain = math.sqrt(quality_sum[cell] + max(0.0, quality[index])) - math.sqrt(
                quality_sum[cell]
            )
            if gain > best_gain:
                best_gain = gain
                best_index = index
        if best_index < 0 or best_gain <= 0:
            break
        add(best_index)
        marginal_gains.append(round(best_gain, 6))

    objective = sum(math.sqrt(value) for value in quality_sum.values())
    return CoverageResult(
        selected=chosen,
        cells={cell: (count[cell], round(quality_sum[cell], 3)) for cell in count},
        objective=round(objective, 4),
        marginal_gains=marginal_gains,
    )


def coverage_report(
    cells: Sequence[str],
    quality: Sequence[float],
    selected: Sequence[int],
    all_cells: Sequence[str] | None = None,
) -> dict[str, object]:
    """给出"覆盖率"这一类直观指标，便于写进数据报告。"""
    universe = set(all_cells if all_cells is not None else cells)
    hit: dict[str, float] = defaultdict(float)
    for index in selected:
        hit[cells[index]] += max(0.0, quality[index])
    covered = set(hit)
    return {
        "universe_cells": len(universe),
        "covered_cells": len(covered),
        "cell_coverage": round(len(covered) / len(universe), 4) if universe else 0.0,
        "empty_cells": sorted(universe - covered),
        "per_cell": {cell: round(value, 3) for cell, value in sorted(hit.items())},
    }


def random_baseline_objective(
    cells: Sequence[str],
    quality: Sequence[float],
    budget: int,
    *,
    trials: int = 200,
    seed: int = 20260921,
) -> float:
    """随机选同样多的样本，取平均 F 值。

    用来证明"子模贪心确实比随机强" —— 报告里的对照数字来自这里，
    不是拍脑袋写的。
    """
    import random

    rng = random.Random(seed)
    n = len(cells)
    if n == 0 or budget <= 0:
        return 0.0
    budget = min(budget, n)
    totals = []
    for _ in range(trials):
        picks = rng.sample(range(n), budget)
        sums: dict[str, float] = defaultdict(float)
        for index in picks:
            sums[cells[index]] += max(0.0, quality[index])
        totals.append(sum(math.sqrt(v) for v in sums.values()))
    return round(sum(totals) / len(totals), 4)
