# Mova-AI · 场景触发式移动 AI 生活助手

> **不是等你开口，而是在你需要的那一刻，刚好出现。**
>
> 一个把**云端大模型的场景理解能力蒸馏进端侧小模型**、并基于**校准置信度做端云级联推理**的 Android 智能助手。

[![Android](https://img.shields.io/badge/Android-minSdk%2026%20%7C%20targetSdk%2036-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.110-009688?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com/)
[![Python](https://img.shields.io/badge/Python-3.9%2B-3776AB?logo=python&logoColor=white)](https://www.python.org/)
[![Arch](https://img.shields.io/badge/Architecture-端云级联路由-2E7D32)](#系统架构)

---

## 一、这个项目和"调个大模型 API"的区别

市面上的 AI 助手大多是「**你问它才答**」：先打开 App、先想好怎么问、再等它回。Mova-AI 想反过来 —— **让 AI 自己判断该不该出现**。

它要回答的不是"怎么把请求发给大模型"，而是移动端 AI 的三个真问题：

| 真问题 | Mova-AI 的回答 |
|---|---|
| **什么时候该出现？** | 把「要不要打扰用户」建模成**带代价的序列决策**，用上下文老虎机在线学习，而不是写死规则 |
| **端侧小模型能扛多少？** | 定义**能力契约**：端侧只学「场景判断 → 意图分类 → 难度评估 → 路由建议 → 槽位抽取」，长生成与复杂推理上云 |
| **什么时候必须上云？** | 把路由写成**代价敏感决策**：端侧省下的毫秒，折算成可接受的精度损失 |

### 和普通套壳应用的正面对比

| 维度 | 普通 AI 应用 | Mova-AI |
|---|---|---|
| 模型来源 | 调 API | 云端大模型当教师 → **蒸馏出端侧小模型** → 量化部署 |
| 端侧能力 | 无（断网即废） | 端侧模型承担感知、判断与结构化抽取 |
| 端云分工 | 无（全走云端） | **代价敏感级联路由**，按置信度动态决策 |
| 路由依据 | `if (复杂) 上云` | 校准后的置信度 + 实测标定的延迟/代价权重 |
| 输出可靠性 | 靠 prompt 祈祷 JSON | **语法约束解码 + 槽位校验**，失败自动升级 |
| 可解释性 | 黑箱 | 每次决策都能回答"为什么走了云端" |

---

## 二、系统架构

```
┌──────────────────────────── 端侧（Android · SceneAi_App）────────────────────────────┐
│                                                                                       │
│  感知层   相机 / 前台 App / 时间 / 位置 / 运动状态 / 网络状态                            │
│     ↓                                                                                 │
│  触发层   P(此刻需要 | 信号) × 上下文老虎机 × 打扰成本 → 要不要出现                      │
│     ↓                                                                                 │
│  推理层   视觉轻量分类器 ──┐                                                          │
│          端侧 SLM（INT4）─┴─ 统一 Executor 接口                                        │
│     ↓                                                                                 │
│  路由层   置信度校准 → 代价敏感决策 → 语法约束解码 → 槽位校验 → 不够就升级云端           │
│     ↓                                                                                 │
│  交互层   结构化结果 / 「为什么现在出现」信号卡 / 技术面板 / 反馈回灌                     │
│                                                                                       │
└───────────────────────────────────┬───────────────────────────────────────────────────┘
                                    │  HTTP + 契约化 JSON（含 meta：执行方式/耗时/路由原因）
                                    ▼
┌──────────────────────────── 云侧（scene_ai_server · FastAPI）────────────────────────┐
│  在线服务   /health  /capabilities  /analyze_image  /reading/*  /chat/*                │
│             Provider 抽象：DashScope ⇄ DeepSeek ⇄ 任意 OpenAI 兼容端点                 │
│  离线流水线 数据引擎 → 三信号难度评估 → 去重 → 子模覆盖优化 → 配比求解                   │
│             → Response / Logit / Feature 三层蒸馏 → 分层量化 → 端侧模型产物             │
└───────────────────────────────────────────────────────────────────────────────────────┘
                          ▲
                          │  教师模型（自托管，承担 Teacher / Judge / Generator / 难度估计）
```

### 一条设计原则：端侧与云侧实现同一个 `Executor` 接口

路由层不关心谁是本地谁是远程。好处是：加新执行器（比如 NPU 加速版）不改路由代码；评测时可以把云端换成 mock 做离线消融；端侧还没训好时挂一个"永远失败"的桩，就能先验证降级逻辑。

---

## 三、界面：为新用户设计，而不是为演示设计

**"友好"被拆成了 8 条可验收的标准**（详见 [`docs/03-UI-UX设计规范.md`](docs/03-UI-UX设计规范.md)）：

| # | 标准 | 做法 |
|---|---|---|
| 1 | 首启最多 3 步进主界面 | 价值说明 → 权限 → 连接配置，每步都可跳过 |
| 2 | 用户不需要想"输入什么" | 每个能力页都有**一键示例**（做饭内置示例图、阅读内置范文、聊天内置场景） |
| 3 | 空状态有明确下一步 | 插图 + 一句话 + 一个按钮 |
| 4 | 错误能自己解决 | 严格「发生了什么 / 为什么 / 怎么办」三件套 + 可点的出路 |
| 5 | 结果能拿走 | 所有结果卡都有复制按钮 |
| 6 | 权限有理由 | 授权前说清"用来做什么" |
| 7 | 长内容不被截断 | 全面废弃 Toast，改为结构化结果卡 |
| 8 | 高级能力不吓人 | 技术面板独立成一级入口，主流程零术语 |

### 页面清单

| 页面 | 说明 |
|---|---|
| 首启向导 | 3 屏，含**连接检测**（失败时给出排查清单）与"演示模式"兜底 |
| 首页 | 连接状态卡 → 主动智能总开关 → 场景能力网格 → 今日端云概览 |
| 做饭助手 | 拍照（**全分辨率**）/ 相册 / 试试示例 → 阶段化加载（识别→生成）→ 菜谱卡 |
| 阅读总结 | 文本 / 截图 / PDF 三 tab，**同一套输出契约**，结果卡只写一份 |
| 聊天辅助 | 风格 chips + 目标 chips + 可展开多轮上下文 → 推荐回复 / 备选 / 为什么这样回 |
| 记录 | 按场景筛选，每条带**端侧/云端徽标**，可点进详情 |
| 技术面板 | 会话统计、延迟拆解、端侧模型状态、最近请求与**路由原因**、导出日志 |
| 设置 | 服务连接、主动智能（免打扰/敏感度）、端侧、外观、数据、关于 |

> 技术面板刻意做成一级入口：它同时服务两类读者 —— 用户看"AI 做了什么决定"，面试官看"这个项目有什么技术含量"。

---

## 四、技术栈

**Android 端（`SceneAi_App/`）**

| 项 | 选型 | 为什么 |
|---|---|---|
| 语言 | **Kotlin 2.1** | 协程 + 密封类，适合表达"加载/成功/失败/降级"状态机 |
| UI | **Jetpack Compose + Material 3** | 多页面 + 动态状态（阶段化加载、降级横幅）代码量远低于 XML |
| 导航 | Navigation Compose | 单 Activity，路由集中声明 |
| 状态 | ViewModel + StateFlow | 单向数据流，UI 只渲染 UiState |
| 网络 | Retrofit + OkHttp + kotlinx.serialization | 与后端契约一一对应，编译期强类型 |
| 本地存储 | DataStore(Preferences) + JSON 文件 | 设置用 DataStore；历史记录量小（≤200 条），用文件省掉 Room 的注解处理器 |
| 依赖注入 | 手写 `AppContainer` | 依赖图只有 4 个对象，比引入 Hilt 更透明、编译更快 |
| 构建 | Gradle 8.13 + AGP 8.13.1 + Version Catalog | 版本集中管理 |

**云侧（`scene_ai_server/`）**

| 项 | 选型 | 为什么 |
|---|---|---|
| 框架 | FastAPI + Pydantic | 契约即代码，自动生成 OpenAPI |
| 模型调用 | **只实现一次 OpenAI 兼容协议** | DashScope 兼容模式 / DeepSeek / 自建 vLLM 都说这套协议，一个类覆盖三家 |
| 容错 | 指数退避重试 + JSON 多层兜底提取 | 小模型不按格式返回是常态，在解析层尽量救回来 |
| 上传校验 | **魔数嗅探**而非信任 Content-Type | 堵住"上传接口无校验"这个经典问题 |
| 可观测 | 每个响应带 `meta`（执行方式/模型/耗时/阶段拆解/路由原因） | App 技术面板的唯一数据源 |

---

## 五、快速开始

### 第 1 步：启动云端服务

```bash
cd scene_ai_server
pip install -r requirements.txt
```

配置 API Key（**源码中不含任何密钥**）。复制模板后填入：

```powershell
Copy-Item .env.example .env      # PowerShell
# cp .env.example .env           # Bash
```

编辑 `.env`，至少填一个 Key：

```ini
MOVA_PROVIDER=dashscope
DASHSCOPE_API_KEY=sk-你的Key
# 也可以切成 DeepSeek：
# MOVA_PROVIDER=deepseek
# DEEPSEEK_API_KEY=sk-你的Key
```

也可以不用 `.env`，直接设置环境变量（`DASHSCOPE_API_KEY` / `DEEPSEEK_API_KEY` / `MOVA_API_KEY`）。

启动（**端口必须是 8000**，与 App 默认地址一致）：

```bash
uvicorn app:app --host 0.0.0.0 --port 8000 --reload
```

- 交互式文档：**http://localhost:8000/docs**
- 健康检查：**http://localhost:8000/health**（首启向导的「检测连接」就是打这个）

### 第 2 步：编译运行 Android 端

1. 用 **Android Studio** 打开 `SceneAi_App/` 目录（**不是仓库根目录**）
2. 确认 **JDK 17+**（JDK 21 已验证）
3. 连接真机或模拟器，点击 Run

命令行构建：

```powershell
cd SceneAi_App
$env:JAVA_HOME = "你的 JDK 路径"
.\gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 第 3 步：配置联调地址

App 内可以随时改：**设置 → 服务连接**，或**首启向导第 3 屏**点「检测连接」。

| 调试方式 | 地址 |
|---|---|
| 模拟器 | `http://10.0.2.2:8000/`（10.0.2.2 是模拟器访问宿主机 localhost 的固定别名） |
| 真机 | `http://电脑的局域网IP:8000/`（`ipconfig` 查询，手机与电脑须同一网段） |

服务以 `--host 0.0.0.0` 启动，真机可直接通过局域网 IP 访问。

### 第 4 步（可选）：跑一遍数据引擎

不需要显卡，也不需要安装任何第三方包 —— 数据引擎只用 Python 标准库：

```bash
cd scene_ai_server
python smoke_test.py --vision          # 先确认两个教师 Key 都通
python pipeline/build_dataset.py --limit 150 --budget 60
python pipeline/evaluate.py --predictor teacher-b
```

产物落在 `scene_ai_server/data/`，其中 `dataset_report.md` 与 `eval_report.md` 是自动生成的统计报告。
标注结果有缓存，重复运行不会重复消耗 API 额度。

---

## 六、接口文档

基地址 `http://<host>:8000`。**所有响应都带 `meta` 段**：

```json
{
  "executor": "cloud",
  "model": "qwen-vl-max+qwen3-max",
  "latency_ms": 1240,
  "route_reason": "端侧执行器未接入，本次直连云端",
  "stages": { "recognize": 420, "generate": 820 },
  "confidence": null
}
```

| 接口 | 入参 | 返回要点 |
|---|---|---|
| `GET /health` | — | `status`、`version`、`provider`、`models`、`edge` |
| `GET /capabilities` | — | 场景清单（驱动客户端首页能力网格） |
| `POST /analyze_image` | multipart `file` | `ingredients` / `dish` / `steps` / `tips` + `meta.stages` 两段耗时 |
| `POST /reading/text` | JSON `{content}` | `summary` / `key_points` / `difficulty` / `qa_suggestion` |
| `POST /reading/image` | multipart `file` | 同上 |
| `POST /reading/pdf` | multipart `file` | 同上 + `page_count` |
| `POST /chat/reply` | JSON `{context, style}` | `reply` / `alternatives[]` / `explain` |
| `POST /chat/rewrite` | JSON `{text, style}` | 同上 |
| `POST /chat/context` | JSON `{messages[], goal, style}` | 同上 |

**返回示例**（`/chat/reply`）：

```json
{
  "scene": "chat",
  "reply": "这周实在排不开，下周我来定时间可以吗？",
  "style": "委婉",
  "alternatives": ["最近手上事有点多，我们约下周？", "这次先不参加了，下次一定到"],
  "explain": "先给出客观原因再提供替代方案，既明确拒绝又保留关系",
  "meta": { "executor": "cloud", "model": "qwen3-max", "latency_ms": 980, "route_reason": "端侧执行器未接入，本次直连云端", "stages": {} }
}
```

---

## 七、数据引擎（S1 + S2，已实际跑通）

端侧方案里最先落地的是**数据侧**——它不依赖显卡、不依赖 NDK，而且是后面所有训练的前提。

```bash
cd scene_ai_server
python pipeline/build_dataset.py --limit 150 --budget 60
```

```
① 种子展开 (150)  →  ② 双教师标注  →  ③ 契约校验  →  ④ 三信号难度
                                    →  ⑤ MinHash 去重  →  ⑥ 子模覆盖  →  ⑦ 配额配比
```

### 实测结果（全部来自真实运行，未手工填写）

| 阶段 | 指标 | 结果 |
|---|---|---|
| ③ 契约校验 | **契约合法率** | **100%**（150/150） |
| ④ 难度评估 | 平均难度 / 三信号可用数 | 0.312 / 150·150·143 |
| ⑤ 去重 | LSH 候选对 → 精确复核删除 | 274 → **22 条（14.7%）** |
| ⑥ 覆盖优化 | 网格覆盖 | **37/37 格** |
| ⑥ 覆盖优化 | 子模目标 F vs 随机基线 | 47.01 vs 39.71 → **提升 18.4%** |
| ⑦ 配额配比 | 难度分布 vs 目标 (30/50/20) | **36.7% / 50.0% / 13.3%** |
| 评测 | 标签噪声下限（教师B × 教师A 标注） | scene 87.2% / intent 82.0% / 槽位 F1 0.838 |

### 三个实测发现（写进文档，因为它们是流程真正的价值）

1. **契约枚举用了英文、prompt 又没列取值 → 合法率 0%。** 教师只能猜，输出「晚饭」而不是 `dinner`。修正后 100%。教训：*契约必须显式到模型能照抄的程度。*
2. **`temperature=0` 会摧毁 logprobs。** 极低温度把分布压成 one-hot，接口返回的 `logprob` 恒为 0、候选恒为 -9999。因此标签（T=0，保证可复现）与软标签（T=1，保证分布有意义）**必须分成两次调用**。
3. **难度分布稀疏是语料问题，不是度量问题。** 第一轮 120 条全落进 easy 档；排查后发现种子都是"换个食材名"式的表面变化，任务本身毫无歧义。补上真正的困难样本、并把采样从「全局随机截断」改成「按组轮转」后，hard 档才被填满。

> 第 3 条对任何数据流水线都成立：**稀有类别必须显式保底，不能指望随机采样照顾它。**

### 产物

| 文件 | 内容 |
|---|---|
| `data/distill_dataset.jsonl` | 蒸馏数据集：硬标签 + **软标签（教师 top-K 分布）** + 难度 + 质量分 |
| `data/eval_gold.jsonl` | 评测集 v1（与训练集按 id 严格互斥） |
| `data/edge_contract.schema.json` | 端侧语法约束解码用的 JSON Schema |
| `data/dataset_report.md` | 自动生成的统计报告 |
| `data/eval_report.md` | 评测报告（标签噪声下限） |

评测还暴露了一个有意思的现象：`none`（无场景/歧义输入）的分类准确率只有 **42.9%**，远低于其他场景 —— 这**反证了难度信号是有效的**：那些被判定为难的样本，确实是两个教师都拿不准的样本。

---

## 八、端侧模型方案（设计已完成，实现待推进）

详细方案见 [`docs/02-端侧模型方案.md`](docs/02-端侧模型方案.md)，这里只给结论：

| 环节 | 方案要点 |
|---|---|
| **能力契约** | 端侧只学感知/决策/抽取三类能力，**长生成与复杂推理留云端** |
| **数据引擎** | 三信号客观难度（教师自一致性 × 学生探针损失 × 跨教师分歧）+ 子模覆盖优化（$1-1/e$ 保证）+ 线性规划配比 |
| **三层蒸馏** | Response → **Logit（top-K logits 离线缓存，绕开显存限制）** → Feature（选做） |
| **量化** | 分层敏感性分析 → **比特分配当背包问题求解**；embedding/lm_head 单独处理；KV cache 量化 |
| **端侧运行时** | llama.cpp/GGUF 主线，LiteRT-LM 横评；目标机骁龙 8 Gen 3 **可走 Hexagon NPU** |
| **可靠性** | **语法约束解码**保证 JSON 100% 合法 + 槽位校验兜底 |
| **路由** | 温度缩放/等渗回归校准置信度 → 代价敏感决策（λ 由实测帕累托前沿反推） |
| **进阶** | 任务级投机执行；端云投机解码 |

> ⚠️ **诚实声明**：端侧模型**尚未部署到 App 中**。技术面板与设置页会如实显示"端侧模型未部署"，并在接入后自动点亮相关统计 —— 界面无需改动。

---

## 九、目录结构

```
移动AI生活助手/
├── docs/                                   # 📘 设计文档
│   ├── 01-总体设计.md                       #    问题定义、级联路由框架、创新点、实验设计、路线图
│   ├── 02-端侧模型方案.md                    #    能力契约、数据引擎、三层蒸馏、量化、运行时、路由
│   └── 03-UI-UX设计规范.md                   #    信息架构、页面规格、设计 token、文案规范
│
├── scene_ai_server/                        # 🐍 云侧服务 + 离线流水线
│   ├── app.py                              #    路由层（唯一处理 HTTP 的地方）
│   ├── jsonx.py                            #    零依赖 JSON 兜底提取（在线与离线共用）
│   ├── mova/
│   │   ├── config.py                       #    配置（唯一读环境变量的地方）
│   │   ├── providers.py                    #    厂商适配（唯一发请求的地方）
│   │   └── prompts.py                      #    提示词（唯一写 prompt 的地方）
│   ├── pipeline/                           #    🔬 离线数据引擎（零第三方依赖）
│   │   ├── contract.py                     #      S1 能力契约 + JSON Schema + 校验器
│   │   ├── seeds.py                        #      场景种子库（按组轮转采样）
│   │   ├── teacher.py                      #      双教师客户端（含 logprobs 捕获）
│   │   ├── difficulty.py                   #      三信号难度
│   │   ├── dedup.py                        #      MinHash + LSH
│   │   ├── coverage.py                     #      子模最大化覆盖
│   │   ├── allocate.py                     #      配额配比（最大余数法）
│   │   ├── build_dataset.py                #      主流程 + 报告生成
│   │   └── evaluate.py                     #      评测（含标签噪声下限）
│   ├── data/                               #    产物：数据集 / 评测集 / 报告（cache 已忽略）
│   ├── requirements.txt · requirements-train.txt
│   └── .env.example
│
├── SceneAi_App/                            # 📱 Android 端（Kotlin + Compose，单 Activity）
│   ├── app/src/main/java/com/mova/sceneai/
│   │   ├── MainActivity.kt · MovaApp.kt
│   │   ├── core/                           #    容器 / 设置 / 错误 / 媒体 / 格式化
│   │   ├── data/                           #    model / remote / local / repo（含 Router）
│   │   └── ui/                             #    theme / nav / components / 各页面
│   ├── keystore/debug.keystore             #    一次性调试密钥（口令为约定的 android）
│   └── gradle/libs.versions.toml
│
└── README.md
```

---

## 十、实现进度

图例：✅ 已完成　🟡 进行中　⬜ 未开始（方案已设计）

| 层 | 模块 | 状态 |
|---|---|---|
| 端·交互 | Compose 多页应用（8 个页面） | ✅ **编译通过，产出 APK** |
| 端·交互 | 首启向导（价值 → 权限 → 连接检测） | ✅ |
| 端·交互 | 结构化结果渲染（全面取代 Toast） | ✅ |
| 端·交互 | 一键示例（三个场景各一份，冷启动友好） | ✅ |
| 端·交互 | 「为什么现在完成」技术面板 + 端云徽标 | ✅ |
| 端·网络 | Retrofit + 统一错误翻译（三件套文案） | ✅ |
| 端·路由 | `Router` 代价敏感决策函数（端侧未就绪时恒走云端） | ✅ 代码就绪 |
| 端·推理 | 端侧 SLM 执行器（llama.cpp JNI） | ⬜ |
| 端·推理 | 视觉轻量分类器 | ⬜ |
| 端·路由 | 置信度校准 + 语法约束解码 + 槽位校验 | ⬜ |
| 端·感知 | 触发决策（老虎机 + 退避）、无障碍/悬浮窗 | ⬜ |
| 云·在线 | 7 个能力接口 + `/health` + `/capabilities` + `meta` | ✅ |
| 云·在线 | Provider 抽象（DashScope ⇄ DeepSeek ⇄ 自建） | ✅ |
| 云·在线 | 魔数校验、体积上限、重试退避、结构化日志、CORS | ✅ |
| 云·离线 | **S1 能力契约**（可执行契约 + JSON Schema + 校验器） | ✅ |
| 云·离线 | **S2 数据引擎**（双教师标注 → 三信号难度 → MinHash 去重 → 子模覆盖 → 配额配比） | ✅ |
| 云·离线 | 评测脚本 + 评测集 v1 + 标签噪声下限 | ✅ |
| 云·离线 | 三层蒸馏训练（Response / Logit / Feature） | ⬜ |
| 云·离线 | 量化（分层敏感性 + 背包比特分配） | ⬜ |
| 工程 | 单元测试与仪器测试 | ⬜ |

---

## 十一、已知限制

- **端侧模型未接入**：当前所有推理都在云端完成；`Router` 已实现完整规则，端侧就绪后自动生效
- **数据规模是演示级**：数据集为 60 条、评测集 40 条，用于跑通并验证流水线；流水线本身可线性扩展
- **评测标签未经人工核验**：`eval_gold.jsonl` 是教师标注的，因此只能测「标签噪声下限」（两个教师的一致率 82–87%），不能当作绝对精度
- **软标签与硬标签序列不完全对齐**：对话式 API 无法对指定前缀做 teacher forcing 取分布，当前软标签取自 T=1 的自回归生成；换成自托管 vLLM 用 `prompt_logprobs` 可消除
- **场景自动触发未实现**：无障碍服务、悬浮窗、位置感知仍是设计稿
- **服务端无鉴权**：任何能访问到端口的人都能消耗你的模型额度，**切勿直接暴露到公网**
- **明文 HTTP**：`network_security_config.xml` 为本地联调放开了明文流量，上线前需收紧
- **无自动化测试**：`test/` 与 `androidTest/` 目前为空
- **release 未开混淆**：开启前需为 kotlinx.serialization / Retrofit 补 keep 规则

---

## 十二、开发环境注意事项

| 事项 | 说明 |
|---|---|
| **仓库路径含中文** | 本仓库路径为 `移动AI生活助手`，AGP 默认会拒绝含非 ASCII 路径的构建。已在 `gradle.properties` 中设置 `android.overridePathCheck=true`（实测 JDK 21 + AGP 8.13.1 可正常构建）。若你移到纯英文路径，可以删掉这一行 |
| **调试密钥** | AGP 默认在 `~/.android/` 自动生成调试密钥，在受限环境（沙箱 / 无家目录写权限的 CI）会失败。这里改为使用仓库内置的 `SceneAi_App/keystore/debug.keystore`，其口令就是 Android 约定的 `android`，**不具备任何保密性**，仅供本地安装调试；release 请另行配置签名 |
| **Gradle 发行版** | `gradle-wrapper.properties` 默认使用阿里云镜像（文件里注释了官方地址与腾讯镜像）。之所以默认镜像：在部分网络环境下 JVM 直连 `services.gradle.org` 会在 TLS 握手阶段收到 Connection reset，而镜像站稳定可用 |
| **模型密钥** | 只放在服务端 `.env` 或环境变量里，**App 端永远不内置任何 Key** |
| **PDF 解析** | 默认只解析前 3 页（`MOVA_MAX_PDF_PAGES`），扫描件因无可提取文字会返回 422 并提示改用截图识别 |
| **离线流水线的依赖** | `pipeline/` 下的数据引擎**零第三方依赖**，只用标准库即可运行；`requirements-train.txt` 里的 PyTorch 等是后续训练阶段才需要 |

---

## 十三、文档索引

| 文档 | 内容 |
|---|---|
| [`docs/01-总体设计.md`](docs/01-总体设计.md) | 问题定义、代价敏感级联路由框架、系统架构、技术选型、5 个创新点、**消融实验设计**、9 阶段路线图、风险预案 |
| [`docs/02-端侧模型方案.md`](docs/02-端侧模型方案.md) | 能力契约、数据引擎（三信号难度 / 子模覆盖 / LP 配比）、三层蒸馏、量化（分层敏感性 + 背包比特分配）、端侧运行时、端云路由、测量协议 |
| [`docs/03-UI-UX设计规范.md`](docs/03-UI-UX设计规范.md) | 新用户友好 8 条验收标准、信息架构、页面规格、设计 token、文案规范 |
| [`docs/04-能力契约.md`](docs/04-能力契约.md) | **S1**：端侧要学什么、为什么让模型自己输出 `need_cloud`、契约为什么必须可执行、两个实测缺陷的记录 |
| [`docs/05-数据引擎.md`](docs/05-数据引擎.md) | **S2**：三信号难度、MinHash+LSH、子模覆盖的数学依据、配额最优性说明、**三个实测发现** |
| [`docs/archive/`](docs/archive) | 早期方案存档（已被上面几份取代，保留以记录设计演进） |

---

<p align="center">
  <b>不是等你开口，而是在你需要的那一刻，刚好出现。</b><br/>
  <sub>Mova-AI · 移动端主动智能助手</sub>
</p>
