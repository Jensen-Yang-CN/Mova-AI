"""配置：全项目唯一读取环境变量的地方。

支持三种厂商（都走 OpenAI 兼容协议）：
    dashscope  阿里云百炼（qwen3-max / qwen-vl-max）
    deepseek   DeepSeek 官方
    自定义      任何 OpenAI 兼容端点，用 MOVA_BASE_URL 指定

同时兼容旧的 DASHSCOPE_API_KEY 变量名，避免破坏已有部署。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

VERSION = "2.0.0"

_PROVIDER_DEFAULTS = {
    "dashscope": {
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "llm": "qwen3-max",
        "vision": "qwen-vl-max",
        "key_env": "DASHSCOPE_API_KEY",
    },
    "deepseek": {
        "base_url": "https://api.deepseek.com",
        "llm": "deepseek-chat",
        "vision": None,  # DeepSeek 目前没有视觉模型
        "key_env": "DEEPSEEK_API_KEY",
    },
}


def load_dotenv(path: Path) -> None:
    """极简 .env 解析（避免为一行代码引入额外依赖）。

    只处理 KEY=VALUE 形式，支持 # 注释与两侧引号，已存在的环境变量不覆盖。
    """
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


@dataclass(frozen=True)
class Settings:
    provider: str
    api_key: str
    base_url: str
    llm_model: str
    vision_model: str | None
    request_timeout: int = 60
    max_retries: int = 2
    max_upload_mb: int = 12
    max_pdf_pages: int = 3
    allow_origins: list[str] = field(default_factory=lambda: ["*"])

    @property
    def display_name(self) -> str:
        return self.provider


class ConfigError(RuntimeError):
    """配置缺失时抛出，由 app.py 转成清晰的启动提示。"""


def load_settings(env_file: Path | None = None) -> Settings:
    if env_file is not None:
        load_dotenv(env_file)

    provider = os.environ.get("MOVA_PROVIDER", "dashscope").strip().lower()
    defaults = _PROVIDER_DEFAULTS.get(provider)
    if defaults is None:
        # 自定义 OpenAI 兼容端点
        defaults = {
            "base_url": os.environ.get("MOVA_BASE_URL", "").strip(),
            "llm": "gpt-4o-mini",
            "vision": None,
            "key_env": "MOVA_API_KEY",
        }

    api_key = (
        os.environ.get("MOVA_API_KEY", "").strip()
        or os.environ.get(defaults["key_env"], "").strip()
    )
    base_url = os.environ.get("MOVA_BASE_URL", "").strip() or defaults["base_url"]

    if not api_key:
        raise ConfigError(
            f"未检测到 {provider} 的 API Key。\n"
            f"  请设置环境变量 {defaults['key_env']}（或统一的 MOVA_API_KEY），\n"
            f"  也可以写入 scene_ai_server/.env 文件（已在 .gitignore 中）。\n"
            f"  示例：MOVA_PROVIDER={provider}\\n"
            f"        {defaults['key_env']}=sk-xxxx"
        )
    if not base_url:
        raise ConfigError("未配置模型服务地址（MOVA_BASE_URL）。")

    return Settings(
        provider=provider,
        api_key=api_key,
        base_url=base_url,
        llm_model=os.environ.get("MOVA_LLM_MODEL", "").strip() or defaults["llm"],
        vision_model=os.environ.get("MOVA_VISION_MODEL", "").strip() or defaults["vision"],
        request_timeout=int(os.environ.get("MOVA_TIMEOUT", "60")),
        max_retries=int(os.environ.get("MOVA_MAX_RETRIES", "2")),
        max_upload_mb=int(os.environ.get("MOVA_MAX_UPLOAD_MB", "12")),
        max_pdf_pages=int(os.environ.get("MOVA_MAX_PDF_PAGES", "3")),
    )
