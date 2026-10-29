# 城市知识资料

`cities.jsonl` 是本轮公开资料的唯一输入，每行是一块可独立引用的事实。首批收录武汉、岳阳、青岛，共 12 条。正文是依据所列政府网站整理的简短摘要，不保存整篇网页，也不包含价格、营业时间或促销活动。

字段：`id`（唯一）、`city_code`（六位行政编码）、`city_name`、`kind`（`food` / `sight`）、`name`、`text`（简短事实摘要）、`source_title`、`source_url`、`verified_on`（核对日期，非网页发布时间）。

补充资料时先阅读原页面，再写摘要与来源。每块只描述一个食品或景点，避免把同一页面的无关内容混在一起。更新或删除某行后，服务会按文件修改时间重建内存检索索引；新城市无需修改业务代码。核对日期应填写实际检查日期。

在仓库根目录运行：

```powershell
python tools/city_kb.py
python tools/city_kb.py --city-code 370200 --question "当地有哪些特色美食"
python tools/city_kb.py --check-sources
```

第一条检查字段、重复 ID、城市名称一致性与来源 URL；第二条仅运行本机检索，适合入库后检查；第三条联网检查 HTTP 状态与标题。SSL 或超时错误只能说明当前电脑连接未成功，需再从手机浏览器核验；资料正文仍需人工阅读确认。
