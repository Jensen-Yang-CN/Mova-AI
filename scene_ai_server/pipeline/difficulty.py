"""三信号客观难度评估。

**为什么不直接问教师"这条难不难"？**
因为模型自评难度的校准很差 —— 它给的分数更像"这句话读起来专不专业"，
而不是"这个任务对学生有多难"。所以难度由三个**可独立测量**的信号合成。

信号一 · 教师自一致性
    同一输入用较高温度采样 k 次，看结构化输出互相之间有多一致。
    一致率高 → 任务边界清晰 → 简单。

信号二 · 教师置信度
    取教师平均对数概率 exp(mean_logprob)。
    ⚠️ 设计文档里这一项本应是 **student probe loss（学生探针损失）** ——
    即"当前学生 checkpoint 在该样本上的 loss"。之所以现在用教师置信度替代：
    学生模型尚未训练，该信号暂时不可得。这是一个**有意的、被记录在案的替代**，
    学生 checkpoint 一旦存在，只需替换本模块的 `probe` 入参即可，接口不变。

信号三 · 跨教师分歧
    两个**不同来源**的教师（DeepSeek × Qwen）各自作答，按字段比对。
    分歧大 → 任务本身边界模糊 → 困难样本，同时也能筛出标注质量差的样本。

三个信号都对齐到「越大越难」，加权平均，缺失的信号自动重新归一化权重。
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any, Callable

from jsonx import extract_json

from .contract import contract_prompt, validate
from .seeds import Seed
from .teacher import TeacherClient, TeacherError

DEFAULT_WEIGHTS = {"consistency": 0.40, "confidence": 0.30, "disagreement": 0.30}


@dataclass
class DifficultyResult:
    seed_id: str
    scene: str
    intent: str
    complexity_hint: str
    #: 教师 A 的贪心输出（合法的那一份，作为这条样本的标签）
    label: dict[str, Any] | None
    #: 教师 B 的贪心输出，用于分歧度与后续交叉校验
    label_b: dict[str, Any] | None
    consistency: float | None = None
    teacher_confidence: float | None = None
    cross_agreement: float | None = None
    difficulty: float = 0.5
    valid: bool = False
    validation_errors: list[str] = field(default_factory=list)
    mean_logprob: float | None = None
    reason: str = ""

    def as_record(self) -> dict[str, Any]:
        return {
            "id": self.seed_id,
            "scene": self.scene,
            "intent": self.intent,
            "complexity_hint": self.complexity_hint,
            "valid": self.valid,
            "validation_errors": self.validation_errors,
            "signals": {
                "consistency": self.consistency,
                "teacher_confidence": self.teacher_confidence,
                "cross_agreement": self.cross_agreement,
                "mean_logprob": self.mean_logprob,
            },
            "weights": DEFAULT_WEIGHTS,
            "difficulty": round(self.difficulty, 4),
        }


# ============================================================
# 字段级比对
# ============================================================

def _hashable(value: Any) -> Any:
    if isinstance(value, list):
        return tuple(sorted(str(v) for v in value))
    return value


def field_pairs(obj: dict[str, Any] | None) -> set[tuple[str, Any]]:
    """把一份契约输出摊平成可比较的 (字段名, 值) 集合。"""
    if not isinstance(obj, dict):
        return set()
    pairs: set[tuple[str, Any]] = {
        ("scene", obj.get("scene")),
        ("intent", obj.get("intent")),
        ("need_cloud", obj.get("need_cloud")),
    }
    slots = obj.get("slots")
    if isinstance(slots, dict):
        for key, value in slots.items():
            pairs.add((f"slot.{key}", _hashable(value)))
    return {(k, v) for k, v in pairs if v is not None}


def field_agreement(a: dict[str, Any] | None, b: dict[str, Any] | None) -> float:
    """Jaccard 形式的字段一致度 ∈ [0,1]。"""
    pa, pb = field_pairs(a), field_pairs(b)
    if not pa and not pb:
        return 1.0
    union = pa | pb
    return len(pa & pb) / len(union) if union else 1.0


def pairwise_consistency(labels: list[dict[str, Any] | None]) -> float | None:
    """多次采样的平均两两一致度。样本不足 2 个时该信号不可用。"""
    valid = [item for item in labels if isinstance(item, dict)]
    if len(valid) < 2:
        return None
    scores = [
        field_agreement(valid[i], valid[j])
        for i in range(len(valid))
        for j in range(i + 1, len(valid))
    ]
    return sum(scores) / len(scores)


def combine(
    consistency: float | None,
    confidence: float | None,
    disagreement: float | None,
    weights: dict[str, float] | None = None,
) -> float:
    """三个信号 → 难度 ∈ [0,1]。缺失信号按剩余权重重新归一化。"""
    weights = weights or DEFAULT_WEIGHTS
    contributions = [
        (weights["consistency"], None if consistency is None else 1.0 - consistency),
        (weights["confidence"], None if confidence is None else 1.0 - confidence),
        (weights["disagreement"], None if disagreement is None else 1.0 - disagreement),
    ]
    total_weight = sum(w for w, v in contributions if v is not None)
    if total_weight <= 0:
        return 0.5
    return sum(w * v for w, v in contributions if v is not None) / total_weight


# ============================================================
# 主流程
# ============================================================

def parse_contract(text: str) -> tuple[dict[str, Any] | None, list[str]]:
    """解析并校验一份契约输出。"""
    parsed = extract_json(text)
    if not isinstance(parsed, dict):
        return None, ["无法从输出中解析出 JSON 对象"]
    errors = validate(parsed)
    return (parsed if not errors else None), errors


def estimate(
    seed: Seed,
    teacher_a: TeacherClient,
    teacher_b: TeacherClient,
    *,
    k_samples: int = 3,
    weights: dict[str, float] | None = None,
    probe: Callable[[Seed], float | None] | None = None,
) -> DifficultyResult:
    """对一条种子做完整的三信号难度评估。

    probe：可选的"学生探针"回调。学生 checkpoint 就绪后传入它，
    信号二就会自动从"教师置信度"切换为真正的 student probe loss。
    """
    prompt = contract_prompt(seed.signals, seed.utterance)
    result = DifficultyResult(
        seed_id=seed.id,
        scene=seed.scene,
        intent=seed.intent,
        complexity_hint=seed.complexity_hint,
        label=None,
        label_b=None,
    )

    # ---------- 教师 A 的贪心输出（作为标签） ----------
    try:
        greedy = teacher_a.complete(prompt, temperature=0.0, max_tokens=400, top_logprobs=8)
    except TeacherError as exc:
        result.reason = f"教师A调用失败：{exc}"
        return result

    label, errors = parse_contract(greedy.text)
    result.label = label
    result.valid = label is not None
    result.validation_errors = errors
    result.mean_logprob = greedy.mean_logprob
    result.teacher_confidence = greedy.confidence

    if not result.valid:
        result.reason = "教师A输出未通过契约校验"
        result.difficulty = 1.0  # 不合格样本按最难处理，后续会被过滤
        return result

    # ---------- 信号一：教师 A 自一致性 ----------
    samples: list[dict[str, Any] | None] = []
    for _ in range(max(0, k_samples - 1)):
        try:
            reply = teacher_a.complete(prompt, temperature=0.9, max_tokens=400)
        except TeacherError:
            continue
        parsed, _ = parse_contract(reply.text)
        samples.append(parsed)
    result.consistency = pairwise_consistency([label, *samples])

    # ---------- 信号二：学生探针（优先）或教师置信度 ----------
    if probe is not None:
        probe_loss = probe(seed)
        if probe_loss is not None:
            # 损失越大越难；用 1 减去归一化损失
            result.teacher_confidence = max(0.0, min(1.0, 1.0 - float(probe_loss)))
            result.reason = "信号二使用学生探针损失"

    # ---------- 信号三：跨教师分歧 ----------
    try:
        other = teacher_b.complete(prompt, temperature=0.0, max_tokens=400)
        label_b, _ = parse_contract(other.text)
        result.label_b = label_b
        if isinstance(label_b, dict):
            result.cross_agreement = field_agreement(label, label_b)
    except TeacherError as exc:
        result.reason = (result.reason + f"；教师B调用失败：{exc}").strip("；")

    # ---------- 合成 ----------
    result.difficulty = round(
        combine(
            result.consistency,
            result.teacher_confidence,
            result.cross_agreement,
            weights,
        ),
        4,
    )

    # 与种子的难度预设做对照，偏差过大时记录下来（不丢弃，只标注）
    expected = {"easy": 0.0, "medium": 0.5, "hard": 1.0}[seed.complexity_hint]
    if abs(result.difficulty - expected) > 0.45:
        note = f"实测难度 {result.difficulty:.2f} 与预设 {seed.complexity_hint} 偏差较大"
        result.reason = (result.reason + "；" + note).strip("；")

    return result


def effective_confidence(mean_logprob: float | None, token_count: int) -> float | None:
    """按 token 数衰减后的置信度（长回答天然平均对数概率更低）。

    保留此函数供后续校准阶段使用：直接拿 exp(mean_logprob) 当置信度会系统性
    低估长回答，需要按长度做一次校正。
    """
    if mean_logprob is None:
        return None
    return math.exp(mean_logprob) ** (1.0 / max(1, math.log10(token_count + 10)))
