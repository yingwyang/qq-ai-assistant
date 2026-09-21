---
title: 部署与运维
description: 环境要求、启动顺序、日志、备份与安全清单
updated: 2026-09-16
---

这一页面向部署者与值班运维：装什么、按什么顺序起、日志在哪、怎么重启、怎么备份、上线前必须改哪些东西。

## 一、环境要求

| 组件 | 版本 | 必需性 | 说明 |
|------|------|--------|------|
| JDK / Maven | 17+ / 3.8+ | 必需 | `pom.xml`：`<java.version>17</java.version>`，Spring Boot 3.2.0 |
| Node.js / npm | 18+ / 9+ | 必需（前端） | Vite 8 需要较新 Node |
| MySQL | 8.0+ | 必需 | 库名默认 `qq_chat`，字符集 `utf8mb4` |
| RabbitMQ | 3.12+ | 必需 | **后端启动的硬依赖**：连不上直接启动失败 |
| FFmpeg / Python | 近期版本 / 3.9+ | 语音功能需要 | `FFMPEG_PATH`（也可用 GPT-SoVITS 自带 `runtime/ffmpeg.exe`）；`PYTHON_EXECUTABLE` + `PYTHON_SILK_SCRIPT` 用于 SILK→MP3 兜底 |
| NapCat | OneBot 11 | 必需（收消息） | OneBot API `:6100`、WebUI `:6099` |
| AstrBot / GPT-SoVITS | — / v2Pro | AI、TTS 需要 | `:6185` / `:8000`，不装则对应功能不可用 |
| MinIO | 最新 | 可选 | 默认不用，走本地磁盘 `backend/uploads/` |

## 二、`.env` 必填项清单

后端通过 `spring-dotenv` 读取 **`backend/.env`**（与 jar / `mvn spring-boot:run` 的工作目录一致）：

```powershell
Copy-Item .env.example backend\.env    # 在项目根目录执行，然后填入真实值
```

### 启动即强校验（缺失/过短直接拒绝启动）

`config/SecurityPropertiesValidator.java` 在应用启动事件里校验，缺一项就抛异常退出：

| 变量 | 约束 | 缺失后果 |
|------|------|----------|
| `JWT_SECRET` | **≥ 32 字符**随机串 | 启动失败：`jwt.secret must be set and at least 32 characters long` |
| `NAPCAT_TOKEN` | 非空 | 启动失败；同时是调用 NapCat OneBot API 的令牌 |
| `NAPCAT_WEBHOOK_TOKEN` | 非空 | 启动失败；**必须与 NapCat 上报配置里的 token 完全一致** |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | 非空 | 启动失败（即便暂时不用 MinIO 也要给占位值） |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 可连通的 MySQL | 连接失败则启动失败 |

### 功能必需（不校验，缺了对应功能不可用）

| 变量 | 影响 |
|------|------|
| `ASTRBOT_TOKEN` | 摘要 / AI 对话全部失败；需在 AstrBot 后台创建 |
| `RABBITMQ_PASSWORD`、`RABBITMQ_HOST/PORT/USERNAME/VHOST` | 连接失败即启动失败（默认 `guest` / `localhost`） |
| `ADMIN_INIT_PASSWORD` | 不设则首次启动随机生成管理员密码并打印到日志 |
| `NAPCAT_SELF_QQ`、`SERVER_PORT` | 机器人 QQ 号、后端端口（默认 8081） |
| `ASTRBOT_SUMMARY_MODEL` | 留空 = 用 AstrBot 默认模型（不要硬编码模型名） |
| `FILE_DOWNLOAD_MAX_MB` / `FILE_DOWNLOAD_MAX_VIDEO_MB` | 媒体/视频下载上限，默认 100 / 300 |
| `JWT_REMEMBER_ME_EXPIRATION`、`APP_REGISTRATION_ENABLED` | 「记住我」有效期（默认 30 天）、是否开放注册 |

