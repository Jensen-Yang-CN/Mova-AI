import base64
import os
import requests

# ⚠️ API Key 从环境变量读取，禁止硬编码（设置方式见 README 与 app.py 顶部注释）
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "").strip()
if not API_KEY:
    raise SystemExit('❌ 未检测到环境变量 DASHSCOPE_API_KEY，请先设置后再运行本测试脚本。')

URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
IMAGE_PATH = "tomato.jpg"  # 确保文件存在

with open(IMAGE_PATH, "rb") as f:
    b64 = base64.b64encode(f.read()).decode("utf-8")

payload = {
    "model": "qwen-vl-max",
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
                        "url": f"data:image/jpeg;base64,{b64}"
                    }
                }
            ]
        }
    ]
}

headers = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json"
}

resp = requests.post(URL, headers=headers, json=payload, timeout=30)

print("status:", resp.status_code)
print("body:", resp.text)
