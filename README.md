# QQ AI Assistant

QQ AI Assistant 是一款基于 NapCat + AstrBot 的 QQ 群智能管理平台，集成了消息实时收发、AI 对话、智能摘要、TTS 语音合成、积分订阅体系和管理后台等功能。

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3.5 · Vue Router 4 · Vite 8 · ECharts 6 |
| 后端 | Spring Boot 3.2.0 · Java 17 · Spring Data JPA |
| 数据库 | MySQL 8.0 · H2 (开发) |
| 消息队列 | RabbitMQ 3.12 |
| 对象存储 | MinIO |
| 安全 | JWT（HttpOnly Cookie 承载） + Spring Security 权限矩阵 |
| AI 引擎 | AstrBot（对话/摘要） · GPT-SoVITS（语音合成） |
| QQ 协议 | NapCat (OneBot 11) |
| 插件 | Groovy 脚本引擎 |

## 目录结构

```
qq-ai-assistant/
├── backend/                     # Spring Boot 后端
│   ├── src/main/java/com/qqai/
│   │   ├── controller/          # REST API 控制器 (21个)
│   │   ├── service/             # 业务逻辑层 (29个)
│   │   ├── entity/              # JPA 实体 (16个表)
│   │   ├── repository/          # 数据访问层 (16个)
│   │   ├── config/              # Spring 配置类
│   │   ├── security/            # JWT 认证模块
│   │   ├── consumer/            # MQ 消费者 (4个)
│   │   ├── websocket/           # WebSocket 处理
│   │   ├── plugin/              # Groovy 插件引擎
│   │   ├── exception/          # 异常处理体系
│   │   ├── common/             # 通用工具
│   │   └── dto/                # 数据传输对象
│   ├── src/main/resources/
│   │   ├── application.yml.example   # 配置模板
│   │   ├── db/migration/            # Flyway 迁移脚本
│   │   ├── init-mysql*.sql          # 数据库初始化脚本
│   │   └── prompts.yml             # AI 提示词模板
│   ├── plugins/                 # Groovy AI 插件 (4个)
│   ├── scripts/                 # Python 辅助脚本
│   └── pom.xml
│
├── frontend/                    # Vue.js 前端
│   ├── src/
│   │   ├── views/               # 页面视图 (5个)
│   │   ├── views/admin/         # 管理后台子页 (13个)
│   │   ├── components/          # UI 组件 (17个)
│   │   ├── components/credits/ # 积分/订阅组件
│   │   ├── composables/        # 组合式函数 (20个)
│   │   ├── services/api.js     # API 请求封装
│   │   ├── router/index.js     # 路由配置
│   │   ├── docs/               # 内置 Markdown 文档 (10个)
│   │   └── utils/              # 工具函数
│   ├── public/                  # 静态资源
│   ├── index.html
│   ├── vite.config.js
│   └── package.json
│
├── doc/                         # 项目文档
│   ├── README.md                # 详细技术文档
│   ├── CODE_WIKI.md             # 代码百科
│   ├── DEFENSE_GUIDE.md         # 安全加固说明
│   ├── MEDIA_TROUBLESHOOTING.md # 媒体（图片/语音/视频）入库排查手册
│   ├── AI_SUMMARY_TROUBLESHOOTING.md # AI 摘要链路排查手册
│   └── user-manual.md           # 用户手册
│
├── .env.example                 # 环境变量模板
├── docker-compose.yml           # Docker 部署编排
├── .gitignore
└── README.md                    # 本文件
```