> ⚠️ `CORS_ALLOWED_ORIGINS` 目前**不生效**：`application.yml` 里 `app.cors.allowed-origins` 是硬编码列表（默认 `localhost` / `127.0.0.1` 的 5173–5176）。改白名单请直接改这一行。

## 三、启动顺序与命令

依赖关系：**MySQL + RabbitMQ 必须先就绪**（后端硬依赖）；NapCat / AstrBot / GPT-SoVITS 相互独立，但都由后端通过 HTTP 调用。

### 0. MySQL 与 RabbitMQ

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS qq_chat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
Get-Content backend\src\main\resources\init-mysql.sql | mysql -u root -p   # 建 9 张基础表，其余由 JPA 首启创建

docker compose up -d      # 一键起 MySQL + RabbitMQ（端口仅绑定 127.0.0.1）
# 或只起 RabbitMQ：docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 `
#   -e RABBITMQ_DEFAULT_USER=guest -e RABBITMQ_DEFAULT_PASS=<RABBITMQ_PASSWORD> --restart unless-stopped rabbitmq:3.12-management
```

验证：<http://127.0.0.1:15672> 能打开（`guest` / `<RABBITMQ_PASSWORD>`）。

### 1. NapCat（6100 API / 6099 WebUI）

WebUI <http://127.0.0.1:6099> 扫码登录机器人 QQ，然后配置 HTTP 客户端，把消息上报给**后端 8081**（不是前端 5173）：

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

> Webhook 指向后端，**后端没起时上报会失败（消息丢失）**：要么先起后端再配 Webhook，要么配好后立刻起后端。

### 2. AstrBot（6185）与 GPT-SoVITS（8000）

```bash
cd AstrBot && python main.py          # 或 Windows: .\Astrbot\AstrBot.exe（在 AstrBot 后台创建 Token → ASTRBOT_TOKEN）
```

```powershell
cd D:\ai\Documents\qq-web\GPT-SoVITS-v2pro-20250604-nvidia50
runtime\python.exe api_v2.py -a 127.0.0.1 -p 8000    # 模型路径与音色列表见 application.yml 的 gpt-sovits.*
```

### 3. 后端（8081）与前端（5173）

```powershell
cd backend
mvn spring-boot:run                      # 开发
mvn -q -DskipTests package               # 打包后常驻（推荐，见第五节）
java -jar target\qq-ai-assistant-1.0-SNAPSHOT.jar
```

```bash
cd frontend && npm install && npm run dev    # http://localhost:5173
```

启动成功标志：日志出现 `RabbitMQ 连接成功，所有 Exchange/Queue/Binding 已声明`；首次启动创建管理员时不打印密码，改为写入 `backend/data/initial-admin-password.txt`（日志只提示路径）。健康检查：公开的 `GET /api/system/health` 只探 NapCat 端口；**依赖（MySQL/RabbitMQ）状态看 `GET /actuator/health`，该端点仅 ADMIN 可读**。

> 三个外部组件也可由后端一键拉起：`POST /api/system/start-all`（ADMIN），内部顺序 **AstrBot → NapCat → GPT-SoVITS**，每步都有幂等检查（进程存活 / 端口已监听则跳过）。

## 四、构建命令

```powershell
cd backend;  mvn -DskipTests package     # 产物 target/qq-ai-assistant-1.0-SNAPSHOT.jar
cd ..\frontend; npm run build            # 产物 frontend/dist/
```

前端 dev 服务器已内置代理（`vite.config.js`）：`/api`、`/images`、`/uploads` → `http://localhost:8081`，`/ws` → `ws://localhost:8081`。生产环境由 Nginx 转发这三类路径并把 `/ws` 升级为 WebSocket。

## 五、日志位置

