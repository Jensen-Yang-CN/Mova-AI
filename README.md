# SCENE-AI · 移动 AI 生活助手

> **Mova-AI** · 一款运行在 Android 手机上的**场景触发式 AI 助手**：感知你正在做什么，在最合适的时刻主动提供 AI 服务，实现"**无需唤醒、无需搜索**"的随身智能体验。

[![Android](https://img.shields.io/badge/Android-minSdk%2024%20%7C%20targetSdk%2036-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Java](https://img.shields.io/badge/Java-11-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.110-009688?logo=fastapi&logoColor=white)](https://fastapi.tiangolo.com/)
[![Python](https://img.shields.io/badge/Python-3.9+-3776AB?logo=python&logoColor=white)](https://www.python.org/)
[![Model](https://img.shields.io/badge/Model-Qwen--VL--Max%20%2B%20Qwen3--Max-615CED)](https://bailian.console.aliyun.com/)
[![Arch](https://img.shields.io/badge/Architecture-端云协同-2E7D32)](#系统架构)

---

## 📖 项目简介

手机里的 AI 助手大多是"**你问它才答**"——要先打开 App、先想好怎么问、再等它回。SCENE-AI 想反过来：**让 AI 自己判断该不该出现**。

项目通过感知用户所处场景（摄像头画面 / 当前使用的 App / 时间 / 位置），在最合适的时机主动提供帮助。例如：

- 你在厨房对着食材举起手机 → 自动识别食材 → 直接给出**能做哪道菜、怎么做**
- 你在微信里纠结怎么回一条消息 → 自动弹出浮层，给**推荐话术 + 备选方案 + 为什么这么回**
- 你在看一篇长文章或 PDF → 自动给出**摘要、要点和可继续追问的问题**

**产品定位**：生活场景优先，而不是又一个学习工具。

### 核心思路：AI 的价值不只在模型，而在"何时出现"

| 对比维度 | 普通工具型 AI | SCENE-AI |
| --- | --- | --- |
| 触发方式 | 用户主动打开、主动提问 | ✅ **场景感知自动触发** |
| 交互成本 | 切 App → 组织语言 → 等待 | ✅ 抬手即用，结果直出 |
| 能力组织 | 一个通用对话框 | ✅ 按生活场景分工的**场景调度器** |
| 结果形态 | 一段自由文本 | ✅ **强约束 JSON**，前端可结构化渲染 |
| 架构 | 纯端侧 或 纯云端 | ✅ **端云协同**：端做感知与调度，云做重模型推理 |

---

## 🏗️ 系统架构

```
┌─────────────────────────── Android 端（SceneAi_App）───────────────────────────┐
│                                                                               │
│  系统状态 / 摄像头 / 位置 / 当前 App                                            │
│            ↓                                                                  │
│  场景判断模块（本地）                                                          │
│            ↓                                                                  │
│  场景调度器  ──  决定"此刻该不该调用 AI、调用哪个能力"                          │
│            ↓                                                                  │
│  OkHttp 发起请求（multipart 上传图片 / JSON 提交文本）                          │
│            ↓                                                                  │
│  结果解析（org.json）                                                          │
│            ↓                                                                  │
│  Toast / 浮窗 / 页面展示                                                       │
│                                                                               │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    │  HTTP（当前为明文，开发期用）
                                    ▼
┌─────────────────────────── 云端（scene_ai_server）───────────────────────────┐
│                                                                               │
│  FastAPI  ──  /analyze_image   /reading/*   /chat/*                            │
│      ↓                                                                        │
│  Prompt 构建 + 模型路由                                                        │
│      ├── qwen-vl-max   多模态视觉模型（认食材、读图）                           │
│      └── qwen3-max     大语言模型（生成菜谱、总结、话术）                       │
│      ↓                                                                        │
│  结构化结果（强制 JSON + 解析兜底）                                             │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## 🧰 技术栈

**Android 端（`SceneAi_App/`）**

| 项 | 选型 |
| --- | --- |
| 语言 | **Java 11**（`MainActivity.java`） |
| 构建 | Gradle **Kotlin DSL** + Version Catalog（`libs.versions.toml`），AGP **8.13.1** |
| SDK | `minSdk 24` / `targetSdk 36` / `compileSdk 36` |
| 网络 | **OkHttp 4.10.0**（multipart 上传） |
| UI | AppCompat + Material + ConstraintLayout |
| 相机 | 系统相机 `MediaStore.ACTION_IMAGE_CAPTURE`（方案中规划 CameraX） |
| JSON | `org.json` |

**云端（`scene_ai_server/`）**

| 项 | 选型 |
| --- | --- |
| Web 框架 | **FastAPI** + Uvicorn |
| 数据校验 | Pydantic（`BaseModel` 定义请求体） |
| 大模型 | 阿里云百炼 **DashScope 兼容模式 API** |
| 视觉模型 | `qwen-vl-max` |
| 语言模型 | `qwen3-max` |
| PDF 解析 | `pypdf`（内存中读取，取前 3 页） |
| HTTP 客户端 | `requests` |

---

## 📂 目录结构

```
移动AI生活助手/
│
├── scene_ai_server/                        # 🐍 云端 AI 服务（FastAPI）
│   ├── app.py                              # ⭐ 服务主体：7 个接口 + Prompt 构建 + 模型调用
│   ├── requirements.txt                    # Python 依赖
│   ├── test_vl.py                          # 冒烟测试：单独验证 qwen-vl-max 认图
│   ├── test_qwen.py                        # 冒烟测试：单独验证 qwen3-max 文本对话
│   ├── tomato.jpg                          # 测试用食材图
│   ├── SCENE-AI：基于多模态感知的智能场景触发式移动助手系统开发方案.md   # 总体设计文档
│   └── 聊天场景模块创新点及UI介绍.md        # 聊天模块创新点与 UI 设计说明
│
├── SceneAi_App/                            # 📱 Android 客户端
│   ├── app/
│   │   ├── build.gradle.kts                # 模块构建脚本（依赖、SDK 版本）
│   │   ├── proguard-rules.pro
│   │   └── src/main/
│   │       ├── AndroidManifest.xml         # 权限：INTERNET / CAMERA / SYSTEM_ALERT_WINDOW
│   │       ├── java/com/example/scene_ai_app/
│   │       │   └── MainActivity.java       # ⭐ 拍照 → 上传 → 解析 JSON → 展示菜谱
│   │       └── res/
│   │           ├── layout/activity_main.xml        # 首页：一个「拍照并上传」按钮
│   │           ├── xml/network_security_config.xml # 允许明文 HTTP（开发用）
│   │           └── values/ · mipmap-*/             # 主题、颜色、图标
│   ├── gradle/libs.versions.toml           # 依赖版本集中管理
│   ├── gradle/wrapper/                     # Gradle Wrapper（8.13）
│   ├── build.gradle.kts · settings.gradle.kts · gradle.properties
│   └── gradlew · gradlew.bat
│
├── .gitignore
└── README.md
```

> 📌 `SceneAi_App/local.properties`（记录本机 Android SDK 路径）与所有 `build/`、`.gradle/` 编译产物**均已被忽略，未上传**。

---

## 🚀 快速开始

### 一、启动云端服务

```bash
cd scene_ai_server
pip install -r requirements.txt
```

**必须先配置模型 API Key**（源码中不含任何密钥）。到[阿里云百炼控制台](https://bailian.console.aliyun.com/)申请 API Key 后设置环境变量：

```powershell
# PowerShell（当前窗口有效）
$env:DASHSCOPE_API_KEY="sk-你的Key"

# PowerShell（永久写入用户环境变量，需重开终端）
[Environment]::SetEnvironmentVariable("DASHSCOPE_API_KEY","sk-你的Key","User")
```

```bash
# CMD
set DASHSCOPE_API_KEY=sk-你的Key

# Bash / zsh
export DASHSCOPE_API_KEY="sk-你的Key"
```

启动服务（**端口必须是 8000**，与 Android 端硬编码的 `BASE_URL` 一致）：

```bash
uvicorn app:app --host 0.0.0.0 --port 8000 --reload
```

启动后可访问交互式接口文档：**http://localhost:8000/docs**

> ⚠️ 若端口改成别的（如 9100），必须同步修改 `MainActivity.java` 中的 `BASE_URL`，否则 App 一定连不上。

### 二、编译运行 Android 端

1. 用 **Android Studio** 打开 `SceneAi_App/` 目录（不是仓库根目录）
2. 首次打开时 Studio 会自动生成 `local.properties` 并指向你的 SDK
3. 确认 **JDK 11+**，Gradle 版本 ≥ 8.13
4. 连接模拟器或真机，点击 Run

### 三、配置联调地址

`MainActivity.java` 中的地址需要按调试方式切换：

```java
// 模拟器：10.0.2.2 是 Android 模拟器访问「宿主机 localhost」的固定别名
private static final String BASE_URL = "http://10.0.2.2:8000/analyze_image";

// 真机调试：改成电脑的局域网 IP（ipconfig 查询），并保证手机与电脑同一网段
// private static final String BASE_URL = "http://192.168.x.x:8000/analyze_image";
```

由于服务以 `--host 0.0.0.0` 启动，真机可直接通过局域网 IP 访问；服务端当前**未配置 CORS**，但不影响原生 App 调用。

### 四、冒烟测试（可选）

想先确认 Key 和网络是否通，可以跳过 App，单独跑两个测试脚本：

```bash
cd scene_ai_server
python test_qwen.py    # 验证 qwen3-max 文本调用
python test_vl.py      # 验证 qwen-vl-max 认图（会读取同目录 tomato.jpg）
```

---

## 🔌 接口文档

基地址：`http://<你的地址>:8000`，全部为 **POST**，返回 JSON。

### 🍜 做饭 / 饮食场景

| 接口 | 入参 | 返回 |
| --- | --- | --- |
| `POST /analyze_image` | `multipart/form-data`，字段名 **`file`**（图片） | `scene`、`ingredients`、`dish`、`steps`、`tips` |

**调用示例**

```bash
curl -X POST "http://localhost:8000/analyze_image" -F "file=@tomato.jpg"
```

**返回示例**

```json
{
  "scene": "food",
  "ingredients": ["番茄", "鸡蛋"],
  "dish": "番茄炒蛋",
  "steps": ["鸡蛋打散加少许盐", "番茄切块热油下锅", "倒入蛋液翻炒均匀"],
  "tips": "番茄先去皮口感更好"
}
```

### 📄 阅读场景

| 接口 | 入参 | 返回 |
| --- | --- | --- |
| `POST /reading/text` | JSON：`{"content": "正文"}` | `scene`、`summary`、`key_points[]`、`difficulty`、`qa_suggestion` |
| `POST /reading/image` | `multipart`：`file`（截图/照片） | `scene`、`summary`、`key_points[]`、`qa_suggestion` |
| `POST /reading/pdf` | `multipart`：`file`（PDF） | `scene`、`page_count`、`summary`、`key_points[]`、`qa_suggestion` |

> `difficulty` 取值：`简单` / `中等` / `偏难`；`/reading/pdf` 目前**只解析前 3 页**（`max_pages = min(3, len(reader.pages))`）。

### 💬 聊天辅助场景

| 接口 | 入参 | 返回 |
| --- | --- | --- |
| `POST /chat/reply` | JSON：`{"context": "对方说的话", "style": "自然"}` | `scene`、`reply`、`style`、`alternatives[]`、`explain` |
| `POST /chat/rewrite` | JSON：`{"text": "原句", "style": "礼貌"}` | 同上（`scene` 为 `chat_rewrite`） |
| `POST /chat/context` | JSON：`{"messages": [{"role":"user","content":"..."}], "goal": "拒绝但不得罪", "style": "委婉"}` | 同上（`scene` 为 `chat_context`） |

**返回示例**

```json
{
  "scene": "chat",
  "reply": "这周实在排不开，下周我来定时间可以吗？",
  "style": "委婉",
  "alternatives": ["最近手上事有点多，我们约下周？", "这次先不参加了，下次一定到"],
  "explain": "先给出客观原因再提供替代方案，既明确拒绝又保留关系"
}
```

---

## 🎯 核心功能说明

### 1. 拍食材 → 出菜谱：两级流水线 + 强约束 JSON

`/analyze_image` 不是"一次问模型"，而是**两级串联**：

```
图片 ──▶ qwen-vl-max ──▶ "番茄, 鸡蛋" ──▶ qwen3-max ──▶ 结构化菜谱 JSON
        （只做识别，不做发挥）            （只做生成，基于识别结果）
```

**为什么拆两级？** 让视觉模型只负责"看图说话"这种它擅长的事，把"推理与组织"交给语言模型。这样识别环节的 Prompt 可以极简（"用中文名称，逗号分隔，不要解释"），生成环节又能拿到干净的食材清单，避免视觉模型在长输出里跑偏。

**工程上的两个细节：**

- **兼容多形态返回**：`qwen-vl-max` 的 `content` 可能是字符串，也可能是 `[{"type":"output_text",...}]` 结构，代码里统一做了归一化处理
- **JSON 解析兜底**：模型偶尔不按 JSON 输出，此时不抛异常，而是把原文塞进 `steps` 并把 `dish` 标为 `"JSON 解析失败"`，保证前端**永远拿得到可渲染的结构**

### 2. 聊天助手：从 ChatBot 到"沟通决策辅助"

这个模块的设计出发点不是"帮我回条消息"，而是"**我想拒绝，但不想伤人**"。它把沟通拆成了 **场景 + 目标 + 风格** 三个可控维度：

| 能力 | 接口 | 说明 |
| --- | --- | --- |
| 基础智能回复 | `/chat/reply` | 语义理解 + 语气可控 |
| 风格改写 | `/chat/rewrite` | 礼貌 / 委婉 / 幽默 / 职场 / 亲密 / 冷处理 |
| 多轮语境建模 | `/chat/context` | 输入完整对话历史 + 角色 + 当前目标 |
| 目标驱动 | `/chat/context` 的 `goal` | 接受 / 拒绝 / 推迟 / 转移话题 / 降低冲突 |
| 多候选 + 可解释 | 全部接口 | 推荐回复 + 2 条备选 + **为什么这样回** |

最后一条是刻意的产品立场：**给人选择而不是替人做决定**，AI 是协作方，不是代笔。完整的设计思考见 [`scene_ai_server/聊天场景模块创新点及UI介绍.md`](scene_ai_server/聊天场景模块创新点及UI介绍.md)。

### 3. 阅读场景：三种输入，同一套输出契约

文本 / 截图 / PDF 三条入口，出口结构完全一致（`summary` + `key_points` + `qa_suggestion`）。这样前端只需要写一套结果卡片，未来扩展 OCR、网页剪藏等新入口时也不用改 UI。

---

## ✅ 实现进度（对标设计文档）

设计文档规划了 5 大场景与完整的场景触发链路，当前实现情况如下：

| 模块 | 规划 | 当前状态 |
| --- | --- | --- |
| 云端 7 个 AI 接口 | ✅ | ✅ **全部实现**（`app.py`） |
| 拍照识别食材 → 菜谱 | ✅ 第 1 周 | ✅ **端到端打通**（拍照 → 上传 → 解析 → Toast 展示） |
| 聊天辅助 | ✅ 第 2 周 | ⚠️ **后端接口已就绪，Android 端 UI 未实现** |
| 阅读总结（文本/图/PDF） | ✅ 第 2 周 | ⚠️ **后端接口已就绪，Android 端 UI 未实现** |
| 首页控制面板（多场景开关） | ✅ | ❌ 仅有单个「拍照并上传」按钮 |
| 场景自动触发（Accessibility Service） | ✅ 第 3 周 | ❌ 未实现 |
| 悬浮窗展示 | ✅ 第 4 周 | ❌ 未实现（`SYSTEM_ALERT_WINDOW` 权限已声明） |
| 位置感知服务 | ✅ 第 5 周 | ❌ 未实现（未申请定位权限） |
| 夜间 / 护眼模式 | ✅ 第 5 周 | ❌ 未实现 |

**一句话概括**：**AI 能力层（云端）基本完成，产品体验层（Android）只完成了"拍菜"这一条主线。**

---

## ⚠️ 已知问题与限制

### 功能性

- [ ] **端口不一致（会导致连不上）**：`app.py` 原注释写启动端口 `9100`，而 Android 端 `BASE_URL` 硬编码为 `8000`。README 已统一为 **8000**，但请确认两边一致
- [ ] Android 端**只有一个 Activity、一个按钮**，聊天与阅读场景没有前端入口
- [ ] 结果通过 `Toast` 展示完整菜谱，长文本易被截断，应改为页面或浮窗
- [ ] 场景自动触发、悬浮窗、定位、护眼模式均未实现（见上表）
- [ ] `test/` 与 `androidTest/` 下仍是模板生成的 `ExampleUnitTest` / `ExampleInstrumentedTest`，**没有真实测试**

### 安全与健壮性

- [x] 三个文件中的硬编码 API Key 已改为**从环境变量 `DASHSCOPE_API_KEY` 读取**，源码不再包含任何密钥
- [ ] 服务端**无任何鉴权**，任何能访问到该端口的人都可以直接消耗你的模型额度，**切勿直接暴露到公网**
- [ ] `network_security_config.xml` 中 `cleartextTrafficPermitted="true"` **全局放开了明文 HTTP**，仅适用于本地开发，上线前必须收紧（建议只在 `debug` 构建里允许）
- [ ] 上传接口**未校验文件类型与大小**，缺少请求体上限，存在被大文件打爆的风险
- [ ] `app.py` 中直接 `print` 了模型返回的**完整响应体**（`print("VL body:", resp.text)`），日志量偏大且可能包含用户内容，建议改为按级别记录
- [ ] 大模型调用**未做超时重试与降级**（仅有 `timeout=30`），网络抖动会直接返回 500
- [ ] 未设置 CORS，若将来接入 Web 端需补上

### 工程配置

- [ ] `gradle-wrapper.properties` 的 `distributionUrl` 指向 `mirrors.aliyun.com/macports/distfiles/gradle/...`，这是**非官方的镜像路径**，他人 clone 后很可能下载失败；建议换成官方地址或稳定的镜像
- [ ] 缺少 `LICENSE` 文件

---

## 📚 文档索引

| 文档 | 内容 |
| --- | --- |
| [`SCENE-AI：基于多模态感知的智能场景触发式移动助手系统开发方案.md`](scene_ai_server/SCENE-AI：基于多模态感知的智能场景触发式移动助手系统开发方案.md) | 总体设计：系统架构、技术选型、5 大功能模块、AI 使用分类、UI 设计原则、2 个月开发路线、MVP 范围 |
| [`聊天场景模块创新点及UI介绍.md`](scene_ai_server/聊天场景模块创新点及UI介绍.md) | 聊天模块：5 种模式分类、API 设计、5 个创新点拆解、与普通聊天助手的对比表、UI 设计建议 |

---

## 📄 许可协议

本仓库当前**未包含 `LICENSE` 文件**，默认保留全部权利。
如需开放使用，建议在根目录补充一份 [MIT License](https://choosealicense.com/licenses/mit/)。

---

<p align="center">
  <b>不是等你开口，而是在你需要的那一刻，刚好出现。</b><br/>
  <sub>SCENE-AI · 场景触发式移动 AI 助手</sub>
</p>
