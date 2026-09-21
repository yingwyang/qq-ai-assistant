---
title: 系统配置
description: 端口、配置文件位置、必填环境变量与启动顺序
updated: 2026-09-16
---

这一页解决一个问题：**把这套系统跑起来，并且知道每个组件到底有没有正常工作**。面向自建部署的用户（含在自己电脑上单机部署）。

## 组件与端口总表

| 组件 | 端口 | 作用 | 是否必需 |
|------|------|------|----------|
| MySQL 8.0 | 3306 | 消息、用户、积分、订单等全部业务数据 | 必需 |
| RabbitMQ | 5672（AMQP）/ 15672（管理界面） | 媒体下载、语音转码、AI 分析、广播的异步队列 | 必需（后端启动的硬依赖） |
| 后端 API | 8081 | 业务接口 + NapCat Webhook 接收端 | 必需 |
| 前端页面 | 5173（开发服务器） | 用户与管理员界面 | 必需 |
| NapCat WebUI | 6099 | 扫码登录机器人 QQ、配置 Webhook | 必需 |
| NapCat OneBot API | 6100 | 后端调用它拉取媒体、查群列表 | 必需 |
| AstrBot | 6185 | AI 对话与摘要 | 可选（不装则无 AI 能力） |
| GPT-SoVITS | 8000 | TTS 语音合成 | 可选 |
| MinIO | 9000（API）/ 9001（Console） | 对象存储（默认不用，走本地磁盘） | 可选 |

## 配置文件在哪

| 文件 | 管什么 | 要不要改 |
|------|--------|----------|
| `.env.example` → **`backend/.env`** | 全部敏感值与部署相关开关（数据库密码、各类令牌、端口、体积上限） | **必须**：复制模板并填真实值 |
| `backend/src/main/resources/application.yml` | 端口、路径、超时、队列、归档策略等业务配置 | 按需 |
| `backend/src/main/resources/prompts.yml` | 各群类型下的 AI 提示词模板 | 想调 AI 风格时改 |
| `docker-compose.yml` | 用 Docker 一键起 MySQL + RabbitMQ | 用 Docker 时改 |

后端通过 `spring-dotenv` 读取 **`backend/.env`**（推荐做法，与 jar / `mvn spring-boot:run` 的工作目录一致）：

```powershell
# 在项目根目录执行
Copy-Item .env.example backend\.env
# 然后用编辑器填写 backend\.env 中的占位值
```

## `.env` 必填项

以下变量缺失或不合规时，后端会**直接拒绝启动**（启动阶段强校验），不会静默降级：

| 变量 | 说明 |
|------|------|
| `JWT_SECRET` | 登录令牌签名密钥，**至少 32 字符**的随机串 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接串与账号密码 |
| `RABBITMQ_PASSWORD` | RabbitMQ 密码（用户名默认 `guest`） |
| `NAPCAT_TOKEN` | 后端调用 NapCat OneBot API 的令牌 |
| `NAPCAT_WEBHOOK_TOKEN` | 校验 NapCat 上报来源的令牌，**必须与 NapCat 配置里的一致** |
| `ASTRBOT_TOKEN` | 调用 AstrBot 的令牌（在 AstrBot 后台创建） |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 凭据（配置了 MinIO 时必填） |

常用可选项：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SERVER_PORT` | 8081 | 后端端口 |
| `ADMIN_INIT_PASSWORD` | 随机生成并写入 `backend/data/initial-admin-password.txt`（不打印到日志） | 初始管理员 `admin` 的密码 |
| `APP_COOKIE_SECURE` | `false` | `qqai_token` Cookie 是否仅走 HTTPS；**生产 HTTPS 部署必须设为 true** |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` 等 | 允许跨域的前端地址，多个用逗号分隔 |
| `ASTRBOT_SUMMARY_MODEL` | 空（用 AstrBot 默认模型） | 摘要专用模型 |
| `JWT_REMEMBER_ME_EXPIRATION` | `2592000000`（30 天） | 登录页勾选「记住我」后的有效期（毫秒） |
| `FILE_DOWNLOAD_MAX_MB` | 100 | 单个媒体文件下载体积上限（MB） |
| `FILE_DOWNLOAD_MAX_VIDEO_MB` | 300 | 单个视频下载体积上限（MB） |
| `NAPCAT_SELF_QQ` | 模板中需自行填写 | 机器人自己的 QQ 号 |
| `APP_REGISTRATION_ENABLED` | `true` | 是否开放自助注册 |

> 提示：文档与仓库里都不应出现真实密钥。填写时统一用 `<JWT_SECRET>`、`<NAPCAT_TOKEN>` 这类占位写法记录，真实值只放在 `backend/.env`，且该文件不进版本库。

## 启动顺序

严格按依赖顺序启动，中间任何一步失败都不要继续往下：

1. **MySQL**：确认 3306 可连，并已执行过初始化脚本。

    ```powershell
    Get-Content backend\src\main\resources\init-mysql.sql | mysql -u root -p
    ```

2. **RabbitMQ**：确认 5672 与 15672 可访问（用 Docker 最省事）。

    ```powershell
    docker compose up -d        # 一键起 MySQL + RabbitMQ
    ```

