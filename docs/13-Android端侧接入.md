# Android 端侧 GGUF 接入与验收

> 2026-10-12：Android 依赖已安装到 E 盘，arm64 / x86_64 APK 编译通过；OPPO Find X7 Ultra（Android 16）已安装、导入并加载 Q4_K_M GGUF。本地中文真机冒烟通过，自建模型服务已完成回复、文本阅读与食材示例请求。界面与竞赛叙事见 docs/14。

## 运行边界

当前选定的 `qwen3-0.6b-epoch3-Q4_K_M.gguf` 是**场景/意图/槽位/上云判断模型**，不生成短信正文。客户端聊天回复采用：

```text
用户短文本 → 本机 GGUF 决策 JSON → 契约校验 → 云端 /chat/reply → 可复制的回复正文
                                   └ 校验或推理失败 → 直接走云端原链路
```

聊天回复即使得到 `need_cloud=false`，仍需要云端生成正文；模型自报 `confidence` 尚未校准，不用它绕过云端。技术面板最终执行端仍为 `cloud`，`edge_decision` 阶段记录本地耗时，路由原因记录端侧参与或回退。其余做饭图片、PDF 阅读、聊天多轮等功能维持原服务端路径。

## 构建前置条件

1. 将本轮量化时使用的上游 `llama.cpp` 源码放到 `SceneAi_App/third_party/llama.cpp/`，或保留服务器原目录名 `SceneAi_App/third_party/llama.cpp-retry/`，确保有 `include/llama.h` 和根 `CMakeLists.txt`。两种目录名都被 Git 忽略。本次收到的源码 Git commit 为 `de7fa0a3c6a2e1b4cd9f22eb8d6bf5b12dbdb63b`；项目 JNI 已按此版本完成 Android 链接与 OPPO 真机冒烟。
2. 安装 Android SDK、NDK、CMake 与 JDK。当前 Gradle 的 CMake 构建覆盖 `arm64-v8a` 真机和 `x86_64` 模拟器 CPU 后端，关闭 OpenMP、llamafile 和 OpenSSL。Android 官方示例同样建议跨平台构建时关闭 `GGML_NATIVE`，并给出 NDK 构建步骤：[llama.cpp Android 文档](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)。
3. 在 Android Studio 同步 `SceneAi_App/` 并构建 `app`。若提示找不到 Android SDK，先在 Android Studio 配置实际 SDK 位置；若提示缺少 `llama.h`，检查第 1 步目录。不要把 GGUF、底座权重或整个第三方源码直接提交到普通 Git 历史。
4. 聊天页新增的「沟通目标」传给 `/chat/reply`。服务器若仍部署旧代码，请同步本仓库的 `scene_ai_server/app.py` 与 `scene_ai_server/mova/prompts.py`，然后重启服务；端侧离线试运行不依赖这一步。

## 模型安装

1. 将本地私有目录中的 `qwen3-0.6b-epoch3-Q4_K_M.gguf` 复制到手机可通过系统文件选择器打开的位置，例如 Downloads。文件为 **396,704,544 字节**，SHA-256 应为 `0c5f1a05e36f49556a9457a51523e87a1dc7aec9f7f1ad00e1c027b0ad051d92`。
2. 打开 App「设置 → 端侧模型 → 导入 Q4_K_M 模型」，选择该文件。App 以流式复制到私有目录，边复制边校验大小与 SHA-256，再调用 JNI 加载。文件不上传服务端，也不打包进 APK。
3. 状态变成「端侧模型运行中」后开启「优先使用端侧」，并保持云端服务地址可用，以便生成最终回复。

## 首次验收

- 不连接服务器也可先在「技术面板 → 端侧模型试运行」输入一句短文本。页面应展示本机判断的场景、意图、槽位、上云建议和耗时；这一步没有短信正文。
- 在聊天「回复」模式输入一条短的中文回复诉求，点击生成。技术面板应出现 `edge_decision` 耗时及本地判断的路由原因；结果卡应展示云端生成的具体回复，而不是路由 JSON。
- 再关闭「优先使用端侧」重复一次，应直接走云端，技术面板不应记录新的 `edge_decision` 阶段。
- 模型文件损坏、JSON 不合法、端侧推理超过 30 秒、输入超过 1000 字或设备 ABI 不受支持时，应用应保留云端回复能力并说明回退原因。
- 若服务器不可用，端侧仍可加载和判断，但当前聊天页面**无法离线生成回复正文**。首页不得宣称完整聊天能力离线可用。

## 提示与契约同步

`tools/export_android_edge_assets.py` 从训练用 `pipeline.contract.contract_prompt` 导出 `edge_prompt.template.txt`，并从脱敏的 `artifacts/release-v1/metrics.json` 导出 `edge_model.json`。完整前缀包含 Qwen3 `enable_thinking=False` 的空 `<think>` 段，与服务器保存的单条冒烟提示逐字对照。更改训练契约或发布模型后必须重新运行导出脚本、重新编译 App，不要直接手改资产文本。端侧输出由 Kotlin 的 `EdgeDecisionParser` 校验；契约失败时回退云端。


## 2026-10-12 本机环境与实测记录

