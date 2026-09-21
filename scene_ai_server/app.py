"""Mova-AI 云端服务入口。

与原版相比的几处结构性改动：
  ① 拆出 mova/ 子包：配置、厂商适配、提示词各自独立，app.py 只负责路由
  ② 新增 /health 与 /capabilities：App 的首启向导与首页状态卡依赖它们
  ③ 每个响应都带 meta（执行方式 / 模型 / 耗时 / 阶段拆解 / 路由原因）——
     这是 App 技术面板的唯一数据来源
  ④ 上传做**魔数嗅探**而不是听信 Content-Type，并限制体积
  ⑤ 关键路径全部改为结构化日志，不再 print 完整响应体
  ⑥ 大模型调用失败会重试，并映射成合适的 HTTP 状态码

启动：
    uvicorn app:app --host 0.0.0.0 --port 8000 --reload
接口文档：
    http://localhost:8000/docs
"""

from __future__ import annotations

import logging
import time
from io import BytesIO
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from mova import prompts
from mova.config import VERSION, ConfigError, load_settings
from mova.providers import OpenAICompatProvider, ProviderError, extract_json

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-7s | %(name)s | %(message)s",
)
logger = logging.getLogger("mova.app")

# ---------------------------------------------------------------
# 启动期配置：缺 Key 就直接给出可执行的提示，而不是等到第一次调用才 500
# ---------------------------------------------------------------
try:
    SETTINGS = load_settings(Path(__file__).with_name(".env"))
except ConfigError as exc:
    raise SystemExit(f"\n[Mova-AI] 服务无法启动：\n{exc}\n") from exc

PROVIDER = OpenAICompatProvider(SETTINGS)

#: 端侧执行器尚未接入时，所有请求的路由原因。与客户端 Router 的文案保持一致。
CLOUD_ONLY_REASON = "端侧执行器未接入，本次直连云端"

app = FastAPI(
    title="Mova-AI 云端服务",
    version=VERSION,
    description=(
        "移动端场景助手的云侧能力层。"
        "重推理与长生成在此完成，轻判断与结构化抽取由端侧小模型承担（规划中）。"
    ),
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=SETTINGS.allow_origins,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ===============================================================
# 契约
# ===============================================================

class ReadingTextRequest(BaseModel):
    content: str = Field(min_length=1, description="待总结的正文")


class ChatReplyRequest(BaseModel):
    context: str = Field(min_length=1, description="对方说的话")
    style: str = "自然"


class ChatRewriteRequest(BaseModel):
    text: str = Field(min_length=1, description="待改写的话")
    style: str = "礼貌"


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatContextRequest(BaseModel):
    messages: list[ChatMessage]
    goal: str = ""
    style: str = "自然"


# ===============================================================
# 工具
# ===============================================================

def build_meta(
    model: str | None,
    latency_ms: int,
    *,
    stages: dict[str, int] | None = None,
    confidence: float | None = None,
    route_reason: str = CLOUD_ONLY_REASON,
) -> dict[str, Any]:
    """统一构造响应中的 meta 段。全服务只有这一处生产 meta，口径不会漂。"""
    return {
        "executor": "cloud",
        "model": model,
        "latency_ms": latency_ms,
        "route_reason": route_reason,
        "stages": stages or {},
        "confidence": confidence,
    }


_MAGIC = (
    (b"\xff\xd8\xff", "image/jpeg", "jpeg"),
    (b"\x89PNG\r\n\x1a\n", "image/png", "png"),
    (b"%PDF", "application/pdf", "pdf"),
)


def sniff(data: bytes) -> tuple[str, str] | None:
    """按魔数判断真实类型，返回 (mime, kind)。"""
    for magic, mime, kind in _MAGIC:
        if data.startswith(magic):
            return mime, kind
    return None


async def read_upload(file: UploadFile, *, allow: tuple[str, ...]) -> tuple[bytes, str]:
    """读取并校验上传文件。

    刻意不信任客户端的 Content-Type：类型判断以文件头为准，
    体积上限由配置决定。这两条堵住的是"上传接口无校验"这个经典问题。
    """
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="上传的文件是空的")

    limit = SETTINGS.max_upload_mb * 1024 * 1024
    if len(data) > limit:
        raise HTTPException(
            status_code=413,
            detail=f"文件超过 {SETTINGS.max_upload_mb} MB 上限（当前 {len(data) / 1024 / 1024:.1f} MB）",
        )

    sniffed = sniff(data)
    if sniffed is None:
        raise HTTPException(
            status_code=415,
            detail="无法识别的文件类型（只支持 JPEG / PNG / PDF）",
        )
    mime, kind = sniffed
    if kind not in allow:
        raise HTTPException(status_code=415, detail=f"不支持的类型：{kind}（需要 {'/'.join(allow)}）")
    return data, mime


