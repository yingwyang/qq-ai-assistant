---
title: 组件启动失败
description: 各组件自检命令、日志与常见误判
updated: 2026-09-16
---

系统由 7 个部件组成：NapCat（6099 / 6100）、后端（8081）、MySQL（3306）、RabbitMQ（5672）、AstrBot（6185）、GPT-SoVITS（8000）、网页前端（5173）。
本页给每个部件一条**自检命令**与**日志位置**，并说明最容易误判的情况——「进程活着但功能不可用」。

## 组件显示「已停止」/「未运行」

**现象**：「组件控制」里 AstrBot / NapCat / GPT-SoVITS 显示「已停止」；点「启动」后仍是停止，或提示启动失败。

**原因**：后台判断组件状态靠**检测端口是否监听**（AstrBot 6185、NapCat 6099、GPT-SoVITS 8000）。所以「显示未运行」只有三种可能：进程真的没起来；**端口被别的程序占用**导致起不来；起来了但监听在别的地址/端口。启动失败还有两类硬性前置条件——**依赖未装**（GPT-SoVITS 缺 `runtime\python.exe`、NapCat 缺 `launcher.bat`、AstrBot 目录不存在，后端会直接报「… not found at: …」），以及**运行环境版本不满足**：后端需 **JDK 17+**，前端需 **Node.js 18+**，另需 **MySQL 8.0+**、**RabbitMQ 3.12+**、Maven 3.8+。

**处理步骤**

1. 先看端口到底有没有监听（比后台状态更可信）：

```powershell
foreach ($p in 6099,6100,8081,3306,5672,6185,8000,5173,15672) {
  $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
  "{0,-6} {1}" -f $p, ($(if ($c) { "LISTENING (PID " + $c[0].OwningProcess + ")" } else { "DOWN" }))
}
```

2. 端口被别的进程占用时，查出是谁并处置（确认不是生产关键进程）：

```powershell
netstat -ano | findstr ":8000"
Get-Process -Id <PID> | Select-Object Id,ProcessName,Path
```

3. 检查组件目录与启动文件是否齐全：

```powershell
Test-Path "D:\ai\Documents\qq-web\napcat\NapCat.Shell\launcher.bat"
Test-Path "D:\ai\Documents\qq-web\astrbot"
Test-Path "D:\ai\Documents\qq-web\GPT-SoVITS-v2pro-20250604-nvidia50\runtime\python.exe"
```

4. 核对环境版本：`java -version`（17+）、`node -v`（18+）、`mvn -v`（3.8+）、`docker --version`（MySQL/RabbitMQ 用容器启动时）；版本不符先升级环境再重启组件。

**验证**：端口一览中目标端口变为 `LISTENING`；后台状态变为「运行中」；WebUI 能打开（AstrBot <http://localhost:6185>、NapCat `http://127.0.0.1:6099/webui?token=<NAPCAT_TOKEN>`）。

> 提示：后端对组件启动做了幂等处理——**端口已监听就跳过启动**。所以点了「启动」没反应、日志写「端口 … 已监听，跳过启动」是正常行为。

## AstrBot（6185）

**现象**：组件显示已停止；摘要/对话报 `Connection refused`；或端口在监听但摘要/对话返回空。

**原因**：进程没启动，或启动了但模型未配置、API Key 不对。后端调用地址由 `ASTRBOT_API_URL` 决定（默认 `http://localhost:6185`）。

**处理步骤**

1. 自检端口与 WebUI：

```powershell
Get-NetTCPConnection -LocalPort 6185 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalPort,OwningProcess
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:6185
```

2. 启动：后台「组件控制 → AstrBot → 启动」，或到 AstrBot 目录手工执行 `astrbot run`；
3. 报「AstrBot directory not found」→ 确认目录存在（后端按 `<backend>\..\..\astrbot` 解析，即 `D:\ai\Documents\qq-web\astrbot`）；
4. 端口在监听但功能异常 → 用《AI 摘要为空》里的 `/api/v1/chat` 直接测；
5. 日志：AstrBot 自身日志在启动它的控制台窗口；后端侧错误看 `backend/logs/application.log`。

**验证**：`curl.exe` 返回 200；后端日志不再出现 `HttpHostConnectException: Connect to http://localhost:6185 … Connection refused`；触发一次摘要或对话能正常出结果。

## GPT-SoVITS（8000）

**现象**：组件显示已停止；语音合成（TTS）失败或没有声音；启动报 `GPT-SoVITS python.exe not found at: …`。

**原因**：启动依赖自带 Python 运行时与 API 脚本
（`<GPT-SoVITS 目录>\runtime\python.exe -I api_v2.py -a 127.0.0.1 -p 8000`）。目录/文件缺失、CUDA 环境异常、音色权重路径不对都会导致起不来或合成无响应。后端地址由 `GPT_SOVITS_API_URL` 决定（默认 `http://localhost:8000`）。

**处理步骤**

1. 自检端口：`Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalPort,OwningProcess`；
2. 确认运行时与脚本存在（见上一节第 3 步）；
3. 启动：后台「组件控制 → GPT-SoVITS → 启动」，或手工在组件目录执行上面的命令并观察控制台报错（多为显存不足或权重路径不存在）；
4. 核对音色权重是否真实存在（根目录由 `GPT_SOVITS_MODEL_ROOT` 指定）：

```powershell
Test-Path "D:\ai\Documents\qq-web\GPT-SoVITS-v2pro-20250604-nvidia50\GPT_weights_v2Pro\Kisaki-e15.ckpt"
Test-Path "D:\ai\Documents\qq-web\GPT-SoVITS-v2pro-20250604-nvidia50\SoVITS_weights_v2Pro\Kisaki_e8_s5104.pth"
```

