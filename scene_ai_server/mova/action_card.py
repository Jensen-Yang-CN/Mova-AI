"""通知行动卡的后处理。只展示有原文依据的行动，不替用户猜日期。"""

from __future__ import annotations

from datetime import datetime, timedelta
import re
from typing import Any


def normalize_action_card(data: dict[str, Any], source: str) -> dict[str, Any]:
    normalized = "".join(source.split())
    tasks = []
    candidates = data.get("tasks")
    for item in candidates[:5] if isinstance(candidates, list) else []:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title") or "").strip()[:80]
        evidence = str(item.get("evidence") or "").strip()[:120]
        if not title or not evidence or "".join(evidence.split()) not in normalized:
            continue
        due_at = item.get("due_at")
        if isinstance(due_at, str) and due_at.strip():
            try:
                parsed = datetime.fromisoformat(due_at)
                # 没有明确时区的日期不写进日历，避免错一天或错时。
                date = re.search(r"(20\d{2})[年/.-](\d{1,2})[月/.-](\d{1,2})日?", evidence)
                time = re.search(r"(\d{1,2}):(\d{2})", evidence)
                # 只有证据中同时出现完整年月日和具体时分才开放日历入口。
                if (parsed.tzinfo is None or parsed.utcoffset() != timedelta(hours=8)
                    or not date or not time
                    or tuple(map(int, date.groups())) != (parsed.year, parsed.month, parsed.day)
                    or tuple(map(int, time.groups())) != (parsed.hour, parsed.minute)):
                    due_at = None
            except ValueError:
                due_at = None
        else:
            due_at = None
        tasks.append({"title": title, "due_at": due_at, "evidence": evidence})
    return {"title": str(data.get("title") or "待办行动卡").strip()[:60], "tasks": tasks}