| 项目 | 本次配置 |
|---|---|
| SDK | `E:\Efilefolder\Android\sdk`；Android 36 平台 revision 2、Build-Tools 36.0.0、platform-tools 37.0.1 |
| NDK / CMake | 27.0.12077973 / 3.22.1；版本在 Gradle 中固定 |
| Gradle / JDK | Gradle 8.13 / JDK 20；Gradle 缓存在 E 盘 |
| APK ABI | arm64-v8a 与 x86_64；手机使用 ARM CPU 后端 |
| ARM 内核 | armv8.2-a+dotprod；JNI 加载前检查 FP16、dotprod、原子指令，旧设备安全回退 |
| 手机 | OPPO Find X7 Ultra，Android 16；页大小 4096 字节 |
| 模型安装 | 系统文件选择器导入，私有目录大小 396,704,544 字节，App 核验 SHA-256 |
| 中文输入 | 1090 个提示 token；使用本文档示例的合伙人搬家回复诉求 |
| 单线程核验 | 本地推理 23,057 ms，输出合法且为 chat / reply_suggest；仅一个样本 |
| 四线程核验 | JUnit `OK (1 test)`；整条测试 11.231 秒，包含初始化；不是平均推理延迟 |
| 云端联调 | 自建 Qwen 服务，回复示例 HTTP 200、一次服务生成耗时 2406 ms；文本阅读和食材示例也返回 HTTP 200 |

### 本轮发现的问题与处理

1. **提示换行漂移**：Windows 文本写入会将 LF 转为 CRLF。导出脚本改写原始 UTF-8 字节，并通过 `.gitattributes` 固定提示资产 LF；与服务器私有冒烟提示按字节核对。
2. **手机预填充超时**：Debug 的 native 优化不足，通用 ARM 内核也未使用目标手机的点积能力。CMake 对 Android Debug 使用 RelWithDebInfo（-O2），ggml ARM 内核启用 dotprod，并加硬件特性检查。
3. **真机测试的后台限制**：ColorOS 下没有前台 Activity 的 instrumentation 进程出现长时间停顿。测试先启动 MainActivity，再复用 App 的模型实例；避免额外加载第二份权重。
4. **批次状态明确化**：提示按一个逻辑批次交给 llama.cpp 内部切分，明确 token 位置、序列 ID 与末 token logits；每次推理清空 KV，生成时逐 token 递增位置。
5. **本地判断与正文的边界**：聊天页展示本机判断，正文继续使用云端生成；`need_cloud=false` 也不意味着本地可以创作短信。显式沟通目标优先于抽取结果。
6. **云端鉴权不适配**：原 DashScope 配置返回 401；本次使用已验证的自建 vLLM 服务。`MOVA_PROVIDER=vllm` 支持不带 Authorization 的无鉴权服务，实际服务地址只存本地 `.env`，公开文档使用 xxx。Qwen 请求关闭思考模式。
7. **系统分享控制**：分享只预填内容，用户选择目标并确认后再生成；在聊天页已有实例时再次分享也能更新内容。
8. **拒绝回复新增承诺**：首轮在线回复出现「改天再约」等用户未提供的承诺。提示明确禁止补造称谓、时间与补偿承诺；复核后推荐回复说明出远门、无法帮忙，表达歉意与祝愿，未新增再约承诺。
9. **Windows PowerShell 编码**：中文运行脚本的无 BOM UTF-8 会被 Windows PowerShell 5.1 按旧代码页解析，出现字符串未闭合。脚本保存为带 BOM UTF-8，并以实际 `powershell -File` 验证编译、安装与 USB 映射入口。

### 本机重现

在 `scene_ai_server` 中启动服务：

```powershell
& 'E:\Efilefolder\Mova\server-venv\Scripts\python.exe' -m uvicorn app:app --host 127.0.0.1 --port 8000
```

在仓库根运行：

```powershell
.\tools\run_android.ps1 -Install -ReverseLocalServer
# 手机上导入本轮 GGUF 后，可执行前台中文真机冒烟：
.\tools\run_android.ps1 -Smoke
```

手机 App 的服务地址填写 `http://127.0.0.1:8000/`。此地址依赖 USB 调试的 ADB reverse，断开后应连接可访问的真实服务；正式展示需说明运行环境。原始手机日志与包含地址的 `.env` 不提交，公开记录不含设备序列号、密钥或服务器 IP。


### 改版后的完整手机操作闭环

2026-10-12 在手机 App 中填入合伙人搬家示例，选择委婉、拒绝并生成。页面展示实际本机判断和云端回复；点击复制后显示「已复制，确认后再发送」，回看页可见新增记录。该条本机记录的 totalMs 为 10,919，edge_decision 为 8,839，serverMs 为 2,047，success 为 true，正文 executor 为 cloud。这是单条功能实测，不是平均延迟或用户效果指标。

浅色与深色首页、深色回复结果均已通过手机截图检查。测试摘要保存在 `artifacts/android-v1/smoke.json`，原始截图仅在本地 App build 目录，未公开上传手机状态栏或原始日志。云端拒绝提示进一步限制未经用户明确允许的补偿、再约等承诺。
