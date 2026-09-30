"""教师端点探针 v2：打印模型的**完整原始响应**（含 reasoning_content 等全部字段）。

v1 发现关键线索：content 为空，但 completion_tokens 顶满上限（400）——
强烈怀疑是思考型模型把全部 token 预算花在"思考"上，思考内容放在独立字段。
本版本每个样本发 3 次请求，一次定位问题与解法：

    A. 长提示（同流水线）+ max_tokens=2048
       → 看思考用更多预算后，content 里能不能出 JSON
    B. 短提示对照 + max_tokens=2048
       → 排除"长提示绕晕模型"的可能
    C. 长提示 + chat_template_kwargs={"enable_thinking": false} + max_tokens=2048
       → 若端点支持直接关思考：又快又干净（这是期望的正式解法）

用法（在仓库根目录执行）：python tools/probe_teacher.py [样本数]     # 默认 3
注意：思考型模型单次调用可能 10~60 秒，3 样本共 9 次请求，预留 5~15 分钟。
只读：不写缓存、不碰 data/。
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scene_ai_server"))

from mova.config import load_dotenv  # noqa: E402

load_dotenv(REPO / "scene_ai_server" / ".env")

from jsonx import extract_json  # noqa: E402
from pipeline import contract, seeds, teacher  # noqa: E402

SHORT_PROMPT = (
    "你是判断引擎。对用户输入只输出一个 JSON 对象（不要解释、不要代码围栏），字段：\n"
    "- scene: food / reading / chat / location / none\n"
    "- intent: 字符串，须与 scene 匹配\n"
    "- complexity: 0~1 浮点数\n"
    "- confidence: 0~1 浮点数\n"
    "- need_cloud: 布尔值\n"
    "- slots: 对象（无槽位时为空对象 {{}}）\n"
    "- reason: 字符串，60 字以内\n\n"
    "用户输入：{utterance}"
)

MAX_PRINT = 800


def _clip(s: str, n: int) -> str:
    return s if len(s) <= n else s[:n] + f"\n...（截断，原文共 {len(s)} 字）"


def _verdict(text: str) -> str:
    if not text or not text.strip():
        return "✗ content 为空（无内容可解析）"
    parsed = extract_json(text)
    if not isinstance(parsed, dict):
        return "✗ 无法解析 JSON"
    errs = contract.validate(parsed)
    return "✓ 通过契约" if not errs else "△ JSON 可解析但违反契约：" + "；".join(errs[:4])


def _raw_call(t, title: str, prompt: str, extra: dict | None = None, max_tokens: int = 2048) -> None:
    payload: dict = {
        "model": t.model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.0,
        "max_tokens": max_tokens,
    }
    if extra:
        payload.update(extra)
    print(f"--- {title} ---")
    print(f"额外请求字段：{extra or '无'}")
    started = time.monotonic()
    try:
        data = t._post(payload)  # 复用 TeacherClient 的重试/鉴权，但保留完整响应
    except teacher.TeacherError as exc:
        print(f"请求失败：{exc}\n")
        return
    ms = int((time.monotonic() - started) * 1000)
    usage = data.get("usage") or {}
    print(f"延迟 {ms} ms，usage={usage}")
    try:
        choice = data["choices"][0]
    except (KeyError, IndexError):
        print(f"响应结构异常：{json.dumps(data, ensure_ascii=False)[:400]}\n")
        return
    msg = choice.get("message") or {}
    print(f"finish_reason: {choice.get('finish_reason')}")
    print(f"message 字段：{sorted(msg.keys())}")
    for key in ("reasoning_content", "reasoning", "thinking"):
        val = msg.get(key)
        if val:
            print(f"[{key}] {len(str(val))} 字：{_clip(str(val), 300)}")
    content = msg.get("content") or ""
    print(f"[content] {len(content)} 字")
    if content:
        print(_clip(content, MAX_PRINT))
    print(f"判定：{_verdict(content)}\n")


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    teacher_a, _teacher_b, _k = teacher.build_teachers()
    print(f"探测对象：{teacher_a.name} @ {teacher_a.base_url}  model={teacher_a.model}\n")

    all_seeds = seeds.generate_seeds(limit=30, seed=20260921)
    picked: list[seeds.Seed] = []
    seen: set[str] = set()
    for s in all_seeds:
        if s.scene not in seen:
            picked.append(s)
            seen.add(s.scene)
    picked = (picked * 3)[:n]

    for i, seed in enumerate(picked, 1):
        print(f"########## 样本 {i}/{len(picked)}  scene={seed.scene}  intent={seed.intent} ##########")
        print(f"用户输入：{seed.utterance}\n")

        long_prompt = contract.contract_prompt(seed.signals, seed.utterance)
        _raw_call(teacher_a, "A. 长提示，max_tokens=2048", long_prompt)
        _raw_call(teacher_a, "B. 短提示对照，max_tokens=2048", SHORT_PROMPT.format(utterance=seed.utterance))
        _raw_call(
            teacher_a,
            "C. 长提示 + enable_thinking=false（期望的正式解法）",
            long_prompt,
            extra={"chat_template_kwargs": {"enable_thinking": False}},
        )

    print("========== 汇总阅读方法 ==========")
    print("- 看 message 字段里有没有 reasoning_content：有 = 确认是思考型模型")
    print("- A/B 的 finish_reason：stop=自然结束，length=又顶满预算（思考更长）")
    print("- C 若判定 ✓ 通过契约 → 正式解法就是给所有请求加 enable_thinking=false")
    print("- 原始输出可能包含内部地址与模型响应；对外分享前先脱敏")


if __name__ == "__main__":
    main()
