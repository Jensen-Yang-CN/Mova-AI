# Mova 云端公网部署与百炼接入

Android 调用的是本目录的 FastAPI 服务。FastAPI 再调用阿里云百炼的 OpenAI 兼容接口。手机设置中的“服务地址”必须是 FastAPI 的公网 HTTPS 地址，不能填写百炼模型接口，也不能填写开发电脑或公司内网地址。

## 1. 服务端配置

在部署平台的**环境变量**中填写以下项目，不要放入源码、镜像或 APK：

```text
MOVA_PROVIDER=dashscope
DASHSCOPE_API_KEY=<在阿里云百炼控制台创建的专用 Key>
MOVA_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
MOVA_LLM_MODEL=qwen-plus
MOVA_VISION_MODEL=qwen3-vl-plus
MOVA_AMAP_KEY=<城市功能所需的高德 Web 服务 Key>
```

示例 Base URL 对应北京地域。Key、地域和计费方案必须匹配；使用其他地域时，按阿里云文档更换对应的兼容接口地址。`qwen3-vl-plus` 已在当前账号和接口完成图片请求验证；如果改用其他视觉模型，应再次实测 `/reading/image`。通义万相用于图像生成，不能替代这里用于图片理解的视觉模型，也不能替代 `qwen-plus` 生成文字回复。`MOVA_AMAP_KEY` 只影响城市解析与动态城市资料，未配置时沟通等功能仍可运行。旧内网 `MOVA_BASE_URL` 必须移除或覆盖。

服务端启动后先检查 `/health`，再用合成内容调用 `/chat/reply`；`/health` 只证明 Mova 后端可达，不会代替百炼实测。

## 2. 部署服务

本目录的 `Dockerfile` 可用于支持容器的云主机或托管容器平台。构建上下文应是 `scene_ai_server/`，部署平台通过密钥管理功能注入环境变量。容器监听 `8000`，对外通过云平台或反向代理提供 HTTPS 域名。切勿把 `.env` 包进镜像，也不要把 8000 端口裸露为无保护的公网投入使用。

若使用 cpolar 的 HTTP 隧道，本机服务端口必须与隧道的“本地地址”一致。例如隧道转发到 `http://localhost:8080`，就在 `scene_ai_server/` 目录启动：

```text
python -m uvicorn app:app --host 127.0.0.1 --port 8080
```

手机只填写 cpolar 提供的 **HTTPS 公网域名**，不填写 `localhost:8080`，也不填写百炼接口。先分别检查本机 `http://127.0.0.1:8080/health` 和公网 `https://你的域名/health`，再经公网地址发一次真实 `/chat/reply`。`/health` 返回 200 只证明隧道和 FastAPI 已连通；模型 Key 错误时，生成仍会失败。

cpolar 若选择“随机域名”，重连或重启后地址可能变化。它适合临时联调；评委安装包应使用已保留的固定 HTTPS 域名，并在手机移动网络下验证。可在 cpolar 控制台的“预留 → 保留二级子域名”中申请 `sceneai-api`，再编辑 `Mova` 隧道，把域名类型改为“二级子域名”并填写同名标签；实际 HTTPS 完整地址以“在线隧道列表”为准。cpolar 官方说明该能力需要基础套餐或以上。穿透客户端与后端应在电脑开机后自动启动、异常时重启；电脑关机、休眠或穿透掉线都会使云端功能中断。

cpolar 免费套餐无法保留固定域名时，也可使用 Tailscale Funnel。Tailscale 官方说明免费套餐可使用 Funnel，并通过设备的 `*.ts.net` 名称提供固定 HTTPS 地址。安装并登录 Tailscale、启用 Funnel 权限后，在本机后端保持监听 8080 的条件下运行：

```text
tailscale funnel --bg 8080
tailscale funnel status
```

