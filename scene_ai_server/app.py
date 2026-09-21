import uvicorn

print("✅ app.py 已被加载")

from fastapi import FastAPI, UploadFile, File, HTTPException
import base64
import json
import os
import requests
from pydantic import BaseModel
from pypdf import PdfReader

# 启动命令（端口需与 Android 端 BASE_URL 保持一致）：
# uvicorn app:app --host 0.0.0.0 --port 8000 --reload
# 接口文档：http://localhost:8000/docs
app = FastAPI()

# ⚠️ 安全约定：API Key 一律从环境变量读取，禁止硬编码进源码。
# 请到阿里云百炼（DashScope）控制台申请 Key，然后设置环境变量：
#   PowerShell : $env:DASHSCOPE_API_KEY="sk-xxxx"
#   CMD        : set DASHSCOPE_API_KEY=sk-xxxx
#   Bash / zsh : export DASHSCOPE_API_KEY="sk-xxxx"
#   也可写入本目录的 .env 文件（已在 .gitignore 中，不会被提交）
DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "").strip()
if not DASHSCOPE_API_KEY:
    raise SystemExit(
        "❌ 未检测到环境变量 DASHSCOPE_API_KEY，服务无法启动。\n"
        "   请先到阿里云百炼控制台申请 API Key，然后设置环境变量，例如：\n"
        '     PowerShell : $env:DASHSCOPE_API_KEY="sk-xxxx"\n'
        '     Bash       : export DASHSCOPE_API_KEY="sk-xxxx"\n'
        "   设置完成后重新运行 uvicorn。"
    )

BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

VISION_MODEL = "qwen-vl-max"
LLM_MODEL = "qwen3-max"

HEADERS = {
    "Authorization": f"Bearer {DASHSCOPE_API_KEY}",
    "Content-Type": "application/json"
}
# ---------- 数据模型 ----------
class ReadingTextRequest(BaseModel):
    content: str
class ChatReplyRequest(BaseModel):
    context: str
    style: str = "自然"

class ChatRewriteRequest(BaseModel):
    text: str
    style: str = "礼貌"

class ChatContextRequest(BaseModel):
    messages: list
    goal: str = ""
    style: str = "自然"

# ---------- 视觉模型：识别食材 ----------
def recognize_food(image_bytes: bytes) -> str:
    image_b64 = base64.b64encode(image_bytes).decode("utf-8")
    payload = {
        "model": VISION_MODEL ,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": "请识别图片中的可食用食材，用中文名称，用逗号分隔，不要解释。"
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/jpeg;base64,{image_b64}"
                        }
                    }
                ]
            }
        ]
    }

    resp = requests.post(BASE_URL, headers=HEADERS, json=payload, timeout=30)
    print("VL status:", resp.status_code)
    print("VL body:", resp.text)
    if resp.status_code != 200:
        raise HTTPException(status_code=500, detail=resp.text)
    data = resp.json()
    content = data["choices"][0]["message"]["content"]

    # 兼容 string 或 list
    if isinstance(content, list):
        text = "".join(c.get("text","") for c in content if c.get("type") == "output_text")
    else:
        text = content

    return text.strip()

# ---------- LLM：生成菜谱 ----------
def generate_recipe(ingredients_text: str) -> dict:
    prompt = f"""
我现在有这些食材：{ingredients_text}
请严格返回 JSON，不要解释：

{{
  "ingredients": [],
  "dish": "",
  "steps": ["", "", ""],
  "tips": ""
}}
"""

    payload = {
        "model": LLM_MODEL,
        "messages": [
            {"role": "user", "content": prompt}
        ]
    }

    resp = requests.post(BASE_URL, headers=HEADERS, json=payload, timeout=30)

    print("LLM status:", resp.status_code)
    print("LLM body:", resp.text)

    if resp.status_code != 200:
        raise HTTPException(status_code=500, detail=resp.text)

    data = resp.json()
    content = data["choices"][0]["message"]["content"]

    if isinstance(content, list):
        text = "".join(c.get("text","") for c in content if c.get("type") == "output_text")
    else:
        text = content

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {
            "ingredients": [ingredients_text],
            "dish": "JSON 解析失败",
            "steps": [text],
            "tips": "模型未按 JSON 输出"
        }

# ---------- 接口：组合两段处理，先识别出菜品，然后再调用大语言模型生成菜谱 ----------
@app.post("/analyze_image")
async def analyze_image(file: UploadFile = File(...)):
    image_bytes = await file.read()
    print("🚀 进入 analyze_image 接口")
    print(">>> 收到图片:", file.filename)

    ingredients_text = recognize_food(image_bytes)
    print(">>> 识别结果:", ingredients_text)

    recipe = generate_recipe(ingredients_text)
    print(">>> 菜谱结果:", recipe)

    return {
        "scene": "food",
        "ingredients": recipe.get("ingredients", []),
        "dish": recipe.get("dish", ""),
        "steps": recipe.get("steps", []),
        "tips": recipe.get("tips", "")
    }

