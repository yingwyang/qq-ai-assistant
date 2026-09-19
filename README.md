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

> 上表里除了「自研的 Spring Boot 后端 + Vue 前端 + 4 个 Groovy 插件脚本」之外，全部是第三方：
> AstrBot、NapCat、GPT-SoVITS、MySQL、RabbitMQ、MinIO 需要单独安装运行；前端还用到
> markdown-it（Markdown 渲染）、DOMPurify、ECharts 等。**版本、用途与许可证逐项列在
> [第三方组件与外部依赖](#第三方组件与外部依赖)**。

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
│   │   ├── views/admin/         # 管理后台子页 (14个)
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

常用可选变量：`RABBITMQ_PASSWORD`、`ADMIN_INIT_PASSWORD`（初始管理员密码）、`CORS_ALLOWED_ORIGINS`、`ASTRBOT_SUMMARY_MODEL`、`FILE_DOWNLOAD_MAX_VIDEO_MB`、`JWT_REMEMBER_ME_EXPIRATION`（勾选"记住我"后的登录有效期，默认 2592000000 = 30 天）。

> **图片识别（AI 摘要 / AI 分析要看图）还依赖 AstrBot 侧的两个配置**：一个主模型支持图片的配置档案（默认名 `vision`）和一个"不挂工具"的档案（默认名 `vision-task`，人格 `tools=[]`）。
> 缺了它们，图片消息的摘要会退化成「内容未知」、分析里只剩 `[图片]`。一键补齐（幂等）：
>
> ```bash
> python backend/scripts/ensure_astrbot_vision_task.py   # 之后重启 AstrBot
> ```
>
> 档案名可用 `ASTRBOT_VISION_CONFIG_NAME` / `ASTRBOT_VISION_TASK_CONFIG_NAME` 覆盖，详见 `doc/AI_SUMMARY_TROUBLESHOOTING.md` §7。

> **双层提示词（人 × 岗位）**：任务规则与输出格式由本后端 `prompts.yml` 提供（**岗位**），说话方式由 AstrBot 人格提供（**人**）。
> 用户在 AI 对话面板 → ⚙️ → 「人格与状态」里选一个人格即可，摘要 / 分析 / 群日报 / 对话全部生效；列表里能展开看人设全文与规范自检结果。
> 后端会为每个人格生成一份 `tools=[]` 的副本（避免模型去调工具把「请稍等片刻」当回答）与 `qqai-p-<slug>-text|-image` 配置档案；
> 首次启用某个人格需要重启 AstrBot（约 10–60 秒），之后切换即时生效。底座档案与系统人格名可用
> `ASTRBOT_PERSONA_TEXT_BASE` / `ASTRBOT_PERSONA_IMAGE_BASE` / `astrbot.internal-persona-ids` 调整，详见 `doc/AI_SUMMARY_TROUBLESHOOTING.md` §8。
>
> **人格库与编写规范**：人设文件放在 `doc/personas/*.md`（文件即事实来源），写法见 `doc/PERSONA_AUTHORING_GUIDE.md`；
> 应用方式 `python backend/scripts/apply_astrbot_personas.py --apply` + 重启 AstrBot，或调 `POST /api/astrbot/personas/sync` 一次同步所有副本。

> **转发到群聊**：AI 对话面板里每条消息（AI 回复与自己的发言）都能一键 **「转发到群」** ——
> 弹窗选群（可搜群名/群号，也可手填群号）、按需改内容、默认清理 Markdown 标记（QQ 不渲染 Markdown），
> 以机器人账号发出，走与群聊面板发送框相同的权限与限流（10 次/分钟、单条 2000 字，超长自动按行分段）。
> 端到端回归脚本：`node frontend/scripts/e2e-forward-to-group.mjs <JWT>`（用 mock 拦住真实发送，不会往群里发测试消息）。

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
| AI 智能摘要 | **按需生成**结构化摘要（标签/情感/摘要）：**单条**（消息悬停「AI 摘要」按钮）、**批量**（后台 数据维护 → AI 摘要，带参数/预估/进度/停止/每日额度）、**群日报**（聊天页顶部「今日速览」，一次调用覆盖整群一天）；消息入库**不再**自动调用 LLM（省模型额度） |
| AI 对话 | 支持多模型对话、上下文管理、人格切换 |
| TTS 语音合成 | GPT-SoVITS 语音合成，支持多种角色 |
| 积分系统 | 积分消耗/获取/签到/阶梯折扣/tier 折扣 |
| 资金流水（后台） | 在积分流水上叠一层**模拟现金账**：订阅按套餐价计收入、退款按比例计支出、AI/TTS 按积分成本折算；含 KPI 卡、现金收支趋势/构成/类型分布三张图、现金列与类别标签，管理员可**手工记一笔**（`POST /api/credits/admin/cash`，不改积分）。现金不写库、读取时推算，改规则即改口径 |
| 月卡订阅 | 小月卡/大月卡/ALL 全功能版，支持退款与纠纷处理；月卡每日额外积分（默认小 +100 / 大 +300）随**每日签到**发放，**双持叠加**（默认 +400）。**价格、赠送积分、每日加成都在后台「规则配置 → 会员月卡」里可改**，改完客户端订阅页与模拟现金账同步变化 |
| 管理后台（`/admin`） | 14 个懒加载子页，按 概览 / 用户 / 系统 / 积分管理 四组排列；**位置写在地址栏 `?tab=` 上**（刷新续接、可分享深链接，如 `?tab=credit-transactions&userId=5`），只加载当前页面需要的接口。统一页头 + 暗色主题 + 破坏性操作一律走弹窗确认（不用浏览器原生 `confirm`）。用户管理支持角色/状态筛选与批量启停、导出 CSV；用户积分支持层级/余额区间筛选与批量调账；系统日志支持关键字/时间范围/自动刷新/导出；媒体管理带缩略图懒加载与清理预览；数据维护支持删除备份与归档预检；**AI 摘要**独立成页；**积分规则配置**为分节导航 + 结构化行编辑器（模型费率/分析倍率/阶梯折扣不再手写 JSON）+ 未保存脏标记 + 内联校验 + **费用试算器** |
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
| [frontend/src/docs/](frontend/src/docs/) | **站内使用文档**（24 篇，四大分类：快速入门 / 使用指南 / 常见问题 / 开发文档）。访问 `/docs` 免登录可读，支持分类侧栏、页内索引、站内搜索与暗色主题；页面结构定义见 `frontend/src/docs/tree.js` |
| [frontend/src/docs/development/ai-summary-design.md](frontend/src/docs/development/ai-summary-design.md) | **AI 摘要实装方案（设计稿，未实装）**：现状缺口、结构化数据模型、单条/批量/群日报接口、前端交互、风控配额与分阶段落地计划 |

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
9. **站内文档体系** — 24 篇结构化文档（快速入门 / 使用指南 / 常见问题 / 开发文档），免登录可读，支持站内搜索、页内索引与暗色主题
10. **可观测的登录页** — 登录页直接展示 NapCat / AstrBot / GPT-SoVITS 的实时状态灯，机器人掉线一眼可见（进程级检测）

## 近期工作总结（2026-09-15 ~ 09-16）

> 一轮集中的**稳定性 + 体验**改造：修掉 4 个导致功能不可用的链路故障、补齐安全整改收尾、重做文档与登录两个页面体系、清理 12 个前端缺陷，新增 24 篇站内文档。下面每一条都可在代码、日志或界面中复核。

### 一、消息与媒体链路

| 故障 | 根因 | 处置 | 复核方式 |
|------|------|------|----------|
| 视频全部存不下来 | QQ 只保留视频缩略图、原片直链过期；取源被"仅允许 uploads 目录"拦住；`get_file` 分支从未走到 | 三级取源（CQ 直链 → NapCat `get_file` → 缩略图兜底）+ 可信来源标记 + 已知媒体路径白名单 | 实测落盘 `.mp4`（`messages.id=84852`）与缩略图兜底 `<uuid>.png`（68KB） |
| 媒体失败后无提示 | 重试 3 次耗尽直接丢弃 | DLQ 兜底写占位符，前端展示"仅预览/已过期" | 见[媒体排查手册](doc/MEDIA_TROUBLESHOOTING.md) |
| 队列被自动摘要灌爆 | 每条消息入库即投一次 LLM 调用（实测单队列积压 149~170 条） | **移除入库自动投递**，摘要改为按需 | 60 秒窗口：新到 75 条消息，队列恒为 0、无新增摘要 |

### 二、AI 能力

- **AI 摘要全量失败** → 三处修复：响应按 SSE 流解析（`data:{"type":"plain"}`）、请求补必填 `username`、显式拒绝 `status=error`/`retcode!=0`（避免把报错文案当摘要写库）。验证：`messages.id=84972` 结构化摘要落库。
- **群类型识别恒返回"其他群 / 置信度 0%"** → 该服务自写了一套 AstrBot 调用（缺 `username` + 未解析 SSE），改为复用 `AstrBotService.chat()`；同时**改为识别成功后才扣积分**、失败结果不进 24h 缓存。验证：同一群识别为 `HOBBY 0.82`（16.4s）；空群返回 `RECOGNITION_FAILED` 且无积分流水。
- **摘要触发方式**：入库自动摘要已移除，保留管理员批量入口 `POST /api/messages/process`；完整的单条按需 / 批量工具 / 群日报方案见[设计稿](frontend/src/docs/development/ai-summary-design.md)。

### 三、安全与权限（收尾）

密钥全部环境变量化并在启动时强校验；初始管理员密码随机化（可用 `ADMIN_INIT_PASSWORD` 指定）；订阅购买改为"待管理员确认"；媒体不再公开（改 Cookie 鉴权）；组件启停 / 头像 / 人格写操作收口 ADMIN；WebSocket 握手 token 校验 fail-closed；登录限流 5 次/分钟 → 429；本地 git 历史已清洗。

本轮新增：**管理员重置密码** `POST /api/admin/users/{id}/reset-password`（重置后 `tokenVersion+1` 使该用户旧会话立即失效，写审计日志）+ 后台「用户管理」按钮，打通"忘记密码"闭环。

### 四、积分与订阅

- 月卡每日额外积分**从"登录静默发放"改为"随每日签到发放"**：小月卡 +100、大月卡 +300、**双持叠加 +400**；签到记录存合计值，流水分两笔（`SIGN_IN` 基础分 + `MONTHLY_CARD_DAILY` 月卡加成）便于审计；识别/发放失败不再扣费。
- **双持展示修正**：`ALL`（全功能版）徽章与方案名使用蓝色；权益文案合并为「11000 积分基础 · 每日签到额外 +400（小100 + 大300 叠加） · 全模型支持」。
- 购买与管理员补单不再"即发"首日积分，权益口径统一为"签到发放"。

### 五、前端缺陷修复（17 项）

| 缺陷 | 说明 |
|------|------|
| 登录页信息量少、空旷 | 改为双栏：左侧品牌 + 能力清单 + **实时组件状态灯**（NapCat / AstrBot / GPT-SoVITS），右侧登录卡；窄屏自动退回单卡 |
| 无「记住我」 | 新增勾选：30 天（`JWT_REMEMBER_ME_EXPIRATION` 可配）／不勾选 24 小时；实测 Cookie 有效期 30.00 / 1.00 天 |
| 无「忘记密码」入口 | 登录页入口 + 弹窗指引，配合上节的重置密码接口形成闭环 |
| 暗色主题缺失 | 登录页 / 文档页 / 用户菜单弹层全套适配；`color-scheme` 改为跟随应用主题（原生控件不再跟随系统） |
| 用户菜单主题开关被删 | 装回「深色模式 / 浅色模式」；并修掉弹层 `Teleport` 到 body 后拿不到主题变量、**暗色下永远白底**的老问题 |
| 群类型弹窗高亮不跟随 | 高亮与对勾绑在"已保存类型"上（点别的卡片不动），改为跟随待保存选择；卡片行高等高 |
| 订单页"暂无订单数据" | 三个页面共用筛选状态，从退款审批/纠纷处理切回时残留 `PENDING_REFUND` 过滤 → 切回时用快照还原本页筛选 |
| 纠纷处理提示"失败" | 提交成功后引用了不存在的变量 `action` → ReferenceError 被 `catch` 吞掉，管理员会误判失败而重复提交 |
| 图标空白/畸形 | `check-circle`、`config` 图标根本不存在；`coin` 用三个同级元素共享一个 `v-else-if`（只渲染第一个 → 实心圆点）；密码框"眼睛"路径圆心写错（渲染成唱片状） |
| 按钮文字不可读 | 订单页「申请退款/纠纷申诉」浅琥珀字叠橙底 → 改黑字 |
| 文档页滚轮失效 | ① 重写时丢了自身滚动容器（全站 `html/body` 被锁 `overflow:hidden`）；② 搜索遮罩是固定定位层，滚轮链落到被锁的 document；③ Esc 关闭后输入框仍聚焦，再输入不弹面板 |
| `style.css` 模板残留 | Vite 脚手架的 `#app { text-align:center }` 被全站继承（文档页侧栏/标题被居中）、`h1 { font-size:56px }` 等 296 行演示样式 → 精简为约 45 行基础样式 |
| **暗色模式只做了一半** | `html/body` 与聊天区、用户中心、积分订阅等页面大量写死 `#fff`/`#f5f5f5`/`#333`/`#e8e8e8` → 统一替换为主题变量（**保留原值作 fallback**），并补上渐变卡片、浅底徽章的暗色变体。复检结果：**20 个页面状态 0 处浅色残留、7 个页面 0 处低对比度文字** |
| **群聊消息气泡暗色下仍是亮块** | `.message-left` 写死 `#f1f3f4`（不在第一版映射表内）→ 暗色下改为 `#16213e` 深底亮字，浅色主题保持原值；同批修掉消息/媒体卡片、富文本分段提示块，以及 AI 对话输入区的模型选择条（浅色渐变）、"当前会话使用"提示块、禁用态发送按钮 |
| **点主题按钮像"重新加载"** | `useTheme()` 每次调用都 `ref('light')` **新建独立状态**：在用户菜单里切换时只有菜单自己那份变了（并写 `<html>` class），而 App.vue 根节点仍挂 `theme-light` —— CSS 变量正是定义在根节点上，于是整页变量没跟着切、视觉上像闪烁/重刷。改为**模块级单例**后，实测切换瞬间 html 与根节点同步为 `theme-dark`、`--bg-primary` 立即生效，`window` 标记未丢、`framenavigated = 0`（确无整页重载） |
| **图片预览的 ←/→ 切换不生效** | `useImagePreview` 本身已实现左右切换（键盘 + 箭头按钮 + 计数），但聊天消息点击图片时**只传了单张 URL** → `imageList` 恒为 1 项，`goPrev/goNext` 直接 return。改为在 `ChatInterface` 汇总当前会话已加载消息的全部图片（新增 `utils/messageParser.extractImageUrl`）并通过 `gallery` 传给 `MessageContent`；媒体库两处（后台 / 个人中心）本已传整组图。实测：灯箱显示 `2 / 4`、←/→ 与箭头按钮均生效、Esc 关闭正常 |
| **灯箱一旦失败就永久卡在"图片加载失败"** | `loadFailed` 只在 `@load` 里重置，而失败后 `<img>` 被 `v-if` 移除、再也不会触发 `load` → 一张图失败后，后面每张都显示失败、左右切换也救不回来。改为**监听 `currentUrl` 变化时重置失败态**，并补上「重试」按钮与原因提示（可能已过期/未缓存/文件被清理）；同时 `MessageContent` 的图片在本地文件加载失败时**自动回退到 QQ 原始链接**，灯箱拿到的是当前实际显示的地址 |

### 六、文档体系（重做）

- 站内 `/docs` 从 10 篇零散页面重做为 **24 篇 / 4 大分类**（快速入门 / 使用指南 / 常见问题 / 开发文档）：索引页卡片 + 三栏阅读页（分类侧栏 + 正文 + 页内索引）+ 站内搜索 + 全套暗色 + **免登录可读**。
- 删除 3 篇与代码不符的旧页（`/help`、`/ai <内容>` 等后端并不存在的命令，以及不存在的"插件管理"菜单）。
- 仓库侧新增/更新：[媒体排查手册](doc/MEDIA_TROUBLESHOOTING.md)、[AI 摘要排查手册](doc/AI_SUMMARY_TROUBLESHOOTING.md)、[AI 摘要实装方案](frontend/src/docs/development/ai-summary-design.md)。

### 七、待办与遗留

- **AI 摘要实装**：设计稿已就绪（结构化字段 → 单条按需 → 批量工具 → 群日报），建议按阶段 0→3 推进。
- **5 处代码与文案不一致**：① 媒体过期占位符在前端被显示成"已删除"；② 绑定 QQ 弹窗文案说"用手机 QQ 发送验证码"，实际是机器人向该 QQ 发私信；③ 双持月卡显示"专属折扣 8 折"，实际按 `all_tier_discount`（0.7）计费；④ `CORS_ALLOWED_ORIGINS` 环境变量实际不生效（`application.yml` 为硬编码白名单）；⑤ 摘要 JSON 在消息气泡里原样展示。
- `GET /api/system/napcat/login-status` 恒返回 `loggedIn:false`：登录页状态灯是**进程级**检测（端口存活），**不代表 QQ 已登录**。

### 变更时间线

| 日期 | 变更 |
|------|------|
| 2026-09-16 | 登录页改版（双栏 + 实时状态灯）、记住我、忘记密码与管理员重置密码、暗色适配；用户菜单主题开关装回；`/docs` 免登录可读 |
| 2026-09-16 | 月卡每日积分改为随签到发放（双持叠加 +400）；移除消息入库自动摘要；修复群类型识别恒失败 |
| 2026-09-16 | 站内文档体系重做（24 篇 / 4 分类 / 搜索 / 暗色）；清理 `style.css` 模板残留；文档页滚轮与对齐修复 |
| 2026-09-16 | 10 项前端缺陷修复（订单页筛选残留、纠纷提交误报失败、图标缺失/畸形、按钮字色、群类型弹窗高亮等） |
| 2026-09-15 | 修复视频无法入库（三级取源 + 缩略图兜底）；修复 AI 摘要全量失败（SSE / `username` / 错误响应过滤） |
| 2026-09-15 | 安全整改：密钥移出仓库并强制校验、默认管理员密码随机化、订阅待确认、媒体不再公开、权限矩阵收口；git 历史清洗 |
| 2026-09-15 | 管理后台从 4600 行单文件拆分为 1 个壳 + 13 个懒加载子页；修复登录后白屏与共享样式失效 |
| 2026-09-19 | 系统管理中心整体升级：位置改由地址栏 `?tab=` 承载（刷新续接 + 可分享深链接）、首屏只加载当前页面接口、14 个页面统一页头与暗色主题、破坏性操作全部走 ConfirmDialog；**AI 摘要**独立成页；数据概览接入 7/30/90 天全局时间范围与现金/积分 KPI；用户管理/用户积分支持筛选排序、批量操作与 CSV 导出；系统日志支持关键字/时间范围/自动刷新/导出；媒体管理带缩略图懒加载与清理预览；数据维护支持删除备份与归档预检 |

## 第三方组件与外部依赖

本仓库**只包含自研代码**；下列内容均为第三方，随项目一起运行但不属于本项目开发成果。
许可证信息以各项目官方仓库为准（这里标注版本与用途便于溯源）。

### 一、外部服务/程序（需单独安装运行）

| 组件 | 版本 | 用途 | 许可证 |
|------|------|------|--------|
| [AstrBot](https://github.com/AstrBotDevs/AstrBot) | 4.28.1 | AI 对话 / 摘要 / 分析的 Agent 引擎，本项目通过其 OpenAPI（`/api/v1/chat`、`/api/v1/file`、配置档案）调用 | AGPL-3.0 |
| [NapCatQQ](https://github.com/NapNeko/NapCatQQ) | 4.x | QQ 协议端（OneBot 11）：收发消息、拉群成员/群列表、上传群文件 | 自定义许可（见其仓库 LICENSE） |
| [GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS) | v2Pro | TTS 语音合成（把 AI 回复念出来） | MIT |
| [MySQL](https://www.mysql.com/) | 9.5（本机实测；8.x 亦可） | 业务数据库 `qq_chat` | GPLv2 / 商业双许可 |
| [RabbitMQ](https://www.rabbitmq.com/) | 4.2（本机实测；CI/compose 用 3.12 镜像） | 消息队列（媒体下载 / 语音转码 / AI 分析 / 群日报） | MPL-2.0 |
| [MinIO](https://min.io/) | 最新版 | 可选的对象存储（本机未启动时自动回退本地磁盘存储） | AGPL-3.0 |

> 这些组件都**不在本仓库内**：部署时按 `doc/` 手册单独安装，`.env` 里填地址与令牌即可。

### 二、后端依赖（Maven，`backend/pom.xml`）

| 依赖 | 版本 | 用途 | 许可证 |
|------|------|------|--------|
| Spring Boot（web / validation / websocket / data-jpa / amqp / security / test） | 3.2.0 | Web、参数校验、WebSocket 推送、JPA、RabbitMQ、鉴权、测试 | Apache-2.0 |
| **Groovy** | 3.0.22 | **Groovy 脚本插件引擎**：`GroovyClassLoader` 加载 `backend/plugins/*.groovy`（详见下节） | Apache-2.0 |
| Apache HttpClient5 | 5.3 | 调 AstrBot / NapCat / GPT-SoVITS 的 HTTP 客户端 | Apache-2.0 |
| JJWT（api / impl / jackson） | 0.12.3 | JWT 签发与校验 | Apache-2.0 |
| sqlite-jdbc | 3.45.2.0 | 只读/维护 AstrBot 的 `data_v4.db`（读人格列表、写无工具副本） | Apache-2.0 |
| Caffeine | 随 Boot 版本 | 本地缓存（JWT 黑名单等） | Apache-2.0 |
| Flyway（core / mysql） | 随 Boot 版本 | 数据库版本管理（当前 `FLYWAY_ENABLED=false`） | Apache-2.0 |
| MySQL Connector/J | 随 Boot 版本 | MySQL 驱动 | GPLv2 + FOSS 例外 |
| H2 | 随 Boot 版本 | 单元测试内存库 | MPL-2.0 / EPL-1.0 |
| MinIO Java SDK | 8.5.7 | 对象存储客户端 | Apache-2.0 |
| Thumbnailator | 0.4.20 | 图片缩放/缩略图 | MIT |
| Apache Commons Exec | 1.3 | 调外部进程（PowerShell / cmd，组件启停） | Apache-2.0 |
| Jackson JSR-310 | 随 Boot 版本 | `LocalDateTime` 序列化 | Apache-2.0 |
| spring-dotenv | 3.0.0 | 读取 `backend/.env` | MIT |

### 三、前端依赖（npm，`frontend/package.json`）

| 依赖 | 版本 | 用途 | 许可证 |
|------|------|------|--------|
| Vue | 3.5.30 | 前端框架 | MIT |
| vue-router | 4.6.4 | 路由 | MIT |
| **markdown-it** | 14.1.0 | **Markdown 渲染**（AI 回复 / 摘要 / 站内文档的 Markdown → HTML） | MIT |
| **DOMPurify** | 3.1.6 | 渲染前对 HTML 做 XSS 清洗（与 markdown-it 配套） | Apache-2.0 / MPL-2.0 |
| ECharts | 6.1.0 | 管理后台图表 | Apache-2.0 |
| vue-echarts | 8.0.1 | ECharts 的 Vue 封装 | MIT |
| Vite | 8.2.1 | 构建/开发服务器（含 rolldown 打包器） | MIT |
| @vitejs/plugin-vue | 6.0.5 | Vite 的 Vue 单文件组件支持 | MIT |
| playwright-core | 1.61.1 | 仅开发期使用：端到端回归脚本 `frontend/scripts/*.mjs` | Apache-2.0 |

> 图标：`frontend/src/components/Icon.vue` 里的 SVG 路径取自 **Material Design Icons**（Apache-2.0），
> 按项目需要做了增删与颜色适配，未引入图标字体或图标库依赖。

### 四、后端 Groovy 插件（`backend/plugins/*.groovy`）

插件**引擎**是第三方（Groovy 3.0.22，见上表）；放在 `backend/plugins/` 下的 4 个脚本是本项目自研，
用于对 AI 回复做后处理，随 jar 一起分发：

| 脚本 | 作用 |
|------|------|
| `MarkdownCleanerPlugin.groovy` | 兜底清理工具 JSON 残留与多余空行 |
| `SummaryCardPlugin.groovy` | 把「摘要：xxx」这类行转成可折叠的 `<details>` 块 |
| `TocMarkerPlugin.groovy` | 文本含 Markdown 标题时在开头插入 `[TOC]`，供前端生成目录 |
| `AutoLinkPlugin.groovy` | 把文本里的裸 URL 转成 Markdown 链接 |

> 接口约定见 `backend/src/main/java/com/qqai/plugin/AiResponsePlugin.java`：实现该接口（`name()` / `order()` / 处理方法）
> 并放进 `backend/plugins/`，即由 `PluginManager` 用 `GroovyClassLoader` 加载执行。

### 五、AstrBot 侧第三方插件（部署环境，不在本仓库）

本项目调用的是 AstrBot 的**模型与人格**能力，不依赖具体插件；但当前部署环境另外装了下列
第三方 AstrBot 插件（可用于扩展 QQ 侧行为，属于别人的作品）：

`astrbot_plugin_angel_smile`、`astrbot_plugin_bilivideo`、`astrbot_plugin_cet6`、
`astrbot_plugin_gpt_sovits`、`astrbot_plugin_img_tool`、`astrbot_plugin_knowledge_base`、
`astrbot_plugin_opencode`、`astrbot_plugin_pixiv_reborn`、`astrbot_plugin_screen_companion`、
`astrbot_plugin_self_evolution`、`astrbot_plugin_skland`

### 六、内容与素材来源（非代码）

| 内容 | 位置 | 来源与归属 |
|------|------|------------|
| 人格提示词（`doc/personas/*.md`） | 仓库内 | 由本项目按《人格编写规范》重写整理；**角色形象与设定版权归原作者** —— 「瑞吉儿·加德纳」出自《杀戮的天使》，「爱莉希雅」出自《崩坏3》（© miHoYo），「灰泽满」为 VirtuaReal 所属虚拟主播。仅供个人学习使用，请勿商用 |
| 「无工具人格副本」与配置档案 | AstrBot 数据库（运行时生成） | 由本项目脚本/服务按用户所选人格克隆生成，原始人格不受影响 |
| QQ 头像、群名、群成员昵称 | 运行时经 NapCat 获取 | 归腾讯/群成员所有，仅本地展示，不入库到本仓库 |
| 站内文档与用户手册 | `doc/`、`frontend/src/docs/` | 本项目自研 |

> 若你二次分发本项目，请一并遵守上表中各第三方组件的许可证（尤其是 **AstrBot 的 AGPL-3.0**：
> 若你修改并向网络用户提供 AstrBot 本身，需按 AGPL 开放相应源码）。

## 许可证

本项目仅供学习和毕业设计使用。