def parse_or_fallback(raw: str, fallback: dict[str, Any]) -> dict[str, Any]:
    """解析模型返回的 JSON；失败时返回兜底结构，保证前端永远拿得到可渲染数据。"""
    parsed = extract_json(raw)
    if isinstance(parsed, dict):
        return parsed
    logger.warning("模型未返回可解析的 JSON，已启用兜底（长度 %d）", len(raw))
    return fallback


def provider_error(exc: ProviderError) -> HTTPException:
    """把上游错误映射成对客户端有意义的状态码。"""
    status = exc.status_code or 502
    if status in (401, 403):
        return HTTPException(status_code=502, detail=f"服务端模型鉴权失败：{exc}")
    if status == 429:
        return HTTPException(status_code=503, detail="模型服务限流，请稍后重试")
    return HTTPException(status_code=502, detail=f"模型服务调用失败：{exc}")


# ===============================================================
# 元信息接口
# ===============================================================

@app.get("/health", summary="健康检查与模型信息")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "version": VERSION,
        "provider": SETTINGS.provider,
        "models": {
            "llm": SETTINGS.llm_model,
            "vision": SETTINGS.vision_model or "",
        },
        "edge": {
            "deployed": False,
            "name": None,
            "size_mb": None,
            "quantization": None,
        },
    }


@app.get("/capabilities", summary="能力清单（驱动客户端首页）")
async def capabilities() -> dict[str, Any]:
    """客户端首页的能力网格由此驱动，服务端增减场景时 App 不需要发版。"""
    return {
        "scenes": [
            {
                "id": "food",
                "name": "做饭助手",
                "desc": "拍食材，出菜谱",
                "emoji": "🍜",
                "available": True,
                "edge_ready": False,
            },
            {
                "id": "reading",
                "name": "阅读总结",
                "desc": "文本 / 图片 / PDF",
                "emoji": "📄",
                "available": True,
                "edge_ready": False,
            },
            {
                "id": "chat",
                "name": "聊天辅助",
                "desc": "帮你把话说好",
                "emoji": "💬",
                "available": True,
                "edge_ready": False,
            },
            {
                "id": "location",
                "name": "位置提示",
                "desc": "到了超市提醒你",
                "emoji": "📍",
                "available": False,
                "edge_ready": False,
                "unavailable_hint": "规划中",
            },
        ]
    }


# ===============================================================
# 做饭场景：两级流水线
# ===============================================================