3. **AstrBot**（可选）：默认 6185。

    ```bash
    cd AstrBot && python main.py
    ```

4. **NapCat**：先起服务，登录机器人 QQ。

    ```text
    WebUI:  http://127.0.0.1:6099   （扫码登录机器人 QQ）
    OneBot: http://localhost:6100   （后端调用）
    ```

5. **后端**：先确认 `backend/.env` 已填好，再启动。

    ```powershell
    cd backend
    mvn spring-boot:run
    # 或打包后运行
    mvn clean package -DskipTests
    java -jar target\qq-ai-assistant-1.0-SNAPSHOT.jar
    ```

6. **前端**：默认 5173。

    ```bash
    cd frontend
    npm install
    npm run dev
    ```

首次启动后端时，若数据库里没有 ADMIN 用户，系统会创建初始管理员：随机密码写入 `backend/data/initial-admin-password.txt`（**不打印到日志**，控制台只提示文件路径）；若设置了 `ADMIN_INIT_PASSWORD` 则用该值。请登录后立即修改密码并删除该文件。

## 配置 NapCat Webhook（最关键的一步）

NapCat 必须把消息**上报给后端 8081**，否则网页端永远没有消息。在 NapCat WebUI 中配置 HTTP 客户端：

```json
{
  "network": {
    "httpClients": [
      {
        "enable": true,
        "name": "qq-chat",
        "url": "http://localhost:8081",
        "reportSelfMessage": true,
        "messagePostFormat": "array",
        "token": "<NAPCAT_WEBHOOK_TOKEN>"
      }
    ]
  }
}
```

三个要点：

1. `url` 必须指向**后端**（8081），不是前端 5173。
2. `token` 必须与 `backend/.env` 里的 `NAPCAT_WEBHOOK_TOKEN` **完全一致**，否则后端会拒绝该上报。
3. `reportSelfMessage: true` 用于同时接收机器人自己发送的消息。

## 怎么确认每个组件正常

| 组件 | 确认方式 | 正常表现 |
|------|----------|----------|
| MySQL | `mysql -h 127.0.0.1 -P 3306 -u <DB_USERNAME> -p -e "SELECT COUNT(*) FROM qq_chat.messages;"` | 能返回行数（新库为 0） |
| RabbitMQ | 浏览器打开 `http://localhost:15672`（默认 `guest` / `<RABBITMQ_PASSWORD>`） | 能看到 4 个业务队列：`media.download.queue`、`voice.transcode.queue`、`ai.analysis.queue`、`broadcast.queue` |
| 后端 | `Invoke-RestMethod http://localhost:8081/api/system/health` | 返回 `status: ok`、`timestamp`，以及 `napcat: available`（这只说明后端进程活着 + NapCat 端口可探） |
| 依赖健康 | 带管理员 Cookie 访问 `http://localhost:8081/actuator/health` | 返回 `components.db`（MySQL）、`components.rabbit`（RabbitMQ）均为 `UP`；该端点仅 ADMIN 可读（匿名 401） |
| 组件状态 | `Invoke-RestMethod http://localhost:8081/api/system/component-status` | 返回 `astrbot` / `napcat` / `gptsovits` 三个对象的 `running` 与 `status` |
| NapCat | WebUI `http://127.0.0.1:6099` 显示已登录；OneBot 6100 端口可访问 | 机器人头像在线，能收到群消息 |
| AstrBot | 浏览器打开 `http://localhost:6185` | 能进入 AstrBot 控制台 |
| GPT-SoVITS | 8000 端口已监听，并在 AI 回复处点一次「语音生成」 | 生成成功并可直接播放 |
| 前端 | 浏览器打开 `http://localhost:5173` | 登录页右上角三个状态灯（NapCat / AstrBot / GPT-SoVITS）显示「运行中」 |

> 提示：登录页的状态灯是**端口级检测**（6099 / 6185 / 8000），只能说明进程活着；机器人 QQ 是否真的登录成功，请看管理后台「组件控制」里的登录状态。

## 常见启动失败对照

| 现象 | 原因 | 处理 |
|------|------|------|
| 后端启动即退出，日志提示缺少密钥/令牌 | `.env` 必填项缺失或 `JWT_SECRET` 少于 32 字符 | 补齐 `backend/.env` 后重启 |
| 后端起不来，日志有 RabbitMQ 连接错误 | RabbitMQ 未启动或密码不符 | 启动 RabbitMQ 并核对 `RABBITMQ_*` |
| 前端能开但没有群消息 | NapCat Webhook 未配置 / token 不一致 | 按上一节配置并保证 token 一致 |
| AI 相关操作全部失败 | AstrBot 未启动或 `ASTRBOT_TOKEN` 不匹配 | 启动 AstrBot 并核对令牌 |
| 队列里 `*.dlq` 有堆积 | 对应链路反复失败（媒体下载 / 语音转码 / AI 分析） | 见 [消息与媒体](messages)、[AI 摘要](ai-summary) |
| 页面接口返回 401/403 | 登录态过期，或该接口仅管理员可用 | 重新登录；管理端功能需 ADMIN 角色 |
