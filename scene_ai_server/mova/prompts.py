"""提示词构建：全项目唯一写 prompt 的地方。

把 prompt 集中在一个文件，好处是**离线蒸馏流水线可以直接复用在线服务的同一批
prompt** —— 教师看到的任务描述与线上完全一致，蒸馏出来的学生才不会有分布偏移。
"""

from __future__ import annotations

# ============================================================
# 端侧能力契约
# ------------------------------------------------------------
# 这是 docs/02 §1 定义的"能力契约"在代码里的落地：
# 端侧 SLM 只需要学「场景判断 → 意图分类 → 难度评估 → 路由建议 → 槽位抽取」，
# 长文本生成与复杂推理仍然交给云端。
#
# 在线服务目前不使用它（端侧尚未部署），但离线流水线会用**同一份**契约
# 去构造训练数据，从而保证教师与学生面对同一个任务定义。
# ============================================================
EDGE_CONTRACT_SYSTEM = """你是一个移动端场景助手的决策核心。
只做判断与抽取，不做长文本创作。严格输出 JSON，不要任何解释。"""

EDGE_CONTRACT_TEMPLATE = """根据下面的用户输入与环境信号，输出决策 JSON：

环境信号：{signals}
用户输入：{utterance}

输出结构（字段不可增删）：
{{
  "scene": "food | reading | chat | location | none",
  "intent": "diet_advice | summarize | reply_suggest | rewrite | none",
  "complexity": 0.0,
  "confidence": 0.0,
  "need_cloud": true,
  "slots": {{}}
}}

判断规则：
- complexity 为 0~1，衡量"是否需要长推理或长生成"，越高越该上云
- need_cloud 与 complexity 一致：复杂任务为 true
- slots 只放该场景必需的短字段（如食材名、文本长度）"""


def edge_contract_prompt(signals: str, utterance: str) -> str:
    return EDGE_CONTRACT_TEMPLATE.format(signals=signals, utterance=utterance)


# ============================================================
# 在线能力
# ============================================================

FOOD_RECOGNIZE = "请识别图片中的可食用食材，用中文名称，用逗号分隔，不要解释。"


def recipe_prompt(ingredients: str) -> str:
    return f"""我现在有这些食材：{ingredients}
请推荐一道最合适的家常菜，严格返回 JSON，不要解释：

{{
  "ingredients": ["用到的食材"],
  "dish": "菜名",
  "steps": ["步骤一", "步骤二", "步骤三"],
  "tips": "一条实用小贴士"
}}"""


def reading_text_prompt(content: str) -> str:
    return f"""你是一个专业的中文阅读理解助手。请阅读以下内容：

----------------
{content}
----------------

用【JSON 格式】返回（严格返回 JSON，不要多余解释）：

{{
  "summary": "用不超过 5 句话总结全文",
  "key_points": ["3~5 条重点"],
  "difficulty": "简单 / 中等 / 偏难",
  "qa_suggestion": "一个适合继续追问的问题"
}}"""


READING_IMAGE = (
    "请阅读图片中显示的内容（网页、App、文章等），做一份简明扼要的总结，"
    '只输出 JSON：{"summary": "...", "key_points": ["..."], "qa_suggestion": "..."}'
)


def reading_pdf_prompt(full_text: str, page_count: int) -> str:
    return f"""你是一个 PDF 阅读助手。下面是文档前 {page_count} 页的文字，请做整体阅读理解。

正文：
{full_text}

只输出 JSON：
{{
  "summary": "几句话总结整段内容",
  "key_points": ["要点1", "要点2", "要点3"],
  "qa_suggestion": "一条适合继续提问的问题"
}}"""


def chat_reply_prompt(context: str, style: str) -> str:
    return f"""你是一个中文聊天助手，根据对方的话帮我生成合适的回复。

对方说的话：
{context}

要求：
- 回复风格：{style}
- 给出 1 条推荐回复、2 条备选回复
- 简要说明为什么这样回

只输出 JSON：
{{
  "reply": "",
  "style": "{style}",
  "alternatives": ["", ""],
  "explain": ""
}}"""


def chat_rewrite_prompt(text: str, style: str) -> str:
    return f"""请把下面这句话改写为「{style}」风格：

原句：{text}

要求：不改变原意，更符合社交表达习惯。

只输出 JSON：
{{
  "reply": "",
  "style": "{style}",
  "alternatives": ["", ""],
  "explain": ""
}}"""


def chat_context_prompt(dialogue: str, goal: str, style: str) -> str:
    goal_line = f"目标：{goal}" if goal else "目标：自然地推进对话"
    return f"""你是一个聊天助手，请基于以下对话历史生成下一句合适回复。

对话记录：
{dialogue}

{goal_line}
风格：{style}

只输出 JSON：
{{
  "reply": "",
  "style": "{style}",
  "alternatives": ["", ""],
  "explain": ""
}}"""
