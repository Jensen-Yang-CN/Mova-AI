"""把已锁定的模型契约导出为 Android 端可直接读取的小型资产。

模型 GGUF 留在本地私有目录，APK 中只放提示模板与大小/哈希清单。
提示模板由训练用 ``contract_prompt`` 生成，避免 Kotlin 手抄后发生漂移。
Qwen3 的非思考前缀与服务器冒烟使用的模板逐字一致。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scene_ai_server"))

from pipeline.contract import contract_prompt  # noqa: E402


ASSETS = ROOT / "SceneAi_App" / "app" / "src" / "main" / "assets"
METRICS = ROOT / "artifacts" / "release-v1" / "metrics.json"
SIGNALS_MARK = "__MOVA_SIGNALS__"
UTTERANCE_MARK = "__MOVA_UTTERANCE__"


def render(signals: str, utterance: str) -> str:
    """使用已与服务器 smoke_prompt.txt 对照过的 Qwen3 非思考格式。"""
    return (
        "<|im_start|>user\n"
        + contract_prompt(signals, utterance)
        + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    )


def main() -> None:
    metrics = json.loads(METRICS.read_text(encoding="utf-8"))
    model = metrics["q4_k_m_gguf"]
    template = render(SIGNALS_MARK, UTTERANCE_MARK)
    if template.count(SIGNALS_MARK) != 1 or template.count(UTTERANCE_MARK) != 1:
        raise ValueError("提示模板占位符数量错误，不能导出")

    ASSETS.mkdir(parents=True, exist_ok=True)
    # Windows 的 write_text 会把 LF 转成 CRLF；模型训练时使用的是 LF 字节。
    # 这里固定写 UTF-8 原始字节，避免 Android 推理提示与服务器冒烟提示漂移。
    (ASSETS / "edge_prompt.template.txt").write_bytes(template.encode("utf-8"))
    release = {
        "file": model["file"],
        "bytes": model["bytes"],
        "sha256": model["sha256"],
        "model_name": "Mova-AI Qwen3-0.6B / round1 epoch-3",
        "quantization": "Q4_K_M",
    }
    (ASSETS / "edge_model.json").write_text(
        json.dumps(release, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    # 本地若保存了服务器冒烟提示，验证导出公式没有偏移；不把案例原文入库。
    smoke = (
        ROOT / "scene_ai_server" / "data" / "model_output"
        / "qwen3-0.6b-release-v1" / "smoke_prompt.txt"
    )
    if smoke.exists():
        actual = smoke.read_text(encoding="utf-8")
        # 只从本地私有提示中拆出动态字段；公开脚本不硬编码评测话语。
        signals, remainder = actual.split("## 环境信号\n", 1)[1].split(
            "\n\n## 用户输入\n", 1
        )
        utterance = remainder.split("\n\n## 输出要求\n", 1)[0]
        if render(signals, utterance).encode("utf-8") != smoke.read_bytes():
            raise ValueError("导出的聊天模板与服务器冒烟提示字节不一致")
    print("已导出 Android 提示模板与 Q4 模型摘要")


if __name__ == "__main__":
    main()
