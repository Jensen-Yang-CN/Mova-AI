"""S1 · 端侧能力契约。

这是整个项目最关键的一份定义：**端侧小模型到底要学什么**。
如果这一步不做，最后一定退化成"端侧什么都不会，全部上云"，项目也就回到调 API。

契约的三个特点：
    ① **可执行**，不只是文档：这里同时提供 JSON Schema 与校验函数，
       数据流水线用它来判定"这条教师样本合不合格"，评测用它来算 JSON 合法率。
    ② **面向决策而非生成**：端侧只输出场景/意图/复杂度/是否上云/槽位，
       不输出长文本。长生成与复杂推理明确留给云端。
    ③ **need_cloud 由模型自己给出**：路由因此是"被蒸馏出来的一项能力"，
       而不是外部写死的阈值规则 —— 这是本项目在算法上最站得住脚的一点。
"""

from __future__ import annotations

import json
from typing import Any

# ============================================================
# 契约取值空间
# ============================================================

SCENES = ("food", "reading", "chat", "location", "none")

INTENTS_BY_SCENE: dict[str, tuple[str, ...]] = {
    "food": ("diet_advice", "recipe_lookup", "ingredient_check", "none"),
    "reading": ("summarize", "key_points", "difficulty_judge", "none"),
    "chat": ("reply_suggest", "rewrite", "tone_adjust", "none"),
    "location": ("nearby_hint", "timing_reminder", "none"),
    "none": ("none",),
}

#: 槽位定义：key -> (类型, 是否必填, 取值范围/说明)
SLOTS_BY_SCENE: dict[str, dict[str, tuple[str, bool, Any]]] = {
    "food": {
        "ingredients": ("array[string]", True, "识别到的食材中文名，最多 8 个"),
        # 取值直接用领域里的自然中文，与种子词表一致。
        # 教训：第一版这里写的是 breakfast/lunch/dinner/snack 英文枚举，
        # 而 prompt 又没列出允许取值，教师只能猜，于是吐出「晚饭」→ 校验全部失败。
        # 契约的取值空间必须用领域语言，并且必须显式写进 prompt。
        "meal": ("enum", False, ("早饭", "午饭", "晚饭", "夜宵")),
    },
    "reading": {
        "text_length": ("integer", True, "输入文本字符数；图片输入填 0"),
        "source": ("enum", True, ("text", "image", "pdf")),
        "topic": ("string", False, "主题关键词，最多 12 字"),
    },
    "chat": {
        "target_tone": ("enum", True, ("自然", "礼貌", "委婉", "幽默", "职场", "亲密", "冷处理")),
        "goal": ("enum", True, ("接受", "拒绝", "推迟", "转移话题", "降低冲突", "不指定")),
        "relation": ("enum", False, ("家人", "朋友", "同事", "上级", "陌生人")),
    },
    "location": {
        "place_type": ("enum", True, ("超市", "商场", "校园", "餐厅", "交通枢纽", "其他")),
        "time_bucket": ("enum", False, ("morning", "noon", "afternoon", "evening", "night")),
    },
    "none": {},
}

#: 复杂度分档：用于数据配比与难度分层
COMPLEXITY_BANDS = (
    ("easy", 0.0, 0.34),
    ("medium", 0.34, 0.67),
    ("hard", 0.67, 1.0001),
)

#: 数据配比目标（简单 / 中等 / 困难）
TARGET_MIX = {"easy": 0.30, "medium": 0.50, "hard": 0.20}


def band_of(complexity: float) -> str:
    for name, low, high in COMPLEXITY_BANDS:
        if low <= complexity < high:
            return name
    return "hard"


# ============================================================
# JSON Schema（可直接交给语法约束解码使用）
# ============================================================

def json_schema() -> dict[str, Any]:
    """生成端侧模型的输出 JSON Schema。

    端侧用 GBNF / JSON-Schema 约束解码时直接消费它，从而把
    "0.6B 模型 JSON 格式崩坏"这个最常见的失败模式结构性消除。
    """
    return {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "title": "MovaEdgeDecision",
        "type": "object",
        "additionalProperties": False,
        "required": ["scene", "intent", "complexity", "confidence", "need_cloud", "slots"],
        "properties": {
            "scene": {"type": "string", "enum": list(SCENES)},
            "intent": {
                "type": "string",
                "enum": sorted({i for group in INTENTS_BY_SCENE.values() for i in group}),
            },
            "complexity": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "confidence": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            "need_cloud": {"type": "boolean"},
            "slots": {
                "type": "object",
                "additionalProperties": True,
                "description": "按 scene 分支的槽位，字段定义见 SLOTS_BY_SCENE",
            },
            "reason": {
                "type": "string",
                "maxLength": 60,
                "description": "一句话说明为什么这么判断（可选，供人工核查与可解释性展示）",
            },
        },
    }


# ============================================================
# 校验器（数据流水线与评测共用）
# ============================================================

