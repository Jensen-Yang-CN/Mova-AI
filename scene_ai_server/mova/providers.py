"""模型厂商适配层：全项目唯一发 HTTP 请求的地方。

三个设计决定：
  ① **只实现一次 OpenAI 兼容协议**。DashScope 兼容模式、DeepSeek、以及绝大多数
     自建推理服务（vLLM / SGLang 的 OpenAI server）都说这套协议，所以一个类就够，
     不需要为每家厂商写一份客户端。
  ② **失败重试放在这一层**（指数退避 + 只对可重试状态码重试），
     上层接口不再关心网络抖动。
  ③ **JSON 提取做成独立函数并做多层兜底**。小模型/长输出不按格式返回是常态，
     与其在校验层反复失败，不如在解析层就尽量救回来。
"""

from __future__ import annotations

import base64
import logging
import time
from typing import Any

import requests

from jsonx import extract_json  # noqa: F401  （对外重新导出，保持调用方接口不变）
from .config import Settings

logger = logging.getLogger("mova.provider")

RETRYABLE_STATUS = {408, 409, 425, 429, 500, 502, 503, 504}


class ProviderError(RuntimeError):
    """上游模型调用失败。携带状态码，便于上层映射成合适的 HTTP 响应。"""

    def __init__(self, message: str, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class OpenAICompatProvider:
    """任何 OpenAI 兼容端点的客户端。"""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.name = settings.provider

    # ---------- 公开接口 ----------

    @property
    def supports_vision(self) -> bool:
        return bool(self.settings.vision_model)

    def chat_text(self, prompt: str, *, system: str | None = None, temperature: float = 0.3) -> str:
        messages: list[dict[str, Any]] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        return self._complete(messages, temperature=temperature)

    def chat_vision(
        self,
        prompt: str,
        image_bytes: bytes,
        *,
        mime: str = "image/jpeg",
        temperature: float = 0.2,
    ) -> str:
        if not self.supports_vision:
            raise ProviderError(
                f"当前厂商 {self.name} 未配置视觉模型，无法处理图片。"
                "请设置 MOVA_VISION_MODEL，或切换到支持视觉的厂商。"
            )
        image_b64 = base64.b64encode(image_bytes).decode("utf-8")
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{image_b64}"}},
                ],
            }
        ]
        return self._complete(messages, temperature=temperature)

    # ---------- 内部 ----------

    def _complete(self, messages: list[dict[str, Any]], *, temperature: float) -> str:
        payload = {
            "model": self.settings.llm_model,
            "messages": messages,
            "temperature": temperature,
        }
        # 视觉请求要用视觉模型
        if any(isinstance(m.get("content"), list) for m in messages):
            payload["model"] = self.settings.vision_model

        data = self._post("/chat/completions", payload)
        try:
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError) as exc:
            raise ProviderError(f"上游返回结构异常：{str(data)[:300]}") from exc
        return _normalize_content(content)

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        url = self.settings.base_url.rstrip("/") + path
        headers = {
            "Authorization": f"Bearer {self.settings.api_key}",
            "Content-Type": "application/json",
        }
        last_error: Exception | None = None

        for attempt in range(self.settings.max_retries + 1):
            try:
                response = requests.post(
                    url, headers=headers, json=payload, timeout=self.settings.request_timeout
                )
            except requests.RequestException as exc:
                last_error = exc
                if attempt < self.settings.max_retries:
                    self._backoff(attempt, f"网络异常：{exc}")
                    continue
                raise ProviderError(f"无法连接模型服务：{exc}") from exc

            if response.status_code in RETRYABLE_STATUS and attempt < self.settings.max_retries:
                self._backoff(attempt, f"HTTP {response.status_code}")
                continue

            if response.status_code >= 400:
                # 只记录前 400 字符，避免把完整响应体（可能含用户内容）写进日志
                logger.warning("上游返回 %s：%s", response.status_code, response.text[:400])
                raise ProviderError(
                    f"模型服务返回 {response.status_code}：{response.text[:300]}",
                    status_code=response.status_code,
                )

            try:
                return response.json()
            except ValueError as exc:
                raise ProviderError("模型服务返回的不是合法 JSON") from exc

        raise ProviderError(f"重试 {self.settings.max_retries} 次后仍失败：{last_error}")

    @staticmethod
    def _backoff(attempt: int, reason: str) -> None:
        delay = 0.8 * (2**attempt)
        logger.info("调用失败（%s），%.1fs 后重试", reason, delay)
        time.sleep(delay)


def _normalize_content(content: Any) -> str:
    """兼容 content 为字符串 / 结构化数组两种形态。"""
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text") or item.get("content") or ""
                if text:
                    parts.append(str(text))
            elif isinstance(item, str):
                parts.append(item)
        return "".join(parts).strip()
    return str(content).strip()
