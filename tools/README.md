# 数据流水线辅助工具

这些脚本是正式流水线之外的诊断、抽样与独立测试工具；在仓库根目录调用。涉及正式批次时，显式传入服务器或本地下载的 5500/500 条文件路径。仓库自带 `scene_ai_server/data/` 仅为 60/40 条 pilot。

| 脚本 | 用途 |
|---|---|
| `export_review.py` | 按教师 `expected.scene/intent` 分层抽取人工抽检清单 |
| `probe_teacher.py` | 对教师端点比较提示、token 预算和关闭思考模式的效果；会发起真实请求 |
| `slice_1k.py` | 按教师 `target.scene/intent` 与难度分层抽取训练子集，供后续 1K/5.5K 对比实验 |
| `independent_cases.py` + `independent_eval.py` | 生成 150 条定向探针，记录 AI/人工复核来源，核对正式数据独立性并评测 epoch-2/3；步骤见 [`docs/07-独立测试操作.md`](../docs/07-独立测试操作.md) |
| `round2_data.py` | 用正式 5500/500 与锁定 150 条的摘要、行数和近重复检查守住第二轮训练边界；新增 204 条规则样本并写出混合训练源和来源清单 |
| `export_public_release.py` | 从忽略提交的本地正式报告提取聚合指标、脱敏训练配置和可选的权重 SHA-256，生成 [`artifacts/release-v1/metrics.json`](../artifacts/release-v1/metrics.json)；不复制逐条话语、预测或绝对路径 |

正式训练与导出入口在 `scene_ai_server/`：`build_sft.py` → `train_lora.py` → `predict_lora.py` / `pipeline/evaluate.py` → `merge_lora.py` → `prepare_gguf_prompt.py`。GGUF 转换、Q4_K_M 量化和 CPU 推理调用的是上游 llama.cpp，具体命令见 [`docs/11`](../docs/11-端侧模型合并与GGUF转换.md)。

旧目录中的 `apply_*_patch.py` 是一次性迁移脚本，其修改已进入 `scene_ai_server/pipeline/` 正式代码。不要再次执行这些补丁。旧 Runbook 包含过时规模与内部环境路径，保留在旧目录供历史对照。
