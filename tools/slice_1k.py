"""从 distill_dataset.jsonl 分层抽 n 条（默认 1000），供 LoRA-1K 实验。

分层键：教师 target.scene × target.intent × 难度档（difficulty 按契约分档：<0.34 easy，
<0.67 medium，其余 hard，与 pipeline/contract.py 的 COMPLEXITY_BANDS 一致）。
名额按各组合在全集中的占比用最大余数法分配，保证 1K 子集与 5.5K 全集
尽量同分布。使用教师标签而非种子意图，与学生实际学习目标一致。

用法（在仓库根目录执行；正式实验时传入 5500 条数据的实际路径）：
    python tools/slice_1k.py scene_ai_server/data/distill_dataset.jsonl /tmp/distill_1k.jsonl 1000 42
"""

from __future__ import annotations

import json
import random
import sys
from collections import defaultdict
from pathlib import Path


def band(d: float) -> str:
    if d < 0.34:
        return "easy"
    if d < 0.67:
        return "medium"
    return "hard"


def main() -> int:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("scene_ai_server/data/distill_dataset.jsonl")
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("scene_ai_server/data/distill_1k.jsonl")
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 1000
    seed = int(sys.argv[4]) if len(sys.argv) > 4 else 42

    rows = [json.loads(line) for line in src.read_text(encoding="utf-8").splitlines() if line.strip()]
    rng = random.Random(seed)

    total = len(rows)
    if total == 0 or n <= 0:
        raise ValueError("训练文件必须非空，抽样数量必须为正数")
    n = min(n, total)
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in rows:
        target = r.get("target")
        if not isinstance(target, dict) or not target.get("scene") or not target.get("intent"):
            raise ValueError(f"样本 {r.get('id', '?')} 缺少教师 target.scene/intent")
        # 正式批次中种子意图和教师标注意图存在系统性差异；分层必须跟随教师标签。
        groups[(target["scene"], target["intent"], band(float(r["difficulty"])))].append(r)
    quotas: dict[tuple, int] = {}
    assigned = 0
    remainders: list[tuple[float, tuple]] = []
    for key, items in groups.items():
        raw = n * len(items) / total
        q = int(raw)
        quotas[key] = q
        assigned += q
        remainders.append((raw - q, key))
    for _frac, key in sorted(remainders, reverse=True):
        if assigned >= n:
            break
        quotas[key] += 1
        assigned += 1

    picked: list[dict] = []
    for key, items in groups.items():
        k = min(quotas[key], len(items))
        picked.extend(rng.sample(items, k))
    rng.shuffle(picked)

    dst.parent.mkdir(parents=True, exist_ok=True)
    with dst.open("w", encoding="utf-8") as handle:
        for r in picked:
            handle.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"写出 {len(picked)} 条 -> {dst}")
    print("分层分布：")
    dist: dict[tuple, int] = defaultdict(int)
    for r in picked:
        target = r["target"]
        dist[(target["scene"], target["intent"], band(float(r["difficulty"])))] += 1
    for key in sorted(dist):
        print(f"  {key[0]}/{key[1]}/{key[2]}: {dist[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