@app.post("/analyze_image", summary="识别食材并生成菜谱")
async def analyze_image(file: UploadFile = File(...)) -> dict[str, Any]:
    """两级串联：视觉模型只负责识别，语言模型只负责生成。

    拆两级的原因：视觉模型在长输出里容易跑偏，而识别环节的 prompt 可以极简；
    生成环节拿到干净的食材清单后再做组织，两个环节的失败模式互不污染。

    meta.stages 会如实返回两段耗时，客户端据此展示"识别中 → 生成中"。
    """
    started = time.monotonic()
    image_bytes, _mime = await read_upload(file, allow=("jpeg", "png"))
    logger.info("收到食材图片：%s（%d KB）", file.filename, len(image_bytes) // 1024)

    try:
        t0 = time.monotonic()
        ingredients = PROVIDER.chat_vision(prompts.FOOD_RECOGNIZE, image_bytes)
        recognize_ms = int((time.monotonic() - t0) * 1000)
        logger.info("识别结果：%s", ingredients[:120])

        t1 = time.monotonic()
        raw = PROVIDER.chat_text(prompts.recipe_prompt(ingredients))
        generate_ms = int((time.monotonic() - t1) * 1000)
    except ProviderError as exc:
        raise provider_error(exc) from exc

    recipe = parse_or_fallback(
        raw,
        {
            "ingredients": [ingredients],
            "dish": "JSON 解析失败",
            "steps": [raw],
            "tips": "模型未按 JSON 输出",
        },
    )

    return {
        "scene": "food",
        "ingredients": _as_list(recipe.get("ingredients")) or _split_ingredients(ingredients),
        "dish": _as_text(recipe.get("dish")),
        "steps": _as_list(recipe.get("steps")),
        "tips": _as_text(recipe.get("tips")),
        "meta": build_meta(
            f"{SETTINGS.vision_model}+{SETTINGS.llm_model}",
            int((time.monotonic() - started) * 1000),
            stages={"recognize": recognize_ms, "generate": generate_ms},
        ),
    }


# ===============================================================
# 阅读场景：三种输入，同一套输出契约
# ===============================================================

@app.post("/reading/text", summary="总结文本")
async def reading_text(req: ReadingTextRequest) -> dict[str, Any]:
    started = time.monotonic()
    try:
        raw = PROVIDER.chat_text(prompts.reading_text_prompt(req.content))
    except ProviderError as exc:
        raise provider_error(exc) from exc

    data = parse_or_fallback(raw, {"summary": raw, "key_points": [], "qa_suggestion": ""})
    return {
        "scene": "reading_text",
        **_reading_payload(data),
        "meta": build_meta(SETTINGS.llm_model, int((time.monotonic() - started) * 1000)),
    }


@app.post("/reading/image", summary="总结截图")
async def reading_image(file: UploadFile = File(...)) -> dict[str, Any]:
    started = time.monotonic()
    image_bytes, _mime = await read_upload(file, allow=("jpeg", "png"))
    try:
        raw = PROVIDER.chat_vision(prompts.READING_IMAGE, image_bytes)
    except ProviderError as exc:
        raise provider_error(exc) from exc

    data = parse_or_fallback(raw, {"summary": raw, "key_points": [], "qa_suggestion": ""})
    return {
        "scene": "reading_image",
        **_reading_payload(data),
        "meta": build_meta(SETTINGS.vision_model, int((time.monotonic() - started) * 1000)),
    }


@app.post("/reading/pdf", summary="总结 PDF 前若干页")
async def reading_pdf(file: UploadFile = File(...)) -> dict[str, Any]:
    started = time.monotonic()
    pdf_bytes, _mime = await read_upload(file, allow=("pdf",))

    from pypdf import PdfReader  # 延迟导入：只有这条路径才需要

    try:
        reader = PdfReader(BytesIO(pdf_bytes))
        max_pages = min(SETTINGS.max_pdf_pages, len(reader.pages))
        full_text = "\n\n".join((reader.pages[i].extract_text() or "") for i in range(max_pages))
    except Exception as exc:  # pypdf 对损坏文件会抛各种异常
        logger.warning("PDF 解析失败：%s", exc)
        raise HTTPException(status_code=422, detail="PDF 解析失败，文件可能已损坏或受密码保护") from exc

    if not full_text.strip():
        raise HTTPException(status_code=422, detail="这个 PDF 没有可提取的文字（可能是扫描件，请改用截图识别）")

    try:
        raw = PROVIDER.chat_text(prompts.reading_pdf_prompt(full_text, max_pages))
    except ProviderError as exc:
        raise provider_error(exc) from exc

    data = parse_or_fallback(raw, {"summary": raw, "key_points": [], "qa_suggestion": ""})
    return {
        "scene": "reading_pdf",
        "page_count": max_pages,
        **_reading_payload(data),
        "meta": build_meta(SETTINGS.llm_model, int((time.monotonic() - started) * 1000)),
    }


# ===============================================================
# 聊天辅助
# ===============================================================

@app.post("/chat/reply", summary="生成一条回复建议")
async def chat_reply(req: ChatReplyRequest) -> dict[str, Any]:
    return await _chat(
        prompts.chat_reply_prompt(req.context, req.style), "chat", req.style
    )


@app.post("/chat/rewrite", summary="改写语气")
async def chat_rewrite(req: ChatRewriteRequest) -> dict[str, Any]:
    return await _chat(
        prompts.chat_rewrite_prompt(req.text, req.style), "chat_rewrite", req.style
    )


@app.post("/chat/context", summary="基于多轮上下文生成回复")
async def chat_context(req: ChatContextRequest) -> dict[str, Any]:
    if not req.messages:
        raise HTTPException(status_code=422, detail="messages 不能为空")
    dialogue = "\n".join(f"{m.role}：{m.content}" for m in req.messages)
    return await _chat(
        prompts.chat_context_prompt(dialogue, req.goal, req.style), "chat_context", req.style
    )


async def _chat(prompt: str, scene: str, style: str) -> dict[str, Any]:
    started = time.monotonic()
    try:
        raw = PROVIDER.chat_text(prompt, temperature=0.6)
    except ProviderError as exc:
        raise provider_error(exc) from exc

    data = parse_or_fallback(
        raw, {"reply": raw, "style": style, "alternatives": [], "explain": ""}
    )
    return {
        "scene": scene,
        "reply": _as_text(data.get("reply")),
        "style": _as_text(data.get("style")) or style,
        "alternatives": _as_list(data.get("alternatives")),
        "explain": _as_text(data.get("explain")),
        "meta": build_meta(SETTINGS.llm_model, int((time.monotonic() - started) * 1000)),
    }


# ===============================================================
# 小工具
# ===============================================================

def _as_text(value: Any) -> str:
    """把模型返回的任意结构归一化成一段纯文本。

    ⚠️ 这个函数是实测逼出来的：第一次跑通阅读场景时，qwen3-max 把 summary
    返回成了**数组**（`{"summary": ["端侧部署…", "第二个要点…"]}`），
    而旧代码写的是 `str(data.get("summary", ""))`，于是界面上直接显示了
    Python 列表的 repr —— 用户看到的"摘要"是一段带方括号和引号的 JSON。

    教训：模型对"字符串"和"字符串数组"的选择是不稳定的，
    契约校验只保证了顶层 JSON 合法，管不到字段的形态。
    归一化必须覆盖 str / list / dict 三种情况。
    """
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        return "".join(_as_text(item) for item in value)
    if isinstance(value, dict):
        return " ".join(_as_text(item) for item in value.values())
    return str(value).strip()


def _as_list(value: Any) -> list[str]:
    """把模型返回的任意结构归一化成字符串列表（会展开一层嵌套）。

    同样来自实测：模型有时给 `["要点1", "要点2"]`，有时给 `"要点1，要点2"`，
    偶尔给 `[["要点1"], ["要点2"]]`。
    """
    if value is None:
        return []
    if isinstance(value, list):
        result: list[str] = []
        for item in value:
            if isinstance(item, (list, dict)):
                result.extend(_as_list(item))
            else:
                text = str(item).strip()
                if text:
                    result.append(text)
        return result
    if isinstance(value, dict):
        return [text for text in (_as_text(item) for item in value.values()) if text]
    text = str(value).strip()
    return [text] if text else []


def _split_ingredients(text: str) -> list[str]:
    """视觉模型有时返回"番茄，鸡蛋"这种一行文本，这里兜底切开。"""
    for separator in ("，", ",", "、"):
        if separator in text:
            return [part.strip() for part in text.split(separator) if part.strip()]
    return [text.strip()] if text.strip() else []


def _reading_payload(data: dict[str, Any]) -> dict[str, Any]:
    return {
        "summary": _as_text(data.get("summary")),
        "key_points": _as_list(data.get("key_points")),
        "difficulty": _as_text(data.get("difficulty")) or None,
        "qa_suggestion": _as_text(data.get("qa_suggestion")) or None,
    }
