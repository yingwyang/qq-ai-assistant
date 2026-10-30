# Route A：每客户独立部署

目标：一套代码，**每个客户一套后端 + 独立数据库 + 独立 RabbitMQ vhost + 独立前端/域名 + 独立上传目录 + 独立 JWT 密钥**。客户之间不共享任何业务表，不依赖 `tenant_id`。

这是“企业多客户、各自功能互不影响”的推荐起步方案：隔离强、几乎不改现有代码；代价是每个客户一套进程/库，运维随客户数线性增长。

## 拓扑

```
客户 A 域名 ── Nginx(A) ── 后端 A:18101 ── MySQL db qqai_a / RabbitMQ vhost /qqai_a
                                   ├─ AstrBot A（或共享，但建议独立）
                                   ├─ NapCat A（独立 QQ 号/机器/容器）
                                   └─ GPT-SoVITS（可共享，TTS 输出各自目录）

客户 B 域名 ── Nginx(B) ── 后端 B:18102 ── MySQL db qqai_b / RabbitMQ vhost /qqai_b
                                   └─ ...
```

## 隔离边界

| 资源 | 本方案 | 说明 |
|---|---|---|
| 用户/会话/JWT | 独立库 + 独立 JWT_SECRET | 客户 A 的 Cookie 到 B 直接 401 |
| 群/消息/摘要/积分/订单 | 独立数据库 | 无跨客户表 |
| 异步队列 | 独立 RabbitMQ vhost | 队列/交换机不串 |
| 上传/媒体/TTS 输出 | 每客户独立目录 | 后端工作目录即客户目录 |
| 本地组件（NapCat/AstrBot/GSV） | `component.enabled=false`，全部外部托管 | 避免多个后端争 6099/6185/8000 端口 |
| 前端 | 独立静态目录 + 域名 | 同一份构建产物可复用 |
| AstrBot 会话 | 按客户独立实例最稳 | 共享 AstrBot 时它的会话/配额仍可能串，见“边界” |
| MySQL/RabbitMQ 服务器 | 可共享服务器、独立库/vhost；严格隔离则每客户独立实例 | 按合规要求选择 |

## 前置条件

- Windows Server（本目录脚本为 Windows 版；Linux 用 systemd 同理）
- JDK 21（`java` 在 PATH，或传 `-JavaExe`）
- MySQL 8（可远程）
- RabbitMQ 3.x
- Nginx
- 已构建：`backend/target/qq-ai-assistant-*.jar` 与 `frontend/dist/`
- 每个客户自己的 NapCat agent；AstrBot/GSV 地址（可共享或独立）

## 快速开始

### 1. 构建产物

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant
mvn -B -ntp package -DskipTests
cd frontend
npm run build
```

### 2. 生成客户目录

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant
powershell -ExecutionPolicy Bypass -File deploy\route-a\provision-customer.ps1 `
  -Customer acme `
  -Domain acme.example.com `
  -BackendPort 18101 `
  -MySqlAdminPassword '你的MySQL root密码' `
  -CreateDatabase
