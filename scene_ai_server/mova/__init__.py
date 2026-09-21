"""Mova-AI 云端服务的内部模块。

拆分原则：
    config     —— 环境与配置（唯一读环境变量的地方）
    providers  —— 模型厂商适配（唯一发 HTTP 请求的地方）
    prompts    —— 提示词构建（唯一写 prompt 的地方）
    schemas    —— 请求/响应契约（唯一定义 JSON 结构的地方）
    pipeline   —— 离线流水线（数据 / 蒸馏 / 量化 / 评测）
"""