| 日志 | 路径 | 产生方式 |
|------|------|----------|
| 应用日志（主） | `backend/logs/application.log` | `logback-spring.xml` 的 `LOG_PATH=./logs`，**相对进程工作目录**，所以要在 `backend/` 下启动 |
| 滚动文件 | `backend/logs/application.<yyyy-MM-dd>.<i>.log` | 按天 + 单文件 20MB 滚动，保留 14 天，总量上限 500MB |
| 后端标准输出 | `backend/app-run.log`、`app-run.err.log`（jar）／`backend-run.log`、`backend-run.err.log`（`mvn spring-boot:run`） | 用 `Start-Process` 重定向时 |
| 前端 dev | Vite 输出到终端（可重定向 `frontend/dev.log`） | `npm run dev`；前端报错看浏览器 Console/Network |

日志格式：`2026-09-16 11:23:20.145 [http-nio-8081-exec-3] INFO  c.q.service.GroupTypeRecognitionService - 群类型识别完成 ...`

```powershell
Select-String -Path backend\logs\application.log -Pattern '【DLQ】|媒体下载完成|AstrBot 返回错误|Connection refused|401 UNAUTHORIZED|403 FORBIDDEN' |
  Select-Object -Last 30 | ForEach-Object { $_.Line }
```

## 六、端口清单

| 服务 | 端口 | 备注 |
|------|------|------|
| MySQL | 3306 | `docker-compose.yml` 仅绑定 `127.0.0.1` |
| RabbitMQ | 5672（AMQP）/ 15672（管理台） | 管理台 <http://127.0.0.1:15672> |
| 后端 API | 8081 | `SERVER_PORT` 可改 |
| 前端开发服务器 | 5173 | Vite（`server.host=true`，局域网可访问） |
| NapCat | 6100（OneBot API）/ 6099（WebUI） | Webhook 上报指向 **8081** |
| AstrBot | 6185 | 摘要 / 对话 |
| GPT-SoVITS | 8000 | TTS |
| MinIO | 9000（API）/ 9001（Console） | 可选 |

## 七、日常运维命令

### 重启后端

```powershell
Get-NetTCPConnection -LocalPort 8081 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }

cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Start-Process -FilePath "java" -ArgumentList "-jar","target\qq-ai-assistant-1.0-SNAPSHOT.jar" `
  -WorkingDirectory "D:\ai\Documents\qq-web\qq-ai-assistant\backend" `
  -RedirectStandardOutput "app-run.log" -RedirectStandardError "app-run.err.log" -WindowStyle Hidden
```

### 看队列 / 组件 / 磁盘

```powershell
curl.exe -s -u guest:guest "http://127.0.0.1:15672/api/queues/%2F" |
  ConvertFrom-Json | Select-Object name,messages,messages_ready,consumers | Format-Table -AutoSize

Test-NetConnection -ComputerName localhost -Port 6185 -InformationLevel Quiet   # 换 6100 / 8000 / 8081
curl.exe -s "http://localhost:8081/api/system/component-status"
curl.exe -s -b cookie.txt "http://localhost:8081/api/system/disk-usage"
```

### 看数据库连接数

```sql
SHOW STATUS LIKE 'Threads_connected';        -- 当前连接数
SHOW STATUS LIKE 'Max_used_connections';     -- 历史峰值
SHOW PROCESSLIST;                            -- 谁在连着、有无长事务
SELECT COUNT(*) AS unprocessed FROM qq_chat.messages WHERE processed = 0;
```

连接池是 Spring Boot 默认的 HikariCP（仓库未显式配置 `maximum-pool-size`，即默认 10）；JPA 批处理已开 `hibernate.jdbc.batch_size=50`。连接数打满时先查长事务与慢查询。

### 定时任务

消息归档 `archive.cron = 0 0 2 * * ?`（归档超过 `archive.days-before=90` 天的消息）；订阅过期扫描同为 `0 0 2 * * ?`（`PAID` 且已过期订单置 `EXPIRED`，用户 tier 降回 `FREE`）。手动触发：`POST /api/messages/archive?daysBefore=90`、`POST /api/admin/archive?days=90`（均需 ADMIN）。