5. 日志：组件控制台窗口（用 `cmd /k` 启动，窗口不关就能看到）；后端 `backend/logs/application.log` 有 TTS 调用失败记录。

**验证**：端口 8000 为 LISTENING；后台 TTS 试听或网页语音合成能生成音频（输出目录 `GPT_SOVITS_OUTPUT_DIR`，默认 `backend/uploads/tts`）。

## NapCat（6099 WebUI / 6100 OneBot）

**现象**：组件显示已停止、消息完全收不到；或显示运行中但群里消息进不来。

**原因**：进程没启动，或者**启动了但 QQ 未登录**（最常见的伪故障）。启动依赖 `launcher.bat`；上报配置在 `onebot11_<QQ>.json` 里。

**处理步骤**

1. 自检两个端口：`Get-NetTCPConnection -LocalPort 6099,6100 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalPort,OwningProcess`；
2. 自检**登录状态**（比端口重要）：

```powershell
curl.exe -s -X POST "http://127.0.0.1:6100/get_login_info" `
  -H "Authorization: Bearer <NAPCAT_TOKEN>" -H "Content-Type: application/json" -d '{}'
```

   `retcode:0` 且带 `user_id` = 已登录；否则去 WebUI 重新扫码；
3. 启动：后台「组件控制 → NapCat → 启动」；报「NapCat launcher.bat not found」→ 检查 `napcat\NapCat.Shell\launcher.bat`；
4. 日志与配置目录：

```
D:\ai\Documents\qq-web\napcat\NapCat.Shell\
  ├─ config\onebot11_<QQ号>.json   # 上报地址与 token
  ├─ cache\qrcode.png              # 登录二维码
  └─ （launcher 窗口的控制台输出）
```

**验证**：6099 与 6100 均 LISTENING；`get_login_info` 返回 `retcode:0`；群里发消息后后端日志出现「【根路径】收到NapCat消息」。

## RabbitMQ（5672 / 15672）与 MySQL（3306）

**现象**：后端启动直接失败（MQ 连不上会**拒绝启动**，不静默降级）；或后端起来了但媒体不落盘、摘要不生成；或页面报数据库错误 500。

**原因**：这两个组件通常由 `docker-compose.yml` 启动（容器名 `qqai-rabbitmq` / `qqai-mysql`）。容器没起、端口冲突、密码与 `.env` 不一致都会失败。

**处理步骤**

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
Get-NetTCPConnection -LocalPort 3306,5672,15672 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalPort,OwningProcess
cd D:\ai\Documents\qq-web\qq-ai-assistant ; docker compose up -d      # 没起来就拉起

# MQ 管理台自检（账号密码见 .env 的 RABBITMQ_USERNAME / RABBITMQ_PASSWORD）
curl.exe -s -u <MQ用户名>:<MQ密码> "http://127.0.0.1:15672/api/overview" | ConvertFrom-Json | Select-Object rabbitmq_version
# 数据库连通自检
docker exec -it qqai-mysql mysql -uroot -p -e "USE qq_chat; SELECT COUNT(*) FROM messages;"
```

密码不一致时以 `.env` 为准（`DB_PASSWORD` / `RABBITMQ_PASSWORD`），改完 `docker compose up -d` 重建容器。日志：`docker logs qqai-rabbitmq --tail 100`、`docker logs qqai-mysql --tail 100`；后端侧看 `backend/logs/application.log`。

**验证**：两个容器状态为 `Up`；MQ 管理台 <http://127.0.0.1:15672> 能打开并看到队列；后端启动日志无 MQ / 数据源连接异常，页面能正常读消息。

## 「进程活着但功能不可用」

**现象**：所有端口都在监听、组件全显示「运行中」，但功能就是不行；重启组件后短暂恢复，过一会儿又不行。

**原因**：**端口只能证明进程在跑，不能证明它可用。** 三个典型例子：

| 组件 | 端口状态 | 实际状态 | 表现 |
|---|---|---|---|
| NapCat | 6099 在监听 | QQ 未登录 / 掉线 | 群里消息全部收不到（最常被误判成「后端坏了」） |
| AstrBot | 6185 在监听 | 模型未配置 / API Key 错 | 摘要与对话返回空，日志出现「AstrBot 响应为空」 |
| GPT-SoVITS | 8000 在监听 | 权重文件缺失 / 显存不足 | 语音合成超时或报错，进程没崩但出不了音频 |

**处理步骤**

1. 不看后台状态，**逐个做功能级自检**：

```powershell
# NapCat 是否真的登录
curl.exe -s -X POST "http://127.0.0.1:6100/get_login_info" `
  -H "Authorization: Bearer <NAPCAT_TOKEN>" -H "Content-Type: application/json" -d '{}'
# AstrBot 是否能正常回话（应为 SSE 文本流）
curl.exe -s -N -X POST "http://localhost:6185/api/v1/chat" `
  -H "X-API-Key: <ASTRBOT_TOKEN>" -H "Content-Type: application/json" `
  -d '{"message":"请回复两个字:成功","username":"summarizer","enable_streaming":false}'
```

2. 按结果分别处置：NapCat 去 WebUI 重新扫码；AstrBot 检查模型与 `ASTRBOT_TOKEN`；GPT-SoVITS 检查权重路径与显存；
3. 处理完**回到业务侧复现一次**，而不是只看端口。

**验证**：`get_login_info` 返回 `retcode:0`；AstrBot 返回 `data: {"type":"plain","data":"成功",...}`；群里发消息能在页面上看到（最直接的端到端验证）。

> 提示：组件控制页的「运行中」只表示**端口存活**。判断「能不能用」永远要用业务请求验证，这也是本页把自检写成接口调用而不是只看端口的原因。
