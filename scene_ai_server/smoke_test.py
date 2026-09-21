"""Mova-AI 冒烟测试：不启动服务，直接验证「配置 + 厂商适配」这条链路是否通。

用法：
    cd scene_ai_server
    python smoke_test.py                    # 文本调用
    python smoke_test.py --vision           # 额外验证视觉调用（读取 tomato.jpg）

它刻意复用 mova/providers.py 里的同一个 provider —— 而不是像旧版那样
在测试脚本里再抄一份请求代码。**测试和线上走同一条路径，测出来的结果才有意义。**
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from mova.config import ConfigError, load_settings
from mova.providers import OpenAICompatProvider, ProviderError, extract_json

HERE = Path(__file__).parent


def main() -> int:
    parser = argparse.ArgumentParser(description="Mova-AI 冒烟测试")
    parser.add_argument("--vision", action="store_true", help="额外测试视觉模型（需 tomato.jpg）")
    args = parser.parse_args()

    try:
        settings = load_settings(HERE / ".env")
    except ConfigError as exc:
        print(f"[失败] 配置不完整：\n{exc}")
        return 2

    provider = OpenAICompatProvider(settings)
    print(f"[配置] 厂商={settings.provider}  端点={settings.base_url}")
    print(f"       文本模型={settings.llm_model}  视觉模型={settings.vision_model or '未配置'}")

    # ---------- 1. 文本调用 ----------
    try:
        answer = provider.chat_text("用一句话说明你是什么模型。")
    except ProviderError as exc:
        print(f"[失败] 文本调用失败：{exc}")
        return 1
    print(f"[通过] 文本调用：{answer[:80]}")

    # ---------- 2. 结构化输出 + JSON 兜底解析 ----------
    try:
        raw = provider.chat_text(
            '只输出 JSON：{"ok": true, "reason": "一句话"}', temperature=0.0
        )
    except ProviderError as exc:
        print(f"[失败] 结构化调用失败：{exc}")
        return 1
    parsed = extract_json(raw)
    if isinstance(parsed, dict):
        print(f"[通过] JSON 解析：{parsed}")
    else:
        print(f"[警告] 模型未返回可解析 JSON（兜底逻辑会接管）：{raw[:80]}")

    # ---------- 3. 视觉调用（可选） ----------
    if args.vision:
        image = HERE / "tomato.jpg"
        if not image.exists():
            print(f"[跳过] 未找到 {image}")
        elif not provider.supports_vision:
            print(f"[跳过] 厂商 {settings.provider} 未配置视觉模型")
        else:
            try:
                result = provider.chat_vision("识别图中的食材，用逗号分隔，不要解释。", image.read_bytes())
                print(f"[通过] 视觉调用：{result[:80]}")
            except ProviderError as exc:
                print(f"[失败] 视觉调用失败：{exc}")
                return 1

    print("\n[完成] 全部通过。可以启动服务：uvicorn app:app --host 0.0.0.0 --port 8000 --reload")
    return 0


if __name__ == "__main__":
    sys.exit(main())