## 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | Java 开发环境 |
| Node.js | 18+ | Node 运行环境 |
| npm | 9+ | 包管理器 |
| MySQL | 8.0+ | 主数据库 |
| RabbitMQ | 3.12+ | 消息队列 |
| MinIO | 最新版 | 对象存储（可选） |
| Maven | 3.8+ | 后端构建工具 |
| Git | 2.x | 版本控制 |

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd qq-ai-assistant
```

### 2. 初始化数据库

```bash
# 登录 MySQL 并执行初始化脚本
mysql -u root -p
SOURCE backend/src/main/resources/init-mysql.sql
```

### 3. 配置后端（环境变量 / `.env`）

所有敏感配置均通过**环境变量**注入，仓库内不再保存任何真实密钥（启动时会强制校验，缺失即拒绝启动）：

```bash
# 复制环境变量模板并填入真实值
cp .env.example backend/.env
```

`backend/.env` 中**必须配置**（否则启动失败）：

| 变量 | 说明 |
|------|------|
| `JWT_SECRET` | JWT 签名密钥，**至少 32 字符**的随机串 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接（MySQL） |
| `NAPCAT_TOKEN` | 调用 NapCat OneBot API 的令牌 |
| `NAPCAT_WEBHOOK_TOKEN` | 校验 NapCat 上报的令牌 |
| `ASTRBOT_TOKEN` | 调用 AstrBot API 的令牌（在 AstrBot 后台创建） |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | MinIO 凭据（使用 MinIO 时必填） |

常用可选变量：`RABBITMQ_PASSWORD`、`ADMIN_INIT_PASSWORD`（初始管理员密码）、`CORS_ALLOWED_ORIGINS`、`ASTRBOT_SUMMARY_MODEL`、`FILE_DOWNLOAD_MAX_VIDEO_MB`。

> 其余业务配置（端口、路径、超时等）仍可在 `backend/src/main/resources/application.yml` 中调整。

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
# 后端启动成功: http://localhost:8081
```

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
# 前端启动成功: http://localhost:5173
```

### 6. 登录系统

- 访问 http://localhost:5173
- 初始管理员账号 `admin`，密码来自：
  - 环境变量 `ADMIN_INIT_PASSWORD`（若已设置），或
  - **首次启动日志中打印的随机密码**（形如 `【安全】已创建初始管理员账号 admin,初始密码: xxxxxxxx`）
- 也可以直接注册普通账号使用（`app.registration.enabled` 控制是否开放注册）
- **首次登录后请立即修改密码**（登录态由 HttpOnly Cookie 承载，登出会注销 JWT）

## 构建部署

### 后端打包

```bash
cd backend
mvn clean package -DskipTests
# 产物: target/qq-ai-assistant-1.0-SNAPSHOT.jar
```

运行：
```bash
java -jar target/qq-ai-assistant-1.0-SNAPSHOT.jar
```

### 前端打包

```bash
cd frontend
npm run build
# 产物: dist/ 目录
```

部署：使用 Nginx 或其他 Web 服务器托管 `frontend/dist/` 目录。

### Docker 部署

```bash
# 修改 docker-compose.yml 中的环境变量
docker-compose up -d
```

## 功能模块

| 模块 | 说明 |
|------|------|
| QQ 消息管理 | 实时接收/发送 QQ 群消息，支持图片/视频/语音/CQ 码 |
| AI 智能摘要 | 消息入库即自动生成结构化摘要（标签/情感/摘要），失败自动重试与死信兜底 |
| AI 对话 | 支持多模型对话、上下文管理、人格切换 |
| TTS 语音合成 | GPT-SoVITS 语音合成，支持多种角色 |
| 积分系统 | 积分消耗/获取/签到/阶梯折扣/tier 折扣 |
| 月卡订阅 | 小月卡/大月卡/ALL 全功能版，支持退款与纠纷处理 |
| 管理后台 | 用户管理、订单审批、积分调账、组件控制（已拆分为 13 个懒加载子页） |
| 媒体管理 | 文件存储、清理、归档；视频原片缺失时自动落盘 QQ 缩略图作预览 |
| 插件系统 | Groovy 脚本扩展 AI 回复格式化 |

## 文档

| 文档 | 说明 |
|------|------|
| [doc/README.md](doc/README.md) | 详细技术文档（架构/数据库/API/配置/部署） |
| [doc/CODE_WIKI.md](doc/CODE_WIKI.md) | 代码百科（包结构/类职责/接口说明） |
| [doc/DEFENSE_GUIDE.md](doc/DEFENSE_GUIDE.md) | 安全加固说明（密钥管理/权限矩阵/鉴权链路） |
| [doc/MEDIA_TROUBLESHOOTING.md](doc/MEDIA_TROUBLESHOOTING.md) | **媒体入库排查手册**（图片/语音/视频，含故障复盘与速查表） |
| [doc/AI_SUMMARY_TROUBLESHOOTING.md](doc/AI_SUMMARY_TROUBLESHOOTING.md) | **AI 摘要链路排查手册**（含故障复盘与批量补摘要方法） |
| [doc/user-manual.md](doc/user-manual.md) | 用户操作手册 |
| [frontend/src/docs/](frontend/src/docs/) | 前端内置帮助文档（系统内访问） |

## 常见故障排查（五分钟定位）

出问题先按"**日志 → 数据库 → 消息队列 → 外部组件**"四层定位，每层都有明确观测点：

| 症状 | 先看什么 | 详细步骤 |
|------|----------|----------|
| 图片/语音/视频没存下来，消息显示 `[视频已过期]` 之类占位符 | 数据库 `messages.content`、后端日志关键词 `媒体下载完成`/`【DLQ】`、磁盘 `backend/uploads/images/` | [媒体排查手册](doc/MEDIA_TROUBLESHOOTING.md) |
| 消息没有 AI 摘要 / 日志刷 `【DLQ】AI分析彻底失败` | `messages.ai_summary`、日志关键词 `AI分析完成`/`AstrBot 返回错误`/`Connection refused`、`ai.analysis.queue` 深度 | [AI 摘要排查手册](doc/AI_SUMMARY_TROUBLESHOOTING.md) |
| 页面白屏、接口 401/403 | 后端日志与浏览器控制台；确认登录态（`qqai_token` Cookie）与接口权限矩阵 | [doc/DEFENSE_GUIDE.md](doc/DEFENSE_GUIDE.md) |
| 消息完全不入库 | NapCat 是否运行（6100）、`NAPCAT_WEBHOOK_TOKEN` 是否与 NapCat 配置一致 | [doc/README.md](doc/README.md) §常见问题 |

> 排查要点：**先对时间线**（哪次改动/组件升级之后开始出问题），再按上述分层看日志；后端建议以 jar 方式常驻并把日志重定向到文件（见媒体手册 §5）。

## 常用端口

| 服务 | 端口 |
|------|------|
| 前端开发服务器 | 5173 |
| 后端 API | 8081 |
| MySQL | 3306 |
| RabbitMQ | 5672 (AMQP) / 15672 (管理) |
| MinIO | 9000 (API) / 9001 (Console) |
| NapCat | 6099 |
| AstrBot | 6185 |
| GPT-SoVITS | 8000 |

## 项目亮点

1. **多模型 AI 对话** — 支持自定义 API Key 和模型，按 tier 阶梯计费
2. **异步消息处理** — RabbitMQ 解耦 AI 分析、媒体下载、语音转码等任务（含重试与死信兜底）
3. **完整的订阅体系** — 月卡叠加、按比例退款、自动过期扫描
4. **Groovy 插件引擎** — 运行时动态加载 AI 回复格式化插件
5. **安全加固** — 密钥全部环境变量化并启动强校验、JWT 走 HttpOnly Cookie、接口权限矩阵（管理端收口 ADMIN）、SSRF/越界/防伪造 CQ 码防护
6. **可运维性** — 媒体与 AI 两条链路都有独立排查手册（五分钟四步定位），关键失败点日志可直接指到根因
7. **NapCat 协议封装** — 完整的 OneBot 11 协议支持
8. **Flyway 数据库版本迁移** — 规范的 schema 演进

## 近期重要变更

| 日期 | 变更 |
|------|------|
| 2026-09-15 | 修复**视频无法入库**（QQ 仅保留缩略图 → 增加可信来源校验与三级取源 + 缩略图兜底）；新增[媒体排查手册](doc/MEDIA_TROUBLESHOOTING.md) |
| 2026-09-15 | 修复 **AI 摘要全量失败**（SSE 响应解析缺失 / 请求缺 `username` / 错误响应被当摘要）；新增[AI 摘要排查手册](doc/AI_SUMMARY_TROUBLESHOOTING.md) |
| 2026-09-15 | 安全整改：密钥移出仓库并强制环境变量校验、默认管理员密码随机化、订阅改为"待管理员确认"、媒体不再公开、权限矩阵收口；本地 git 历史已清洗 |
| 2026-09-15 | 前端管理后台从 4600 行单文件拆分为 **1 个壳 + 13 个懒加载子组件**；修复登录后白屏与共享样式失效 |

## 许可证

本项目仅供学习和毕业设计使用。
