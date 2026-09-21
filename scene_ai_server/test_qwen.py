import requests
import json
import os

# ⚠️ API Key 从环境变量读取，禁止硬编码（设置方式见 README 与 app.py 顶部注释）
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "").strip()
if not API_KEY:
    raise SystemExit('❌ 未检测到环境变量 DASHSCOPE_API_KEY，请先设置后再运行本测试脚本。')

url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

headers = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json",
}

data = {
    "model": "qwen3-max",
    "messages": [
        {"role": "user", "content": "你好，用一句话介绍你自己"}
    ],
}

resp = requests.post(url, headers=headers, json=data, timeout=30)
print(resp.status_code)
print(resp.text)