```

产物在 `D:\qqai-tenants\acme\`：

| 路径 | 用途 |
|---|---|
| `app/qq-ai-assistant.jar` | 后端 |
| `frontend/` | 静态前端 |
| `conf/application.properties` | 该客户全部配置（含独立密钥） |
| `conf/mysql-setup.sql` | 建库/建用户 SQL（未执行时手工执行） |
| `conf/rabbitmq-setup.txt` | 建 vhost/用户命令 |
| `conf/nginx-site.conf` | 该域名站点 |
| `run-backend.cmd` | 前台/服务启动入口 |
| `start-customer.ps1` / `stop-customer.ps1` / `status-customer.ps1` | 启停/探活 |
| `install-task.ps1` | 注册开机任务（可选） |
| `data/` `uploads/` `logs/` | 客户私有数据 |

### 3. 建库与 vhost

若没有用 `-CreateDatabase`，执行：

```powershell
mysql -u root -p < D:\qqai-tenants\acme\conf\mysql-setup.sql
```

RabbitMQ 管理员执行 `conf/rabbitmq-setup.txt` 里的命令。严格隔离时，把 `conf/application.properties` 里的 `spring.rabbitmq.host/port` 指向该客户独占 broker。

### 4. 配置外部服务

编辑 `conf/application.properties`：

```properties
astrbot.api-url=http://客户自己的AstrBot:6185
astrbot.token=...
astrbot.data-path=...        # 需要直连 AstrBot data_v4.db 的管理功能才填
napcat.api-url=http://客户NapCat:6100
napcat.token=...
napcat.webhook-token=...
napcat.self-qq=...
gpt-sovits.api-url=http://...
```

补充：`napcat.token` 与 `napcat.webhook-token` 由脚本随机生成；把这两个值填进该客户的 NapCat 配置（OneBot token / 回调 access_token 或 Bearer），否则消息收发会 401。`minio.access-key/secret-key` 默认 `minioadmin` 只是让应用能启动，生产请换成该客户自己的桶与凭据；`astrbot.data-path` 仅在需要直连 AstrBot data_v4.db 的管理功能时填写。

### 5. Nginx

把 `conf/nginx-site.conf` 放进 Nginx 的 `http {} include` 目录，检查并重载：

```powershell
nginx -t
nginx -s reload
```

HTTP 已经可用；生产请加 TLS（证书 + 443 server 块），然后把 `app.cookie.secure` 改成 `true` 并重启。

### 6. 启动与验收

```powershell
D:\qqai-tenants\acme\start-customer.ps1
D:\qqai-tenants\acme\status-customer.ps1
```

验收：

1. 浏览器打开 `http://acme.example.com`，能加载登录页；
2. 后端 `logs/backend.log` 有 `Started Application` 且无端口/组件报错；
3. 用另一个客户域名访问，拿不到对方数据（两个库/JWT 不同）；
4. 后端日志不应出现启动 NapCat/AstrBot/GSV（`component.enabled=false`）。

可选开机自启（管理员）：

```powershell
D:\qqai-tenants\acme\install-task.ps1
```

## 安全清单

- 每个客户 `JWT_SECRET` / DB 密码 / Rabbit 密码由脚本随机生成，**不要复用**；
- 生产必须 HTTPS，并设置 `app.cookie.secure=true`；
- `app.cors.allowed-origins` 只放该客户域名；
- Nginx 只反代 `/api`、`/images`、`/uploads`、`/ws`，不要暴露后端 181xx 到公网；
- 客户注册目前默认开放（`app.registration.enabled=true`）。企业内网/VPN 可接受；公网交付前应补邀请码或管理员建用户；
- 每个客户用独立 QQ 号/独立登录环境；同 IP 多 QQ 仍受腾讯风控；
- 定期备份：`mysqldump` + `uploads/` + `data/` + `conf/`（conf 含密钥，按机密文件保管）。

## 升级与回滚

1. 构建新 jar/dist；
2. `stop-customer.ps1`；
3. 备份数据库与 `uploads/data/conf`；
4. 重新执行 `provision-customer.ps1 ... -Force` 替换产物（会读取旧 `conf/application.properties`，保留数据库/队列/JWT/NapCat 密钥）；
5. `start-customer.ps1`，按验收步骤检查；
6. 失败则还原旧 jar/dist 与数据库备份。

注意：当前 `spring.flyway.enabled=false`，表结构靠 `ddl-auto=update` 自动演进；生产升级前应补正式 DDL 迁移（见项目待办）。

## 本地已验证

2026-10-30 在本机用临时客户 `routea-selftest` 走通：建独立库/用户与 RabbitMQ vhost/用户 → 生成配置 → 启动后端（端口 18081）→ `Started Application`、`/api/bots` 返回 401（鉴权生效）、组件注册 `enabled=false` 且未拉起 NapCat/AstrBot/GSV；随后已停止并清理测试库/vhost/目录。

## 已知边界

- 本方案解决“客户之间”隔离，**不解决客户内部多 bot 的媒体/AstrBot 会话隔离**（见 `frontend/src/docs/development/multi-bot.md`）；
- 共享 AstrBot/GSV 可以省资源，但 AstrBot 的会话历史、模型配额、人格配置仍是共享的；要求严格隔离就每客户一套；
- 注册准入、密码找回、登录审计仍是项目级缺口；
- Nginx TLS/证书续期、监控、日志轮转需按企业规范补齐；
- 本目录是 Windows Server 版本；Linux 用同样的配置键 + systemd 单元即可。
