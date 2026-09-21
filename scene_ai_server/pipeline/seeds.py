"""场景种子库。

数据引擎的起点。种子不是"随便写几句话"，而是刻意覆盖
(scene × intent × 难度档 × 槽位组合) 这个网格的锚点 —— 后续的子模覆盖优化
就是在这个网格上做最大化，种子决定网格能不能被填满。

每个种子给出：
    signals  —— 环境信号（时间 / 前台 App / 位置 / 运动状态），是"主动智能"的输入
    template —— 用户话语模板，{} 会被 vocab 里的词填充，产生表述多样性
    hint     —— 期望的复杂度档，用于校验教师给出的复杂度是否跑偏
"""

from __future__ import annotations

import itertools
import random
from dataclasses import dataclass, field

VOCAB: dict[str, list[str]] = {
    "veg": ["番茄", "青椒", "土豆", "西兰花", "菠菜", "茄子", "黄瓜", "胡萝卜"],
    "protein": ["鸡蛋", "鸡胸肉", "牛肉", "豆腐", "虾仁", "五花肉", "带鱼", "鸡腿"],
    "staple": ["米饭", "面条", "馒头", "年糕", "意面", "馄饨"],
    "meal": ["早饭", "午饭", "晚饭", "夜宵"],
    "doc": ["产品需求文档", "学期论文", "租赁合同", "会议纪要", "体检报告", "课程讲义"],
    "relation": ["室友", "同事", "大学同学", "部门主管", "邻居", "表姐"],
    "place": ["超市", "商场", "学校", "地铁站", "医院", "健身房"],
}

SIGNALS = {
    "kitchen": "时间：18:40 | 前台 App：相机 | 位置：家 | 运动：静止 | 屏幕：亮",
    "desk": "时间：14:10 | 前台 App：WPS | 位置：办公室 | 运动：静止 | 屏幕：亮",
    "commute": "时间：08:05 | 前台 App：微信 | 位置：地铁 | 运动：移动中 | 屏幕：亮",
    "market": "时间：19:20 | 前台 App：桌面 | 位置：超市 | 运动：步行 | 屏幕：亮",
    "night": "时间：23:30 | 前台 App：微信 | 位置：家 | 运动：静止 | 屏幕：亮",
    "campus": "时间：12:15 | 前台 App：微信 | 位置：校园 | 运动：步行 | 屏幕：亮",
}


@dataclass
class Seed:
    id: str
    scene: str
    intent: str
    complexity_hint: str
    signals: str
    utterance: str
    expect_slots: list[str] = field(default_factory=list)


