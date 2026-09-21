"""教师客户端：支持**双教师**与 **logprobs 捕获**。

两个关键能力：

① **双教师**：同时接入两个不同来源的教师（例如 DeepSeek 与 Qwen）。
   它们的答案分歧本身就是一种数据质量信号（见 difficulty.py 的信号三），
   这比让单个模型自评"我确定吗"可靠得多。

② **logprobs 捕获**：OpenAI 兼容接口可以返回每个生成 token 的 top-K 对数概率。
   这意味着**即使没有本地大模型，也能拿到教师的软标签（soft label）**，
   从而做真正的 logit 蒸馏，而不是只做"复制答案"的 response 蒸馏。
   本项目已在 DeepSeek 与 DashScope 上实测确认该字段可用（top_logprobs ≤ 20）。

零第三方依赖：只用 urllib + json。
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from mova.config import load_dotenv

RETRYABLE = {408, 409, 425, 429, 500, 502, 503, 504}


def _percentile(values: list[float], q: float) -> float:
    """线性插值分位数。样本很少时退化为最小值，避免索引越界。"""
    if not values:
        raise ValueError("空序列没有分位数")
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = q * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    frac = position - low
    return ordered[low] * (1 - frac) + ordered[high] * frac


@dataclass
class TeacherReply:
    text: str
    model: str
    teacher: str
    latency_ms: int
    usage: dict[str, Any] = field(default_factory=dict)
    #: 全文平均对数概率（越高越"确信"）
    mean_logprob: float | None = None
    #: 10 分位对数概率 —— 用来刻画"教师最没把握的那几处决策"
    p10_logprob: float | None = None
    #: 逐 token 的 top-K 分布，供 logit 蒸馏落盘
    token_logprobs: list[dict[str, Any]] = field(default_factory=list)

    @property
    def confidence(self) -> float | None:
        """把对数概率转成 [0,1] 的粗置信度（未经校准，只作数据筛选信号）。

        ⚠️ 这里用的是 **10 分位**而不是均值，原因来自第一次实测：
        契约输出是 JSON，大量 token 是 `{`、`"`、`scene` 这类高度可预测的结构符号，
        它们的对数概率接近 0，会把**均值**抬到很高且几乎不随样本变化 ——
        实测 8 条样本的难度全被判成 "easy"，该信号完全没有区分度。

        改用 10 分位后，衡量的是"教师在最没把握的那几处有多没把握"，
        这才对应我们真正关心的东西：这个任务的判断边界清不清晰。
        """
        if self.p10_logprob is not None:
            import math

            return round(math.exp(self.p10_logprob), 4)
        if self.mean_logprob is None:
            return None
        import math

        return round(math.exp(self.mean_logprob), 4)

    def soft_label_bytes(self) -> int:
        """估算软标签落盘体积，用于验证蒸馏缓存的空间预算。"""
        return len(json.dumps(self.token_logprobs, ensure_ascii=False).encode("utf-8"))


class TeacherError(RuntimeError):
    pass


class TeacherClient:
    def __init__(
        self,
        name: str,
        base_url: str,
        api_key: str,
        model: str,
        *,
        timeout: int = 90,
        max_retries: int = 3,
    ) -> None:
        self.name = name
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout
        self.max_retries = max_retries

    def complete(
        self,
        prompt: str,
        *,
        system: str | None = None,
        temperature: float = 0.0,
        max_tokens: int = 512,
        top_logprobs: int = 0,
    ) -> TeacherReply:
        messages: list[dict[str, str]] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})

        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        if top_logprobs > 0:
            payload["logprobs"] = True
            payload["top_logprobs"] = min(top_logprobs, 20)

        started = time.monotonic()
        data = self._post(payload)
        latency_ms = int((time.monotonic() - started) * 1000)

        try:
            choice = data["choices"][0]
            text = choice["message"]["content"] or ""
        except (KeyError, IndexError) as exc:
            raise TeacherError(f"{self.name} 返回结构异常：{str(data)[:200]}") from exc

        mean_logprob: float | None = None
        p10_logprob: float | None = None
        token_logprobs: list[dict[str, Any]] = []
        lp = choice.get("logprobs")
        if lp and lp.get("content"):
            values: list[float] = []
            for item in lp["content"]:
                logprob = item.get("logprob")
                if isinstance(logprob, (int, float)):
                    values.append(float(logprob))
                token_logprobs.append(
                    {
                        "token": item.get("token", ""),
                        "logprob": logprob,
                        "top": [
                            {"token": t.get("token", ""), "logprob": t.get("logprob")}
                            for t in (item.get("top_logprobs") or [])
                        ],
                    }
                )
            if values:
                mean_logprob = sum(values) / len(values)
                p10_logprob = _percentile(values, 0.10)

        return TeacherReply(
            text=text.strip(),
            model=self.model,
            teacher=self.name,
            latency_ms=latency_ms,
            usage=data.get("usage") or {},
            mean_logprob=mean_logprob,
            p10_logprob=p10_logprob,
            token_logprobs=token_logprobs,
        )

    def _post(self, payload: dict[str, Any]) -> dict[str, Any]:
        url = f"{self.base_url}/chat/completions"
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        last: Exception | None = None
        for attempt in range(self.max_retries + 1):
            request = urllib.request.Request(url, data=body, headers=headers, method="POST")
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    return json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as exc:
                detail = ""
                try:
                    detail = exc.read().decode("utf-8", "ignore")[:300]
                except Exception:  # noqa: BLE001
                    pass
                if exc.code in RETRYABLE and attempt < self.max_retries:
                    last = exc
                    time.sleep(0.8 * (2**attempt))
                    continue
                raise TeacherError(
                    f"{self.name} HTTP {exc.code}：{detail}"
                ) from exc
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                last = exc
                if attempt < self.max_retries:
                    time.sleep(0.8 * (2**attempt))
                    continue
                raise TeacherError(f"{self.name} 网络错误：{exc}") from exc

        raise TeacherError(f"{self.name} 重试耗尽：{last}")


# ---------------------------------------------------------------
# 默认双教师
# ---------------------------------------------------------------

DASHSCOPE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEEPSEEK_URL = "https://api.deepseek.com"


def build_teachers(
    env_file: Path | None = None,
    *,
    model_a: str | None = None,
    model_b: str | None = None,
    logprob_k: int = 8,
) -> tuple[TeacherClient, TeacherClient, int]:
    """构造默认的两名教师。

    默认组合：DeepSeek（教师 A）+ 千问 qwen-plus（教师 B）。
    两个来源不同，分歧信号才有意义；同源同族模型的分歧会偏小。

    若要接入自托管的 Qwen3.8-27B，把 URL 指向 vLLM 的 OpenAI 端点即可 ——
    本客户端只依赖 OpenAI 兼容协议。
    """
    import os

    if env_file is not None:
        load_dotenv(env_file)

    deepseek_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    dashscope_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()

    if not deepseek_key or not dashscope_key:
        raise SystemExit(
            "缺少教师 API Key。请设置环境变量或写入 scene_ai_server/.env：\n"
            "  DEEPSEEK_API_KEY=sk-xxxx\n"
            "  DASHSCOPE_API_KEY=sk-xxxx"
        )

    teacher_a = TeacherClient(
        "deepseek",
        os.environ.get("MOVA_TEACHER_A_URL", DEEPSEEK_URL),
        deepseek_key,
        model_a or os.environ.get("MOVA_TEACHER_A_MODEL", "deepseek-chat"),
    )
    teacher_b = TeacherClient(
        "qwen",
        os.environ.get("MOVA_TEACHER_B_URL", DASHSCOPE_URL),
        dashscope_key,
        model_b or os.environ.get("MOVA_TEACHER_B_MODEL", "qwen-plus"),
    )
    return teacher_a, teacher_b, logprob_k
