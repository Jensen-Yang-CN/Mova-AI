"""零依赖的 JSON 提取工具。

放在顶层而不是某个子包里，是因为**在线服务与离线流水线都要用它**：
    - mova/providers.py   在线：模型不按格式返回时兜底
    - pipeline/*          离线：解析教师输出

语言模型不按 JSON 输出是常态，与其在校验层反复失败，不如在解析层尽量救回来。
四层兜底：直接解析 → 去 Markdown 围栏 → 括号平衡扫描 → 修尾随逗号。
"""

from __future__ import annotations

import json
import re
from typing import Any

_JSON_FENCE = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL)


def extract_json(text: str) -> Any | None:
    """尽力从模型输出里提取一个 JSON 值；全部失败返回 None（不抛异常）。"""
    if not text:
        return None

    candidates: list[str] = [text.strip()]
    candidates.extend(match.strip() for match in _JSON_FENCE.findall(text))

    balanced = first_balanced_object(text)
    if balanced:
        candidates.append(balanced)

    # 尾随逗号是最常见的格式瑕疵，单独修一轮
    candidates.extend(re.sub(r",(\s*[}\]])", r"\1", candidate) for candidate in list(candidates))

    for candidate in candidates:
        if not candidate:
            continue
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            continue
    return None


def first_balanced_object(text: str) -> str | None:
    """扫描出第一个括号平衡的 JSON 对象，正确跳过字符串内的括号与转义。"""
    start = text.find("{")
    if start < 0:
        return None
    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    return None
