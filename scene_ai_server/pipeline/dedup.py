"""MinHash + LSH 近似去重。

**为什么不用 sentence-transformers？**
因为它会把整条流水线的依赖从"零"变成"一个 PyTorch"（约 2GB），
而中文短句的近重复检测用字符 n-gram MinHash 已经足够准，
而且在没有 GPU 的机器上快得多。

算法：
    ① **Shingling**：把文本切成字符 3-gram 集合（中文按字符切比按词切更适合短句）
    ② **MinHash**：用 128 个通用哈希 h_i(x) = (a_i·x + b_i) mod p 分别取最小值，
       得到 128 维签名。签名中相同分量的比例是 Jaccard 相似度的无偏估计
    ③ **LSH 分带**：把签名切成 b 带 × r 行，任一带完全相同即进入候选集。
       这步把 O(n²) 的全量比较降到近似 O(n)
    ④ **精确复核**：对候选对算真实 Jaccard，超过阈值才判定为重复

为什么必须有第 ④ 步：LSH 是**有假阳性的**（只是概率很低）。
在数据集构建里，"误删一条不重复的样本"比"漏掉一条重复"代价更高。
"""

from __future__ import annotations

import hashlib
import random
from dataclasses import dataclass
from typing import Callable, Iterable, Sequence

# Mersenne 素数，用于通用哈希族，保证 (a*x+b) mod p 的低碰撞率
_MERSENNE = (1 << 61) - 1


def _h64(text: str) -> int:
    return int.from_bytes(hashlib.blake2b(text.encode("utf-8"), digest_size=8).digest(), "big")


def shingles(text: str, n: int = 3) -> set[str]:
    """字符 n-gram 集合。文本很短时退化为整句本身。"""
    normalized = "".join(text.split()).lower()
    if len(normalized) <= n:
        return {normalized} if normalized else set()
    return {normalized[i : i + n] for i in range(len(normalized) - n + 1)}


class MinHasher:
    def __init__(self, num_perm: int = 128, shingle_size: int = 3, seed: int = 20260921) -> None:
        self.num_perm = num_perm
        self.shingle_size = shingle_size
        rng = random.Random(seed)
        self._coeffs = [
            (rng.randrange(1, _MERSENNE), rng.randrange(0, _MERSENNE)) for _ in range(num_perm)
        ]

    def signature(self, text: str) -> tuple[int, ...]:
        grams = shingles(text, self.shingle_size)
        if not grams:
            return tuple([0] * self.num_perm)
        hashed = [_h64(gram) for gram in grams]
        return tuple(
            min((a * value + b) % _MERSENNE for value in hashed) for a, b in self._coeffs
        )


def jaccard(a: set[str], b: set[str]) -> float:
    if not a and not b:
        return 1.0
    union = a | b
    return len(a & b) / len(union) if union else 1.0


def lsh_candidates(signatures: Sequence[tuple[int, ...]], bands: int = 32) -> set[tuple[int, int]]:
    """按带分桶得到候选对。要求 num_perm 能被 bands 整除。"""
    if not signatures:
        return set()
    num_perm = len(signatures[0])
    rows = max(1, num_perm // bands)
    candidates: set[tuple[int, int]] = set()

    for band in range(bands):
        buckets: dict[tuple[int, ...], list[int]] = {}
        start = band * rows
        end = start + rows
        for index, signature in enumerate(signatures):
            key = signature[start:end]
            buckets.setdefault(key, []).append(index)
        for members in buckets.values():
            if len(members) < 2:
                continue
            for i in range(len(members)):
                for j in range(i + 1, len(members)):
                    candidates.add((members[i], members[j]))
    return candidates


@dataclass
class DedupResult:
    kept_indices: list[int]
    #: 被删索引 -> (保留的索引, 相似度)
    removed: dict[int, tuple[int, float]]
    candidate_pairs: int
    exact_checks: int

    @property
    def removed_count(self) -> int:
        return len(self.removed)


def dedup(
    texts: Sequence[str],
    *,
    threshold: float = 0.80,
    num_perm: int = 128,
    bands: int = 32,
    shingle_size: int = 3,
    keep_priority: Callable[[int], float] | None = None,
) -> DedupResult:
    """近似去重，返回保留下来的下标。

    keep_priority(i) 越大越优先保留；默认为 None，表示按输入顺序保留靠前的。
    典型用法是传"质量分"，让质量高的样本在重复对中胜出。
    """
    if not texts:
        return DedupResult([], {}, 0, 0)

    hasher = MinHasher(num_perm=num_perm, shingle_size=shingle_size)
    signatures = [hasher.signature(text) for text in texts]
    grams = [shingles(text, shingle_size) for text in texts]

    candidate_pairs = lsh_candidates(signatures, bands=bands)

    # 并查集：把判定为重复的一组样本归到一起
    parent = list(range(len(texts)))

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x: int, y: int) -> None:
        rx, ry = find(x), find(y)
        if rx != ry:
            parent[max(rx, ry)] = min(rx, ry)

    exact_checks = 0
    for i, j in candidate_pairs:
        exact_checks += 1
        if jaccard(grams[i], grams[j]) >= threshold:
            union(i, j)

    # 每个连通分量里选一个代表：优先 keep_priority，其次下标小者
    groups: dict[int, list[int]] = {}
    for index in range(len(texts)):
        groups.setdefault(find(index), []).append(index)

    kept: list[int] = []
    removed: dict[int, tuple[int, float]] = {}
    for members in groups.values():
        if len(members) == 1:
            kept.append(members[0])
            continue
        if keep_priority is not None:
            representative = max(members, key=lambda idx: (keep_priority(idx), -idx))
        else:
            representative = min(members)
        kept.append(representative)
        for member in members:
            if member != representative:
                removed[member] = (representative, round(jaccard(grams[member], grams[representative]), 4))

    kept.sort()
    return DedupResult(
        kept_indices=kept,
        removed=removed,
        candidate_pairs=len(candidate_pairs),
        exact_checks=exact_checks,
    )


def estimate_false_positive_rate(num_perm: int, bands: int, threshold: float) -> float:
    """估算 LSH 在给定阈值下的"漏检"概率 1-(1-s^r)^b。

    注意这是**漏检率**，不是误删率：LSH 本身只产生候选对，
    最终判定由精确 Jaccard 复核，所以不会误删。
    这个函数的用途是选参数 —— 让阈值附近的漏检率足够低。
    """
    rows = max(1, num_perm // bands)
    return 1.0 - (1.0 - threshold**rows) ** bands
