FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py jsonx.py ./
COPY mova ./mova
COPY knowledge ./knowledge

# Key 在云平台环境变量中注入，镜像和源码中均不包含 .env。
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