def call_qwen_text(prompt: str) -> str:
    """调用千问文本模型，返回纯文本回答"""
    payload = {
        "model": LLM_MODEL,
        "messages": [
            {"role": "user", "content": prompt}
        ]
    }
    resp = requests.post(BASE_URL, headers=HEADERS, json=payload, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    content = data["choices"][0]["message"]["content"]
    if isinstance(content, list):
        text = "".join(c.get("text", "") for c in content if c.get("type") == "output_text")
    else:
        text = content
    return text


#文本输入：/reading/text
@app.post("/reading/text")
async def reading_text(req: ReadingTextRequest):
    prompt = f"""
    你是一个专业的中文阅读理解助手，请认真阅读以下内容：

    ----------------
    {req.content}
    ----------------

    请你用【JSON 格式】返回以下结构（严格返回 JSON，不要多余解释）：

    {{
      "summary": "用不超过5句话总结全文",
      "key_points": ["列出3~5条重点"],
      "difficulty": "简单 / 中等 / 偏难",
      "qa_suggestion": "给出一个适合继续追问的问题，例如：作者观点是否存在争议？"
    }}
    """

    text = call_qwen_text(prompt)

    # 让模型直接返回 JSON 格式，我们解析一下
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        # 模型没按 JSON 返回，就包一层兜底
        data = {
            "summary": text,
            "key_points": [],
            "qa_suggestion": ""
        }

    return {
        "scene": "reading_text",
        **data
    }

#理解截图/图片：/reading/image（用 Qwen-VL）
def call_qwen_vl_for_reading(image_bytes: bytes) -> str:
    image_b64 = base64.b64encode(image_bytes).decode("utf-8")

    payload = {
        "model": VISION_MODEL,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": "请阅读图片中显示的内容（比如网页、App、文章等），用中文帮我做一份简明扼要的总结，并输出 JSON：{\"summary\":...,\"key_points\":[...],\"qa_suggestion\":...}。"
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/jpeg;base64,{image_b64}"
                        }
                    }
                ]
            }
        ]
    }

    resp = requests.post(BASE_URL, headers=HEADERS, json=payload, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    content = data["choices"][0]["message"]["content"]
    if isinstance(content, list):
        text = "".join(c.get("text", "") for c in content if c.get("type") == "output_text")
    else:
        text = content
    return text
@app.post("/reading/image")
async def reading_image(file: UploadFile = File(...)):
    image_bytes = await file.read()

    text = call_qwen_vl_for_reading(image_bytes)

    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        data = {
            "summary": text,
            "key_points": [],
            "qa_suggestion": ""
        }

    return {
        "scene": "reading_image",
        **data
    }

#阅读PDF：/reading/pdf
@app.post("/reading/pdf")
async def reading_pdf(file: UploadFile = File(...)):
    # 读入 PDF 内容
    pdf_bytes = await file.read()

    # 用 PdfReader 从内存打开
    from io import BytesIO
    reader = PdfReader(BytesIO(pdf_bytes))

    max_pages = min(3, len(reader.pages))  # 先只看前 3 页
    texts = []
    for i in range(max_pages):
        page = reader.pages[i]
        texts.append(page.extract_text() or "")

    full_text = "\n\n".join(texts)

    prompt = f"""
你是一个 PDF 阅读助手。下面是文档前 {max_pages} 页的文字内容，请帮我做一个整体阅读理解总结。

正文内容如下（可能比较长）：

{full_text}

请用 JSON 返回：
{{
  "summary": "几句话总结整段内容",
  "key_points": ["要点1", "要点2", "要点3"],
  "qa_suggestion": "给出一条适合继续提问的问题"
}}
只输出 JSON。
"""
    text = call_qwen_text(prompt)

    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        data = {
            "summary": text,
            "key_points": [],
            "qa_suggestion": ""
        }

    return {
        "scene": "reading_pdf",
        "page_count": max_pages,
        **data
    }

# 一句话智能回复
@app.post("/chat/reply")
async def chat_reply(req: ChatReplyRequest):
    prompt = f"""
你是一个中文聊天助手，根据对话帮我生成合适回复。

对方说的话：
{req.context}

要求：
- 回复风格：{req.style}
- 给出1条推荐回复
- 给出2条备选回复
- 简要说明为什么这样回

用JSON返回：
{{
  "reply": "",
  "style": "{req.style}",
  "alternatives": ["",""],
  "explain": ""
}}
只输出JSON。
"""
    text = call_qwen_text(prompt)
    try:
        data = json.loads(text)
    except:
        data = {"reply": text, "style": req.style, "alternatives": [], "explain": ""}
    return {"scene": "chat", **data}
# 语气风格改写（更礼貌/更幽默/更商务）
@app.post("/chat/rewrite")
async def chat_rewrite(req: ChatRewriteRequest):
    prompt = f"""
请把下面这句话改写为“{req.style}”风格：

原句：{req.text}

要求：
- 不改变原意
- 更符合社交表达习惯

用JSON返回：
{{
  "reply": "",
  "style": "{req.style}",
  "alternatives": ["",""],
  "explain": ""
}}
只输出JSON。
"""
    text = call_qwen_text(prompt)
    try:
        data = json.loads(text)
    except:
        data = {"reply": text, "style": req.style, "alternatives": [], "explain": ""}
    return {"scene": "chat_rewrite", **data}
# 看懂上下文（基于最近多轮）
@app.post("/chat/context")
async def chat_context(req: ChatContextRequest):
    dialogue = "\n".join([f"{m['role']}：{m['content']}" for m in req.messages])

    prompt = f"""
你是一个聊天助手，请基于以下对话历史，生成下一句合适回复：

对话记录：
{dialogue}

目标：{req.goal}
风格：{req.style}

请返回JSON：
{{
  "reply": "",
  "style": "{req.style}",
  "alternatives": ["",""],
  "explain": ""
}}
只输出JSON。
"""
    text = call_qwen_text(prompt)
    try:
        data = json.loads(text)
    except:
        data = {"reply": text, "style": req.style, "alternatives": [], "explain": ""}
    return {"scene": "chat_context", **data}