def validate(obj: Any) -> list[str]:
    """校验一条契约输出，返回错误列表（空列表 = 合法）。

    刻意不引入 jsonschema 依赖：契约很小，手写校验既零依赖，
    又能给出比通用库更具体的中文错误信息。
    """
    errors: list[str] = []
    if not isinstance(obj, dict):
        return ["顶层不是 JSON 对象"]

    unknown = set(obj) - {"scene", "intent", "complexity", "confidence", "need_cloud", "slots", "reason"}
    if unknown:
        errors.append(f"出现未定义字段：{sorted(unknown)}")

    scene = obj.get("scene")
    if scene not in SCENES:
        errors.append(f"scene 非法：{scene!r}")
        return errors  # scene 错了后面无法继续校验

    intent = obj.get("intent")
    if intent not in INTENTS_BY_SCENE[scene]:
        errors.append(f"intent 与 scene 不匹配：{intent!r} 不属于 {scene}")

    for field in ("complexity", "confidence"):
        value = obj.get(field)
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            errors.append(f"{field} 必须是数字")
        elif not 0.0 <= float(value) <= 1.0:
            errors.append(f"{field} 超出 [0,1]：{value}")

    if not isinstance(obj.get("need_cloud"), bool):
        errors.append("need_cloud 必须是布尔值")

    slots = obj.get("slots")
    if not isinstance(slots, dict):
        errors.append("slots 必须是对象")
        return errors

    schema_slots = SLOTS_BY_SCENE.get(scene, {})
    extra = set(slots) - set(schema_slots)
    if extra:
        errors.append(f"{scene} 场景出现未定义槽位：{sorted(extra)}")

    for key, (kind, required, spec) in schema_slots.items():
        if required and key not in slots:
            errors.append(f"缺少必填槽位 {key}")
            continue
        if key not in slots:
            continue
        value = slots[key]
        if kind == "array[string]":
            if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
                errors.append(f"槽位 {key} 必须是字符串数组")
            elif len(value) > 8:
                errors.append(f"槽位 {key} 超过 8 项")
        elif kind == "enum":
            if value not in spec:
                errors.append(f"槽位 {key} 取值非法：{value!r}")
        elif kind == "integer":
            if not isinstance(value, int) or isinstance(value, bool):
                errors.append(f"槽位 {key} 必须是整数")
        elif kind == "string":
            if not isinstance(value, str):
                errors.append(f"槽位 {key} 必须是字符串")

    return errors


def is_valid(obj: Any) -> bool:
    return not validate(obj)


def _slot_spec_lines(scene: str) -> str:
    slots = SLOTS_BY_SCENE.get(scene, {})
    if not slots:
        return "    （无槽位，slots 必须为空对象 {}）"
    lines = []
    for key, (kind, required, detail) in slots.items():
        mark = "必填" if required else "可选"
        if kind == "enum":
            values = " | ".join(str(v) for v in detail)
            lines.append(f'    - "{key}"（{mark}，枚举，只能取：{values}）')
        elif kind == "array[string]":
            lines.append(f'    - "{key}"（{mark}，字符串数组）：{detail}')
        elif kind == "integer":
            lines.append(f'    - "{key}"（{mark}，整数）：{detail}')
        else:
            lines.append(f'    - "{key}"（{mark}，字符串）：{detail}')
    return "\n".join(lines)


def contract_prompt(signals: str, utterance: str) -> str:
    """构造教师侧的任务提示。

    关键点：**槽位的允许取值必须逐条列出来**。第一版只列了槽位名，
    教师只能猜枚举值，结果 8 条样本全部因取值不合法被判失败 ——
    "契约要显式到模型能照抄的程度"，这是那次失败留下的教训。

    离线蒸馏与在线服务共用同一份契约定义（本模块），
    教师看到的任务描述与未来端侧模型要面对的任务完全一致，
    这样学生学到的分布才不会有偏移。
    """
    scene_blocks = []
    for scene in SCENES:
        intents = list(INTENTS_BY_SCENE[scene])
        scene_blocks.append(
            f'### scene = "{scene}"\n'
            f"  intent 只能取：{intents}\n"
            f"  slots 字段：\n{_slot_spec_lines(scene)}"
        )
    scenes_text = "\n\n".join(scene_blocks)

    return f"""你是移动端场景助手的决策核心。只做判断与槽位抽取，不做长文本创作。

## 环境信号
{signals}

## 用户输入
{utterance}

## 输出要求
严格输出**一个 JSON 对象**，不要任何解释、不要 Markdown 代码围栏。

顶层字段：
- scene：字符串，只能取 {list(SCENES)}
- intent：字符串，必须与 scene 对应（见下表）
- complexity：0~1 的浮点数，衡量"是否需要长推理或长生成"，越高越该上云
- confidence：0~1 的浮点数，你对本次判断的把握
- need_cloud：布尔值。长文生成、多步推理、需要外部知识的建议为 true
- slots：对象，**只能包含该 scene 下定义的槽位**，且枚举字段必须逐字使用下面列出的取值
- reason：不超过 60 字，说明判断依据

## 各 scene 的取值约束

{scenes_text}

再次强调：枚举值必须**逐字照抄**上面的取值，不要翻译成英文、不要使用同义词、
不要自行发明新取值。只输出 JSON。"""


def schema_text() -> str:
    return json.dumps(json_schema(), ensure_ascii=False, indent=2)


if __name__ == "__main__":  # 便于人工查看生成的 Schema
    print(schema_text())