SPECS: list[dict] = [
    # ---------------- 做饭 ----------------
    dict(scene="food", intent="diet_advice", complexity_hint="medium", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"],
         templates=[
             "我在厨房，手边有{veg}和{protein}，{meal}做点什么好？",
             "拍了一下冰箱，只有{veg}和{staple}，能凑一顿{meal}吗？",
             "今天买了{protein}和{veg}，想清淡一点，怎么做好？",
         ]),
    dict(scene="food", intent="ingredient_check", complexity_hint="easy", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"],
         templates=[
             "看看这些是不是都能一起吃：{veg}、{protein}、{staple}？",
             "我这里有{veg}和{protein}，会不会相克？",
         ]),
    dict(scene="food", intent="recipe_lookup", complexity_hint="hard", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"],
         templates=[
             "只有{veg}、{protein}和{staple}，帮我设计一份适合减脂的三菜一汤，要控制总热量",
             "用{protein}和{veg}做一道能带饭的菜，要放凉了也好吃，最好前一天晚上就能备好",
         ]),
    dict(scene="food", intent="none", complexity_hint="easy", signals=SIGNALS["kitchen"],
         expect_slots=[],
         templates=["这个菜市场叫什么名字？", "现在几点了？"]),

    # ---------------- 阅读 ----------------
    dict(scene="reading", intent="summarize", complexity_hint="medium", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "这份{doc}太长了，帮我看看重点是什么",
             "把这份{doc}总结三句话，我要发到群里",
         ]),
    dict(scene="reading", intent="key_points", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=["{doc}里有哪些是我必须注意的条款？", "从这份{doc}里挑出最关键的几条要求"]),
    dict(scene="reading", intent="difficulty_judge", complexity_hint="hard", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "这份{doc}以我目前的水平能看懂吗？我只有大一基础",
             "把{doc}按难度排序，告诉我哪部分需要先补背景知识",
         ]),

    # ---------------- 聊天 ----------------
    dict(scene="chat", intent="reply_suggest", complexity_hint="medium", signals=SIGNALS["night"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "{relation}问我周末能不能帮忙搬家，我不想直接答应，怎么回？",
             "{relation}在群里@我问方案进度，其实我还没开始，怎么回比较得体？",
         ]),
    dict(scene="chat", intent="rewrite", complexity_hint="easy", signals=SIGNALS["night"],
         expect_slots=["target_tone", "goal"],
         templates=["把「这个方案不行」改得委婉一点", "把这句话改得不那么冲：你根本没看我的消息"]),
    dict(scene="chat", intent="tone_adjust", complexity_hint="easy", signals=SIGNALS["commute"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "{relation}发来的消息我要回得专业一点，帮我把语气调一下",
             "我想拒绝但不想得罪{relation}，这句话怎么改？",
         ]),

    # ---------------- 位置 ----------------
    dict(scene="location", intent="nearby_hint", complexity_hint="easy", signals=SIGNALS["market"],
         expect_slots=["place_type"],
         templates=["我现在在{place}，有什么需要顺手买的吗？", "到了{place}，提醒我该准备点什么"]),
    dict(scene="location", intent="timing_reminder", complexity_hint="easy", signals=SIGNALS["campus"],
         expect_slots=["place_type", "time_bucket"],
         templates=["我在{place}，这个点食堂还开着吗？", "在{place}，接下来一小时适合做什么？"]),

    # ---------------- 无场景（负样本，必须有） ----------------
    dict(scene="none", intent="none", complexity_hint="easy", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "你好",
             "1+1 等于几",
             "今天天气怎么样",
             "讲个笑话吧",
             "你是谁",
         ]),

    # ============================================================
    # 困难样本组
    # ------------------------------------------------------------
    # 加这一组的直接原因：第一轮跑完 120 条，难度全部落在 easy 档，
    # 没有一个样本进入 hard 档 —— 因为前面的种子都是"换个食材名"式的表面变化，
    # 任务本身毫无歧义。**难度分布的稀疏是语料设计问题，不是度量问题。**
    #
    # 真正难的任务长这样：约束冲突、信息缺失、跨场景歧义、需要外部知识、
    # 或者连人类都不确定标准答案是什么。
    # ============================================================
    dict(scene="food", intent="recipe_lookup", complexity_hint="hard", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"],
         templates=[
             "我妈血糖高，手边有{veg}和{staple}，做一顿升糖慢的{meal}，还要告诉我哪些得先焯水",
             "{protein}和{veg}都有，但我只有一个电磁炉和一个微波炉，半小时内要端上桌，怎么安排顺序？",
         ]),
    dict(scene="food", intent="diet_advice", complexity_hint="hard", signals=SIGNALS["kitchen"],
         expect_slots=["ingredients"],
         templates=[
             "冰箱里只剩{veg}和{protein}了，家里有人不吃辣、有人不吃香菜，还要保证蛋白质够，怎么搭？",
             "我在减脂，但{protein}是昨天剩的，还能不能吃？顺便说说这顿大概多少热量",
         ]),
    dict(scene="reading", intent="summarize", complexity_hint="hard", signals=SIGNALS["desk"],
         expect_slots=["source", "text_length"],
         templates=[
             "这份{doc}里前后两段说法互相矛盾，到底以哪个为准？",
             "把{doc}里所有带时间节点的要求按截止日期排个序，有冲突的标出来",
         ]),
    dict(scene="chat", intent="reply_suggest", complexity_hint="hard", signals=SIGNALS["night"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "{relation}发来「你上次说的那个事，我考虑了一下」，可我根本不记得是什么事，怎么回？",
             "客户第三次说「方案我再想想」，我想推进又不想显得急，怎么回？",
         ]),
    dict(scene="chat", intent="tone_adjust", complexity_hint="hard", signals=SIGNALS["commute"],
         expect_slots=["target_tone", "goal"],
         templates=[
             "{relation}在群里当众指出我的错误，我要回应但不想把场面搞僵，帮我想想",
             "我确实搞砸了，但不想写成一味道歉，想让对方觉得我会改，怎么措辞？",
         ]),

    # ---------------- 跨场景歧义（故意让判断边界模糊） ----------------
    dict(scene="none", intent="none", complexity_hint="hard", signals=SIGNALS["desk"],
         expect_slots=[],
         templates=[
             "看看这个",
             "那个弄好了吗",
             "这个还有救吗",
         ]),
]


