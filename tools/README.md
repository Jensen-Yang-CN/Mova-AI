# 数据流水线辅助工具

这三个脚本是正式流水线之外的诊断或抽样工具；在仓库根目录调用。涉及正式批次时，显式传入服务器或本地下载的 5500/500 条文件路径。仓库自带 `scene_ai_server/data/` 仅为 60/40 条 pilot。

| 脚本 | 用途 |
|---|---|
| `export_review.py` | 按教师 `expected.scene/intent` 分层抽取人工抽检清单 |
| `probe_teacher.py` | 对教师端点比较提示、token 预算和关闭思考模式的效果；会发起真实请求 |
| `slice_1k.py` | 按教师 `target.scene/intent` 与难度分层抽取训练子集，供后续 1K/5.5K 对比实验 |

旧目录中的 `apply_*_patch.py` 是一次性迁移脚本，其修改已进入 `scene_ai_server/pipeline/` 正式代码。不要再次执行这些补丁。旧 Runbook 包含过时规模与内部环境路径，保留在旧目录供历史对照。
