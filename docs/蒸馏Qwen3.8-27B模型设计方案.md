# Mova-AI Edge Model

> 注：这是当前最小实现可行闭环方案。

Mova-AI 的端侧小模型训练、压缩与部署流水线。

## 1. 目标

将云端大模型具备的部分场景理解与任务路由能力迁移到轻量级端侧模型，使 Android 客户端能够在不访问云端的情况下完成高频、简单、结构化的任务判断。

当前阶段不追求训练通用聊天模型，而是构建面向 Mova-AI 场景的 Edge SLM。

---

## 2. 当前模型

### Teacher

- Qwen3.8-27B 云端大模型
- 负责生成高质量结构化监督数据

### Student

- Qwen3-0.6B
- 负责 Android 端本地推理

---

## 3. 端侧能力契约

第一阶段只训练三个核心能力：

```json
{
  "scene": "food",
  "intent": "diet_advice",
  "need_cloud": false
}
```

### scene

用于判断当前输入所属场景：

- `food`
- `reading`
- `chat`
- `location`
- `none`

### intent

用于判断用户具体意图。

### need_cloud

判断当前任务是否需要云端大模型进行进一步处理：

- `false`：端侧可以完成
- `true`：升级到云端

---

## 4. 蒸馏方案

第一阶段采用 Response Distillation。

```text
Teacher
Qwen3.8-27B
    │
    │ 生成结构化回答
    ▼
Distillation Dataset
    │
    │ supervised fine-tuning
    ▼
Student
Qwen3-0.6B
    │
    │ LoRA
    ▼
Edge SLM
```

不在第一阶段引入：

- Logit Distillation
- Feature Distillation
- GKD
- QAT
- 复杂的在线蒸馏

原因是首先验证完整的端侧模型工程闭环。

---

## 5. 数据来源

训练数据来自：

```text
scene_ai_server/data/distill_dataset.jsonl
```

数据由云端 Teacher 根据 Mova-AI 能力契约生成。

`edge_model/` 不重复实现数据生成引擎，仅负责：

```text
已有蒸馏数据
    ↓
训练格式转换
    ↓
Student Training
```

---

## 6. 训练

采用：

- Transformers
- TRL
- PEFT
- LoRA
- PyTorch

训练目标：

```text
Qwen3-0.6B
      ↓
Teacher-generated Dataset
      ↓
LoRA SFT
      ↓
Mova Edge SLM
```

第一轮实验建议：

- 数据规模：1000 条
- Epoch：2
- LoRA rank：16
- LoRA alpha：32
- LoRA dropout：0.05
- Learning rate：1e-4
- Max sequence length：1024

实际参数根据显存与验证集结果调整。

---

## 7. 评测

至少比较三个版本：

### Baseline

原始 Qwen3-0.6B。

### LoRA-1K

使用 1000 条 Teacher 数据进行 LoRA SFT。

### LoRA-5K

使用 5000 条 Teacher 数据进行 LoRA SFT。

指标：

- Scene Accuracy
- Intent Accuracy
- Need-Cloud Accuracy
- JSON Valid Rate
- 推理延迟

---

## 8. 模型压缩

训练完成后：

```text
LoRA Adapter
    ↓
Merge
    ↓
FP16 Model
    ↓
GGUF
    ↓
Q4_K_M
```

第一阶段只使用标准 4-bit 量化，不进行分层混合精度或 QAT。

核心评测：

```text
FP16 vs Q4_K_M

模型体积
准确率
JSON 合法率
推理速度
峰值内存
```

---

## 9. Android 部署

端侧 Runtime 第一阶段采用 llama.cpp。

```text
Q4_K_M GGUF
      ↓
llama.cpp
      ↓
Android
      ↓
EdgeExecutor
```

第一阶段优先验证 CPU 路径。

后续可进一步探索：

- GPU Backend
- Qualcomm QNN
- Hexagon NPU

---

## 10. 端云协同

Android 端统一通过 Executor 接口调用模型：

```text
                MovaExecutor
                     │
           ┌─────────┴─────────┐
           ▼                   ▼
     EdgeExecutor        CloudExecutor
           │                   │
       llama.cpp             FastAPI
           │                   │
      Qwen3-0.6B           Qwen3-Max
```

Edge SLM 输出：

```json
{
  "scene": "food",
  "intent": "diet_advice",
  "need_cloud": false
}
```

若 `need_cloud=false`：

```text
本地处理
```

若 `need_cloud=true`：

```text
升级云端
```

---

## 11. 实验路线

### Phase 1

Response Distillation

### Phase 2

LoRA SFT

### Phase 3

FP16 → GGUF → Q4_K_M

### Phase 4

llama.cpp Android 部署

### Phase 5

端云 Executor 接入

### Phase 6（可选）

Logit Distillation

### Phase 7（可选）

NPU / QNN 加速

---

## 12. 第一阶段完成标准

当以下链路全部跑通时，认为 Edge SLM V1 完成：

```text
Teacher
  ↓
Distillation Dataset
  ↓
Qwen3-0.6B LoRA
  ↓
Evaluation
  ↓
Merge
  ↓
GGUF
  ↓
Q4_K_M
  ↓
llama.cpp
  ↓
Android
  ↓
EdgeExecutor
  ↓
端云路由
```

第一阶段的目标不是提出新的蒸馏算法，而是完成一个可验证、可部署、可测量的端侧模型工程闭环。