`--bg` 让 Funnel 在 Tailscale 重启后自动恢复；它不会替代 FastAPI 自身的开机启动。最终只把 `funnel status` 显示的 HTTPS 地址注入安装包，不把 Tailscale 登录信息或百炼 Key 写进 APK。Funnel 暴露的是公开接口，仍需使用后端请求限额、百炼费用预算和日志监控。正式使用前必须从手机移动网络测试该地址，而不能只凭本机 DNS 或 `/health` 结果判断可用。

如果 `funnel status` 显示开启，但外部浏览器提示 `DNS_PROBE_FINISHED_NXDOMAIN`，先向公网 DNS 查询该名称；本机连通可能只是 MagicDNS 走了内网。Tailscale 说明公网记录可能需要约 10 分钟。更换设备名时，要等 `tailscale status` 显示新名称后再重新设置 Funnel；旧名称构建的 APK 不会自动切换。出现这种情况时，应分别验证公网 DNS、手机移动网络的 `/health`，再通过公网发起真实生成请求。

后端对模型和地图接口默认设置进程内总上限：每分钟 30 次、滚动 24 小时 500 次，可通过 `MOVA_PUBLIC_REQUESTS_PER_MINUTE` 和 `MOVA_PUBLIC_REQUESTS_PER_DAY` 调整。上限会在进程重启后重置，且多进程部署不共享计数，因此仍应在百炼控制台设置费用预算，并监控失败率。比赛演示使用单独的百炼 Key，避免个人开发 Key 被长期共用。

仓库不保存任何实际穿透域名或密钥。临时随机域名可以用于构建连通性测试包；固定域名、有效百炼 Key 和公网真实生成都通过后，才能制作评委直接使用的安装包。

## 3. Android 安装包

如果 Funnel 根路径已用于安装包下载，可把 API 单独挂到 `/api`：

```text
tailscale funnel --bg --https=443 --set-path=/api --yes http://127.0.0.1:8080
```

手机基地址应为 `https://xxx.ts.net/api/`，健康检查为该地址下的 `health`。本轮验证该映射会把 `/api` 前缀剥离后转发到 FastAPI，同时保留原有根路径下载服务。

在 `SceneAi_App/` 构建时传入 Mova 后端地址：

```text
gradlew.bat assembleDebug -PmovaServiceBaseUrl=https://你的公网服务域名/
```

该参数只包含后端地址，不含百炼 Key。构建配置优先级依次为命令行 `movaServiceBaseUrl`、环境变量 `MOVA_SERVICE_BASE_URL`、已忽略的 `SceneAi_App/local.properties` 中的 `mova.serviceBaseUrl`。本机可在该文件写入 `mova.serviceBaseUrl=https://xxx.ts.net/api/`，普通更新构建就会保留公网默认地址。实际域名不提交仓库。

三种配置都为空时，App 默认服务地址才为空。配置公网默认地址后，旧的回环／私网设置会显示为新的公网地址；在手机「保存并检测」后将新地址保存。验证时移除 ADB reverse，再从手机移动网络发起真实请求，不能把 USB 联调当作公网验证。

评审手机应关闭 Wi-Fi，仅用移动网络打开 `https://你的公网服务域名/health`；随后在 App 中走一次完整的“输入消息 → 本机判断 → 云端回复 → 复制”流程。这样才能验证公网连通性和模型调用都正常。

## 4. 模型与使用边界

端侧 Qwen3-0.6B GGUF 只输出结构化场景理解。候选回复正文由服务端通过百炼生成。阅读图片、食材图片需要服务端的视觉模型可用；城市推荐还依赖地图服务。服务不可达时，App 不把端侧 JSON 伪装成已完成的正文回复。

参考：[阿里云百炼 Base URL 总览](https://help.aliyun.com/zh/model-studio/base-url)、[Tailscale Funnel 官方文档](https://tailscale.com/docs/features/tailscale-funnel)、[cpolar 固定二级子域名说明](https://www.cpolar.com/docs)、[AI+软件创新赛题要求](https://www.aicomp.cn/tracks/tracks-2/3792.html)。