## 八、备份与恢复

### 应用内备份（ADMIN）

| 操作 | 接口 | 落盘 |
|------|------|------|
| 触发备份 | `POST /api/admin/backup` | `backend/data/backups/backup_yyyyMMdd_HHmmss.sql` |
| 查看列表 | `GET /api/admin/backup/list` | 文件名、大小、修改时间 |
| 下载 | `GET /api/admin/backup/{fileName}/download` | 校验文件名，禁止 `..` 与路径分隔符 |

实现：H2 走 `SCRIPT TO`，其它数据库（含 MySQL）走 `exportViaJdbc` 逐表导出 INSERT。**只需** `data/backups` 目录可写。

### 生产建议：mysqldump + 目录打包

```powershell
mysqldump -u root -p --single-transaction --routines --triggers qq_chat > backup_qq_chat.sql
Compress-Archive -Path backend\uploads, backend\.env, backend\src\main\resources\application.yml `
  -DestinationPath backup_files.zip -Force
```

恢复：

```powershell
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS qq_chat CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
Get-Content backup_qq_chat.sql | mysql -u root -p qq_chat
# 再把 uploads 解压回 backend\uploads（库里的 content 是 /images/... 相对路径）
```

> 应用内备份是逐表 INSERT，恢复大库明显慢于 `mysqldump`；`uploads/images`、`uploads/avatars`、`uploads/tts` 必须与数据库**同批次**备份，否则会出现「消息在、图片 404」。

## 九、安全清单（上线前逐条核对）

| # | 项目 | 做法 |
|---|------|------|
| 1 | 敏感值只存在 `backend/.env` | 仓库内不得出现真实密钥（文档统一写 `<JWT_SECRET>` 这类占位符）；确认 `.env` 已被 `.gitignore` 忽略 |
| 2 | 必填环境变量校验 | 保留 `SecurityPropertiesValidator` 的强校验，不要注释掉——它是「配置缺失就拒绝启动」的最后防线 |
| 3 | 默认管理员密码随机化 | `DataInitializer` 仅在无 admin 时创建：优先 `ADMIN_INIT_PASSWORD`（≥8 位），否则随机 16 位并打印一次；**首次登录后立即改密** |
| 4 | `JWT_SECRET` 轮换 | 更换即所有登录态失效（签名不匹配 → 401），属预期；只需让单个用户下线时改用「改密码」（`tokenVersion+1`），不必换全局密钥 |
| 5 | NapCat 令牌轮换 | `NAPCAT_TOKEN` / `NAPCAT_WEBHOOK_TOKEN` 必须在 `.env` 与 NapCat 配置中**同时**更新，否则消息立刻中断（Webhook 401） |
| 6 | MinIO 凭据轮换 | MinIO Console 改密钥后同步 `.env` 的 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` 并重启后端 |
| 7 | CORS 白名单 | 改 `application.yml` 的 `app.cors.allowed-origins`（硬编码，环境变量无效），只允许真实前端来源；不要加 `*`（已开 `allowCredentials`） |
| 8 | HTTPS 与 Cookie | 生产走 HTTPS 后把 `AuthController` 中 Cookie 的 `secure(false)` 改为 `true` |
| 9 | 数据库 schema 策略 | 首次部署可用 `ddl-auto=update`，稳定后改 `validate`；需要版本化时启用 Flyway（`FLYWAY_ENABLED=true`） |
| 10 | 中间件暴露面 | `guest` 账号仅允许本机登录，远程部署另建用户；管理台与 3306/5672 不要暴露公网，后端与前端统一走反向代理 |
| 11 | 调试开关 | `app.debug.log-media-payload` 默认 `false`（会打印含聊天内容的完整载荷），排查完记得关 |

相关：[消息队列指南](rabbitmq-guide) · [排障手册索引](troubleshooting-playbook) · [架构说明](architecture)