def generate_seeds(limit: int | None = None, seed: int = 20260921) -> list[Seed]:
    """把种子模板展开成具体的用户输入。

    limit 控制总量。**采样方式是按组轮转，而不是全局随机截断** ——
    这一点很重要：困难样本组的模板数远少于"换个食材名"这类组，
    全局随机截断会让它们以很高的概率被整组丢掉，
    于是难度分布永远填不满 hard 档（这是第一轮实测踩过的坑）。

    展开是确定性的（固定随机种子），任何人重跑都得到同一份数据集。
    """
    rng = random.Random(seed)
    groups: list[list[Seed]] = []
    counter = 0

    for spec in SPECS:
        group: list[Seed] = []
        for template in spec["templates"]:
            # 占位符按**单个模板**取，而不是取整个 spec 的并集 ——
            # 否则会把不相干的词表也做笛卡尔积，导致样本量爆炸且分布严重失衡
            names = _placeholders([template])
            pools = [VOCAB[name] for name in names]
            combos = list(itertools.product(*pools)) if pools else [()]

            for combo in combos:
                fields = dict(zip(names, combo))
                utterance = template.format(**fields) if fields else template
                counter += 1
                group.append(
                    Seed(
                        id=f"{spec['scene']}_{counter:04d}",
                        scene=spec["scene"],
                        intent=spec["intent"],
                        complexity_hint=spec["complexity_hint"],
                        signals=spec["signals"],
                        utterance=utterance,
                        expect_slots=list(spec["expect_slots"]),
                    )
                )
        if group:
            rng.shuffle(group)
            groups.append(group)

    # 组内已打散，组间轮转取样，保证每个 spec 都有代表
    interleaved: list[Seed] = []
    cursors = [0] * len(groups)
    while len(interleaved) < sum(len(g) for g in groups):
        progressed = False
        for index, group in enumerate(groups):
            if cursors[index] < len(group):
                interleaved.append(group[cursors[index]])
                cursors[index] += 1
                progressed = True
        if not progressed:
            break

    if limit is not None and limit < len(interleaved):
        interleaved = interleaved[:limit]
    return interleaved


def _placeholders(templates: list[str]) -> list[str]:
    import re

    found: list[str] = []
    for template in templates:
        for name in re.findall(r"\{(\w+)\}", template):
            if name not in found and name in VOCAB:
                found.append(name)
    return found


def coverage_cells(seeds: list[Seed]) -> dict[str, list[str]]:
    """把种子映射到覆盖网格的格子：scene × intent × complexity_hint。"""
    cells: dict[str, list[str]] = {}
    for item in seeds:
        key = f"{item.scene}|{item.intent}|{item.complexity_hint}"
        cells.setdefault(key, []).append(item.id)
    return cells


if __name__ == "__main__":
    all_seeds = generate_seeds()
    cells = coverage_cells(all_seeds)
    print(f"种子总数：{len(all_seeds)}")
    print(f"覆盖格子：{len(cells)}")
    for key, ids in sorted(cells.items()):
        print(f"  {key:<40} {len(ids):>3} 条")
