"""把 eval_gold.jsonl 渲染成人工抽检清单（TSV，用 Excel 或文本编辑器打开逐条判定）。

用法（在仓库根目录执行；正式抽检时传入 500 条 gold 的实际路径）：
    python tools/export_review.py /path/to/formal/eval_gold.jsonl 150

抽样（v2 修复）：按 (scene, intent) 组合**分层比例抽样**（最大余数法配
名额，固定种子 42，可复现），输出按组排序便于人工看系统性错误。
背景：gold 文件按种子抽取顺序排列（场景重排，food 块在最前），v1 的
"取前 150 行"实测 150/150 全是 food——一份不含 chat/reading/location/
none 的抽检清单对放行判定无效（2026-09 发现，本次修复）。

产物：<同名文件>.review.tsv，最后两列留空给人工填「判定 / 备注」。
判定建议：对 / 错 / 存疑。
- 系统性错误（某类 intent 或某个槽位大面积判错）> 5%：改契约或 prompt，
  清掉 data/cache 后重跑；
- 零星错误：接受，写进数据报告的已知噪声里。
"""
from __future__ import annotations

import json
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path


def main() -> int:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("scene_ai_server/data/eval_gold.jsonl")
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 150

    recs = [
        json.loads(line)
        for line in src.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    total = len(recs)
    if total == 0 or n <= 0:
        raise ValueError("gold 文件必须非空，抽样数量必须为正数")
    # 样本不足时取全量，避免清单声称抽取了超过实际数量的记录。
    n = min(n, total)

    # 按 (scene, intent) 分组
    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for r in recs:
        exp = r.get("expected") or {}
        groups[(str(exp.get("scene", "?")), str(exp.get("intent", "?")))].append(r)

    # 比例名额：最大余数法（n 条在 16 个组合间按占比分配）
    alloc: dict[tuple[str, str], int] = {}
    assigned = 0
    fr: list[tuple[float, tuple[str, str]]] = []
    for key, items in groups.items():
        raw = n * len(items) / total
        q = int(raw)
        alloc[key] = q
        assigned += q
        fr.append((raw - q, key))
    for _, key in sorted(fr, reverse=True):
        if assigned >= n:
            break
        alloc[key] += 1
        assigned += 1

    # 组内抽样（固定种子，可复现）
    rng = random.Random(42)
    chosen: list[dict] = []
    for key in sorted(groups):
        items = list(groups[key])
        rng.shuffle(items)
        chosen.extend(items[: alloc[key]])

    # 按 (scene, intent) 组序写出
    lines = [
        "id\tscene\tintent\tcomplexity\tneed_cloud\tslots\tutterance\texpected_json\t判定(对/错/存疑)\t备注"
    ]
    for r in chosen:
        exp = r.get("expected") or {}
        utterance = (r.get("utterance") or "").replace("\t", " ").replace("\n", " ")
        lines.append(
            "\t".join(
                [
                    str(r.get("id", "")),
                    str(exp.get("scene", "")),
                    str(exp.get("intent", "")),
                    str(exp.get("complexity", "")),
                    str(exp.get("need_cloud", "")),
                    json.dumps(exp.get("slots", {}), ensure_ascii=False),
                    utterance,
                    json.dumps(exp, ensure_ascii=False),
                    "",
                    "",
                ]
            )
        )
    out = Path(str(src) + ".review.tsv")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")

    src_scene = Counter(str((r.get("expected") or {}).get("scene", "?")) for r in recs)
    sel_scene = Counter(str((r.get("expected") or {}).get("scene", "?")) for r in chosen)
    print(f"写出 {out}（{len(chosen)} 行），打开后逐条判定，错误>5% 且呈系统性时回头改契约/清缓存重跑。")
    print("gold 场景构成：  " + "  ".join(f"{k}={v}" for k, v in src_scene.most_common()))
    print("清单场景构成：  " + "  ".join(f"{k}={v}" for k, v in sel_scene.most_common()))
    print("组合明细（池 -> 抽样）：")
    for key in sorted(groups):
        print(f"  {key[0]}/{key[1]}: {len(groups[key])} -> {alloc[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
