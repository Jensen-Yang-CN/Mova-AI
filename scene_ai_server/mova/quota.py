"""公网演示的进程内请求上限，防止开放隧道触发无限量付费调用。"""

from __future__ import annotations

import time
from collections import deque
from threading import Lock


class RequestQuota:
    """在单个 Uvicorn 进程内限制每分钟和滚动 24 小时的请求数。"""

    def __init__(self, per_minute: int, per_day: int):
        if per_minute < 1 or per_day < 1:
            raise ValueError("公网请求上限必须是正整数")
        self.per_minute = per_minute
        self.per_day = per_day
        self._minute: deque[float] = deque()
        self._day: deque[float] = deque()
        self._lock = Lock()

    def allow(self, now: float | None = None) -> bool:
        """原子检查并占用一个请求名额；拒绝时不消耗名额。"""
        moment = time.monotonic() if now is None else now
        with self._lock:
            while self._minute and moment - self._minute[0] >= 60:
                self._minute.popleft()
            while self._day and moment - self._day[0] >= 24 * 60 * 60:
                self._day.popleft()
            if len(self._minute) >= self.per_minute or len(self._day) >= self.per_day:
                return False
            self._minute.append(moment)
            self._day.append(moment)
            return True
