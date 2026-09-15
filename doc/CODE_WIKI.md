# QQ-Web 项目  Wiki

## 1. 项目概述

本项目是一个**AI聊天机器人系统**，包含三大核心组件：

| 组件 | 名称 | 功能描述 | 技术栈 |
|------|------|----------|--------|
| **统一管理平台** | qq-ai-assistant | 全栈 Web 应用，整合 NapCat、AstrBot、GPT-SoVITS，提供消息管理、用户认证、WebUI | Java Spring Boot + Vue.js |
| **核心框架** | AstrBot | 多平台AI聊天机器人，支持插件扩展、知识库、WebUI管理 | Python + WebUI (Vue) |
| **语音合成引擎** | GPT-SoVITS-v2pro | 少样本语音克隆与文本转语音系统 | Python + PyTorch |

---

## 2. 项目架构

### 2.1 整体架构图

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        QQ-Web 项目                                         │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │                     qq-ai-assistant (统一管理平台)                    │    │
│  │                                                                      │    │
│  │  ┌─────────────────────────┐    ┌─────────────────────────────────┐ │    │
│  │  │      Vue.js 前端        │    │      Spring Boot 后端           │ │    │
│  │  │  (Vite + Vue Router)    │    │                               │ │    │
│  │  │                         │    │  ┌───────────────────────────┐ │ │    │
│  │  │  ┌───────────────────┐  │    │  │         Controller层       │ │ │    │
│  │  │  │   登录页面        │  │    │  │  Auth/Message/NapCat/AstrBot│ │ │    │
│  │  │  │   聊天界面        │  │    │  └──────────┬───────────────┘ │ │    │
│  │  │  │   消息管理        │  │    │             │                  │ │    │
│  │  │  │   系统管理        │  │    │  ┌──────────▼───────────────┐ │ │    │
│  │  │  │   管理后台        │  │    │  │         Service层        │ │ │    │
│  │  │  └──────────┬────────┘  │    │  │  Message/NapCat/AstrBot  │ │ │    │
│  │  │             │           │    │  │  GptSovits/FileStorage   │ │ │    │
│  │  │    WebSocket │          │    │  └──────────┬───────────────┘ │ │    │
│  │  └─────────────┼───────────┘    │             │                  │ │    │
│  │                │                │  ┌──────────▼───────────────┐ │ │    │
│  │                │                │  │         Repository层     │ │ │    │
│  │                │                │  │  JPA + H2/SQLite/MySQL  │ │ │    │
│  │                │                │  └──────────┬───────────────┘ │ │    │
│  │                │                │             │                  │ │    │
│  │                ▼                │  ┌──────────▼───────────────┐ │ │    │
│  │           REST API              │  │         Entity层         │ │ │    │
│  │                │                │  │  User/Message/Group/File │ │ │    │
│  │                │                │  └───────────────────────────┘ │ │    │
│  │                │                │                                │ │    │
│  │                │                │  ┌───────────────────────────┐ │ │    │
│  │                │                │  │     WebSocket 通信        │ │ │    │
│  │                │                │  │  NapCat ↔ Frontend       │ │ │    │
│  │                │                │  └───────────────────────────┘ │ │    │
│  └────────────────┼──────────────────────────────────────────────────┘ │    │
│                   │                                                     │    │
│          ┌────────┴────────┐                                            │    │
│          ▼                 ▼                                            │    │
│  ┌─────────────────┐  ┌───────────────────────────────────────┐        │    │
│  │   AstrBot 框架   │  │        GPT-SoVITS 语音合成引擎       │        │    │
│  │                 │  │                                       │        │    │
│  │  ┌─────────────┐ │  │  ┌───────────────────────────────┐   │        │    │
│  │  │  WebUI      │ │  │  │     WebUI (Gradio)            │   │        │    │
│  │  │  Plugin Sys │ │  │  │                              │   │        │    │
│  │  │  Knowledge  │ │  │  └───────────┬─────────────────┘   │        │    │
│  │  │  LLM Provider│ │  │             │                    │        │    │
│  │  └─────────────┘ │  │  ┌───────────▼─────────────────┐   │        │    │
│  └────────┬────────┘  │  │     API Server               │   │        │    │
│           │           │  │   (FastAPI + Uvicorn)        │   │        │    │
│           │           │  └───────────┬─────────────────┘   │        │    │
│           │           │             │                    │        │    │
│           │           │  ┌───────────▼─────────────────┐   │        │    │
│           │           │  │    GPT-SoVITS 模型          │   │        │    │
│           │           │  │  (S1 + S2 + Vocoder)       │   │        │    │
│           │           │  └─────────────────────────────┘   │        │    │
│           │           └───────────────────────────────────────┘        │    │
│           │                                                             │    │
│           ▼                                                             │    │
│  ┌───────────────────────┐                                             │    │
│  │      NapCat (QQ协议)  │                                             │    │
│  │   (WebSocket/Webhook)│                                             │    │
│  └───────────────────────┘                                             │    │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 目录结构

```
qq-web/
├── qq-ai-assistant/                      # 统一管理平台
│   ├── backend/                          # Spring Boot 后端
│   │   ├── src/main/java/com/qqai/
│   │   │   ├── Application.java          # 启动类
│   │   │   ├── common/                   # 通用工具
│   │   │   │   ├── AvatarResolver.java   # 头像解析
│   │   │   │   ├── ProcessManager.java  # 子进程管理
│   │   │   │   ├── RateLimiterService.java # 限流
│   │   │   │   └── SecurityHelper.java  # 安全辅助
│   │   │   ├── config/                   # 配置类
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── CorsConfig.java
│   │   │   │   ├── MinioConfig.java
│   │   │   │   ├── RabbitMQConfig.java
│   │   │   │   ├── GlobalExceptionHandler.java # 全局异常处理
│   │   │   │   ├── TraceIdFilter.java   # 链路追踪
│   │   │   │   └── ...
│   │   │   ├── consumer/                 # MQ 消费者
│   │   │   │   ├── AiAnalysisConsumer.java
│   │   │   │   ├── MediaDownloadConsumer.java
│   │   │   │   ├── VoiceTranscodeConsumer.java
│   │   │   │   └── BroadcastConsumer.java
│   │   │   ├── controller/               # REST API 控制器
│   │   │   │   ├── AuthController.java
│   │   │   │   ├── MessageController.java
│   │   │   │   ├── CreditsController.java        # 积分
│   │   │   │   ├── SubscriptionsController.java   # 订阅
│   │   │   │   ├── AstrBotController.java
│   │   │   │   ├── SystemController.java
│   │   │   │   ├── DashboardController.java
│   │   │   │   ├── PersonaController.java
│   │   │   │   ├── AdminController.java
│   │   │   │   ├── AdminCreditsController.java    # 管理-积分
│   │   │   │   ├── AdminOrdersController.java     # 管理-订单
│   │   │   │   ├── BackupController.java
│   │   │   │   ├── ConfigController.java
│   │   │   │   ├── LogController.java
│   │   │   │   ├── GroupController.java
│   │   │   │   ├── UserProfileController.java
│   │   │   │   ├── UserSettingsController.java
│   │   │   │   └── ...
│   │   │   ├── service/                  # 业务逻辑层
│   │   │   │   ├── MessageService.java
│   │   │   │   ├── NapCatService.java
│   │   │   │   ├── AstrBotService.java
│   │   │   │   ├── GptSovitsService.java
│   │   │   │   ├── CreditService.java      # 积分核心
│   │   │   │   ├── SubscriptionService.java # 订阅+月卡
│   │   │   │   ├── SubscriptionExpireScheduler.java # 过期扫描
│   │   │   │   ├── CreditRuleService.java   # 积分规则
│   │   │   │   ├── FileStorageService.java
│   │   │   │   ├── MediaDownloadService.java
│   │   │   │   ├── MessageParserService.java
│   │   │   │   ├── MessageQueueService.java
│   │   │   │   └── ...
│   │   │   ├── entity/                   # JPA 实体
│   │   │   │   ├── Message.java
│   │   │   │   ├── User.java
│   │   │   │   ├── UserCredit.java         # 积分账户
│   │   │   │   ├── CreditTransaction.java  # 积分流水
│   │   │   │   ├── CreditRule.java         # 积分规则
│   │   │   │   ├── SubscriptionOrder.java  # 订阅订单
│   │   │   │   ├── SignInRecord.java       # 签到记录
│   │   │   │   ├── MonthlyBonusRecord.java # 月度奖励
│   │   │   │   ├── Group.java
│   │   │   │   ├── FileRecord.java
│   │   │   │   ├── UserSettings.java
│   │   │   │   └── ...
│   │   │   ├── repository/               # 数据访问层
│   │   │   ├── security/                 # JWT 认证
│   │   │   ├── websocket/                # WebSocket 模块
│   │   │   ├── plugin/                   # Groovy 插件系统
│   │   │   ├── exception/                # 异常体系
│   │   │   └── dto/                      # 数据传输对象
│   │   ├── src/main/resources/
│   │   │   ├── application.yml.example   # 配置示例
│   │   │   ├── db/migration/             # Flyway 迁移脚本
│   │   │   ├── init-mysql*.sql           # 数据库初始化脚本
│   │   │   └── prompts.yml               # AI 提示词模板
│   │   ├── plugins/                      # Groovy 插件脚本
│   │   │   ├── AutoLinkPlugin.groovy
│   │   │   ├── MarkdownCleanerPlugin.groovy
│   │   │   ├── SummaryCardPlugin.groovy
│   │   │   └── TocMarkerPlugin.groovy
│   │   ├── scripts/                      # Python 脚本
│   │   │   └── convert_silk_to_mp3.py    # SILK 转 MP3
│   │   └── pom.xml
│   ├── frontend/                         # Vue.js 前端
│   │   ├── src/
│   │   │   ├── main.js                   # 入口文件
│   │   │   ├── App.vue                   # 根组件
│   │   │   ├── router/index.js           # 路由配置
│   │   │   ├── services/api.js           # API 服务封装
│   │   │   ├── composables/              # 组合式函数
│   │   │   │   ├── useMessageWebSocket.js
│   │   │   │   ├── useAdminOrders.js      # 管理-订单
│   │   │   │   ├── useCreditsDashboard.js # 积分仪表盘
│   │   │   │   ├── useComponentControl.js # 组件控制
│   │   │   │   └── ...
│   │   │   ├── views/                    # 页面视图
│   │   │   │   ├── LoginPage.vue
│   │   │   │   ├── HomeView.vue
│   │   │   │   ├── UserCenter.vue        # 个人中心（积分/订阅）
│   │   │   │   ├── AdminView.vue         # 管理后台
│   │   │   │   └── DocView.vue           # 文档
│   │   │   ├── views/admin/              # 管理子页
│   │   │   │   ├── AdminDashboard.vue
│   │   │   │   ├── AdminOrders.vue
│   │   │   │   ├── AdminTransactions.vue
│   │   │   │   ├── AdminCreditsUsers.vue
│   │   │   │   └── ...
│   │   │   ├── components/               # UI 组件
│   │   │   │   ├── Sidebar.vue
│   │   │   │   ├── ChatInterface.vue
│   │   │   │   ├── MessageContent.vue
│   │   │   │   ├── AstrBotChat.vue
│   │   │   │   ├── PersonaManager.vue
│   │   │   │   ├── credits/
│   │   │   │   │   ├── CreditsDashboard.vue
│   │   │   │   │   └── SubscriptionDashboard.vue
│   │   │   │   └── ...
│   │   │   ├── docs/                     # 前端内置文档 (Markdown)
│   │   │   └── utils/                    # 工具函数
│   │   ├── public/                       # 静态资源
│   │   └── package.json
│   ├── doc/                              # 项目文档
│   │   ├── CODE_WIKI.md                  # 代码百科（本文件）
│   │   ├── README.md                     # 项目说明
│   │   └── user-manual.md                # 用户手册
│   ├── .env.example                      # 环境变量示例
│   ├── docker-compose.yml                # Docker 部署编排
│   └── .gitignore
│
├── Astrbot/                              # AstrBot AI 机器人框架
├── GPT-SoVITS-v2pro-20250604-nvidia50/   # GPT-SoVITS 语音合成引擎
├── .trae/                                # TRAE 开发环境配置
└── .idea/                                # IntelliJ IDEA 配置
```

---

## 3. qq-ai-assistant 统一管理平台

### 3.1 后端架构

#### 3.1.1 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行时 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring Security | - | 安全认证 |
| Spring Data JPA | - | 数据访问 |
| MySQL / H2 / SQLite | - | 数据库 |
| MinIO | - | 对象存储 |
| JJWT | 0.12.3 | JWT 令牌 |
| Groovy | 3.0.22 | 脚本插件引擎 |
| Caffeine | - | 本地缓存 |

#### 3.1.2 Controller 层

**[AuthController.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/controller/AuthController.java)** - 用户认证

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/auth/login` | POST | 用户登录 |
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/me` | GET | 获取当前用户信息 |
| `/api/auth/change-password` | POST | 修改密码 |
| `/api/auth/logout` | POST | 退出登录 |

**[UserDashboardController](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/controller/UserDashboardController.java)** - 用户仪表盘

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/user/dashboard/overview` | GET | 用户积分概览 |
| `/api/user/dashboard/trend` | GET | 积分消耗趋势 |
| `/api/user/dashboard/rank` | GET | 用户使用排行 |

**[CreditsController](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/controller/CreditsController.java)** - 积分系统

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/credits/balance` | GET | 查询当前积分余额 |
| `/api/credits/sign-in` | POST | 每日签到领积分 |
| `/api/credits/transactions` | GET | 积分流水列表 |
| `/api/credits/trend` | GET | 积分趋势数据 |

**[SubscriptionsController](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/controller/SubscriptionsController.java)** - 订阅月卡

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/subscriptions/plans` | GET | 获取套餐列表 |
| `/api/subscriptions/order` | POST | 创建订阅订单 |
| `/api/subscriptions/pay/{orderNo}` | POST | 确认支付 |
| `/api/subscriptions/refund/{orderNo}` | POST | 申请退款 |
| `/api/subscriptions/dispute` | POST | 提交纠纷 |

**[AdminCreditsController](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/controller/AdminCreditsController.java)** - 管理端积分

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/admin/credits/rules` | GET/PUT | 查看/修改积分规则 |
| `/api/admin/credits/users` | GET | 用户积分列表 |
| `/api/admin/credits/adjust` | POST | 管理员调账 |

**[AdminOrdersController](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/controller/AdminOrdersController.java)** - 管理端订单

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/admin/orders` | GET | 订单列表 |
| `/api/admin/orders/{orderNo}/approve` | POST | 批准退款 |
| `/api/admin/orders/{orderNo}/reject` | POST | 拒绝退款 |
| `/api/admin/orders/refundable` | GET | 可退款订单查询 |

**[MessageController.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/controller/MessageController.java)** - 消息管理

| 端点 | 方法 | 功能 |
|------|------|------|
| `/api/messages` | POST | 创建消息 |
| `/api/messages/group/{groupId}` | GET | 获取群消息 |
| `/api/messages/group/{groupId}/paged` | GET | 分页获取群消息 |
| `/api/messages/group/{groupId}/since` | GET | 获取指定ID之后的消息 |
| `/api/messages/group/{groupId}/members` | GET | 获取群成员列表 |
| `/api/messages/upload` | POST | 上传文件 |
| `/api/messages/send-with-file` | POST | 发送带文件的消息 |
| `/api/messages/file/{fileId}` | GET/DELETE | 文件操作 |
| `/api/messages/{messageId}` | DELETE | 删除单条消息 |
| `/api/messages/delete-batch` | POST | 批量删除消息 |
| `/api/messages/group/{groupId}/delete-by-types` | POST | 按类型删除消息 |
| `/api/messages/purge-media` | POST | 清理媒体文件 |
| `/api/messages/media-files` | GET | 列出媒体文件 |
| `/api/messages/delete-media-files` | POST | 删除媒体文件 |
| `/api/messages/archive` | POST | 手动触发归档 |
| `/api/messages/recent-groups` | GET | 获取最近群聊列表（含未读数） |
| `/api/messages/read/{groupId}` | POST | 标记群聊已读 |
| `/api/messages/read-all` | POST | 全部标为已读 |

**其他 Controller**：

| Controller | 功能 |
|------------|------|
| `NapCatWebhookController` | 接收 NapCat Webhook 消息推送 |
| `RootWebhookController` | 处理 Webhook 消息（媒体下载、转换） |
| `AstrBotController` | AstrBot API 代理（AI对话/模型切换） |
| `SystemController` | 系统组件控制（启动/停止各服务） |
| `DashboardController` | 仪表盘统计数据（管理端） |
| `UserDashboardController` | 用户仪表盘（积分/用量统计） |
| `PersonaController` | 人设管理 |
| `CreditsController` | 积分查询/签到/流水/趋势 |
| `SubscriptionsController` | 月卡订阅购买/退款/纠纷 |
| `GroupController` | 群类型识别与配置 |
| `AdminController` | 管理员功能（用户管理） |
| `AdminCreditsController` | 管理员积分调账/规则管理 |
| `AdminOrdersController` | 管理员订单审批/退款 |
| `BackupController` | 数据备份与恢复 |
| `ConfigController` | 系统配置管理 |
| `LogController` | 审计日志查看 |
| `AvatarController` | 头像上传 |

#### 3.1.3 Service 层

**[MessageService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/MessageService.java)** - 消息核心业务

| 方法 | 功能 |
|------|------|
| `saveMessage()` | 保存消息并触发异步处理 |
| `processMessageAsync()` | 异步处理消息（调用 AstrBot 总结） |
| `getMessagesByGroupIdAndUserQqList()` | 获取指定群和用户的消息 |
| `getGroupMemberNicknames()` | 获取群成员昵称映射 |
| `getRecentGroupsForUser()` | 获取用户最近群聊（含未读数） |
| `deleteMessage()` | 软删除单条消息 |
| `deleteMessagesByIds()` | 批量软删除消息 |
| `deleteMessagesByGroupAndTypes()` | 按群和类型批量删除 |
| `markGroupAsRead()` | 标记群聊已读 |
| `markAllAsRead()` | 全部标为已读 |

**[NapCatService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/NapCatService.java)** - NapCat 协议交互

| 方法 | 功能 |
|------|------|
| `getLoginQrCode()` | 获取登录二维码 |
| `checkLoginStatus()` | 检查登录状态 |
| `startNapCat()` | 启动 NapCat |
| `stopNapCat()` | 停止 NapCat |
| `getGroupMemberList()` | 获取群成员列表 |
| `getGroupMemberInfo()` | 获取单个群成员信息 |
| `getGroupList()` | 获取群列表 |
| `getForwardMsg()` | 获取合并转发消息详情 |
| `normalizeForwardMessages()` | 标准化转发消息格式 |

**[AstrBotService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/AstrBotService.java)** - AstrBot 交互

| 方法 | 功能 |
|------|------|
| `summarizeMessage()` | 调用 AstrBot 总结消息 |
| `startAstrBot()` | 启动 AstrBot |
| `stopAstrBot()` | 停止 AstrBot |

**[GptSovitsService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/GptSovitsService.java)** - 语音合成服务

**[FileStorageService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/FileStorageService.java)** - 文件存储管理

**[FilePurgeService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/FilePurgeService.java)** - 文件清理服务

**[MessageBroadcastService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/MessageBroadcastService.java)** - WebSocket 消息广播

**[MessageArchiveService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/service/MessageArchiveService.java)** - 消息归档服务

#### 3.1.4 Entity 层

**[Message.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/entity/Message.java)** - 消息实体

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 主键 |
| `messageId` | String | QQ 消息唯一 ID |
| `groupId` | String | 群号 |
| `groupName` | String | 群名称 |
| `userQq` | String | 发送者 QQ |
| `userNickname` | String | 发送者昵称 |
| `messageType` | MessageType | 消息类型（TEXT/IMAGE/VIDEO/AUDIO/FILE/VOICE/AT/REPLY/FORWARD） |
| `content` | String | 文本内容或文件描述 |
| `fileId` | String | 关联文件 ID |
| `atQq` | String | @ 的用户 QQ |
| `replyToMessageId` | Long | 回复的消息 ID |
| `aiSummary` | String | AI 总结内容 |
| `sendTime` | LocalDateTime | 发送时间 |
| `serverRecvMs` | Long | 服务端接收毫秒时间戳 |
| `archived` | boolean | 是否已归档 |
| `deleted` | boolean | 是否已软删除 |
| `processed` | boolean | 是否已处理 |
| `isSelfMessage` | boolean | 是否是登录账号发送 |
| `selfQq` | String | 接收消息的机器人 QQ |

**其他 Entity**：

| Entity | 说明 |
|--------|------|
| `User` | 用户实体（用户名、密码、角色、昵称、头像、积分余额 tier） |
| `Group` | 群聊实体（群号、群名、所属 QQ、头像、群类型） |
| `FileRecord` | 文件记录（文件 ID、路径、类型、上传者） |
| `GroupReadState` | 群聊已读状态（用户 ID、群 ID、最后已读时间） |
| `UserQqBinding` | 用户 QQ 绑定关系（可多个 QQ 号绑定） |
| `UserSettings` | 用户 AI 设置（API Key、模型列表、提供商配置） |
| `AstrBotConversation` | AstrBot 对话会话记录 |
| `AstrBotMessage` | AstrBot 对话消息记录 |
| **`UserCredit`** | **用户积分账户**（余额、tier、过期时间） |
| **`CreditTransaction`** | **积分流水**（收入/支出、关联订单、描述） |
| **`CreditRule`** | **积分规则**（消费系数、tier 折扣、阶梯折扣） |
| **`SubscriptionOrder`** | **订阅订单**（月卡档位、有效期、支付状态） |
| **`SignInRecord`** | **签到记录**（连续天数、奖励积分） |
| **`MonthlyBonusRecord`** | **月度奖励记录**（登录发放月卡积分） |
| `AuditLog` | 审计日志（管理员操作记录） |

#### 3.1.5 安全模块

**[JwtUtil.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/security/JwtUtil.java)** - JWT 工具类

| 方法 | 功能 |
|------|------|
| `generateToken()` | 生成 JWT 令牌 |
| `validateToken()` | 验证令牌有效性 |
| `getUsernameFromToken()` | 从令牌提取用户名 |
| `getJtiFromToken()` | 从令牌提取 JTI |

**[TokenBlacklistService.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/security/TokenBlacklistService.java)** - 令牌黑名单服务（基于 Caffeine 缓存）

**[JwtAuthenticationFilter.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/security/JwtAuthenticationFilter.java)** - JWT 认证过滤器

#### 3.1.6 WebSocket 模块

**[FrontendMessageWebSocketHandler.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/websocket/FrontendMessageWebSocketHandler.java)** - 前端消息推送

**[NapCatWebSocketHandler.java](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/java/com/qqai/websocket/NapCatWebSocketHandler.java)** - NapCat 消息接收

#### 3.1.7 配置文件

**[application.yml](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/resources/application.yml)** - 核心配置

| 配置节 | 关键字段 | 默认值 |
|--------|----------|--------|
| `spring.datasource` | MySQL 连接配置 | localhost:3306/qq_chat |
| `server.port` | 服务端口 | 8081 |
| `astrbot` | AstrBot API 地址和 Token | http://localhost:6185 |
| `napcat` | NapCat API 地址和 Token | http://localhost:6099 |
| `gpt-sovits` | GPT-SoVITS API 配置 | http://localhost:8000 |
| `minio` | MinIO 对象存储配置 | http://localhost:9000 |
| `jwt` | JWT 密钥和过期时间 | 86400000ms |
| `app.registration` | 注册开关和限流 | enabled=true |

#### 3.1.8 积分与订阅系统

#### 3.1.8.1 积分体系

**积分计算流程**：
```
消息发送 → 判定优先级:
  1. 管理员免费（扣0）
  2. 月度免费配额（每月 5000 免费额度）
  3. 每日封顶（每日最多扣 10000）
  4. 实际扣费:
     基础消耗 = 模型费率 × token 数
     实际扣费 = ceil(基础消耗 × 超额倍数 × tier折扣 × 阶梯折扣)
```

**Service**:
- `CreditService` — 核心积分操作（扣费/退费/余额查询）
- `CreditRuleService` — 积分规则管理

**积分流水枚举** (`CreditTransactionType`):
`AI_CHAT` / `TTS` / `IMAGE_GENERATE` / `GROUP_ANALYSIS` / `SIGN_IN` / `MONTHLY_BONUS` / `DIRECT_PURCHASE` / `ADMIN_ADJUST` / `REFUND` 等

**积分 tier** (`SubscriptionTier`):
| 层级 | 说明 | 折扣 |
|------|------|------|
| `FREE` | 免费版 | 无折扣 |
| `SMALL_MONTH_CARD` | 小月卡（¥30） | 低折扣 |
| `LARGE_MONTH_CARD` | 大月卡（¥68） | 中折扣 |
| `ALL` | 同时持有大小月卡 | 全功能 |

#### 3.1.8.2 月卡订阅体系

**订阅规则**：
| 规则 | 说明 |
|------|------|
| 同档位不可重复购买 | 下单时直接拦截 |
| 大小月卡同时持有 | tier 显示为 `ALL`，有效期取最远 |
| 升级（小→大） | 叠加剩余天数 + 30 天 |
| 降级（大→小） | 保持大月卡 tier 和有效期 |
| 过期自动降级 | 每日 02:00 扫描 → FREE |
| 退款按比例 | 回退积分 + 有效期 |

**Service**:
- `SubscriptionService` — 订单创建/支付/退款
- `SubscriptionExpireScheduler` — 定时扫描过期月卡
- `OrderNoGenerator` — 订单号生成

**订单状态** (`OrderStatus`):
`PENDING` / `PAID` / `CANCELLED` / `REFUNDED` / `DISPUTED`

### 3.1.9 插件系统

Groovy 插件目录：`backend/plugins/`

| 插件 | 功能 |
|------|------|
| `AutoLinkPlugin.groovy` | 自动链接生成 |
| `MarkdownCleanerPlugin.groovy` | Markdown 内容清洗 |
| `SummaryCardPlugin.groovy` | 总结卡片生成 |
| `TocMarkerPlugin.groovy` | 目录标记 |

### 3.2 前端架构

#### 3.2.1 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5.30 | 前端框架 |
| Vue Router | 4.6.4 | 路由管理 |
| Vite | 8.0.1 | 构建工具 |
| Markdown-It | 14.1.0 | Markdown 渲染 |
| DOMPurify | 3.1.6 | HTML 净化 |

#### 3.2.2 路由配置

**[router/index.js](file:///d:/ai/Documents/qq-web/qq-ai-assistant/frontend/src/router/index.js)**

| 路径 | 组件 | 权限 |
|------|------|------|
| `/login` | LoginPage.vue | 公开 |
| `/` | HomeView.vue | 需登录 |
| `/admin` | AdminView.vue | 需管理员 |

#### 3.2.3 API 服务封装

**[services/api.js](file:///d:/ai/Documents/qq-web/qq-ai-assistant/frontend/src/services/api.js)**

| API 模块 | 功能 |
|----------|------|
| `messageApi` | 消息相关 API |
| `systemApi` | 系统控制 API |
| `astrBotApi` | AstrBot 交互 API |
| `userApi` | 用户相关 API |
| `dashboardApi` | 仪表盘 API |
| `personaApi` | 人设管理 API |
| `authApi` | 认证相关 API |
| `adminApi` | 管理员 API |

#### 3.2.4 页面视图

| 视图 | 功能 |
|------|------|
| `LoginPage.vue` | 用户登录页面 |
| `HomeView.vue` | 主页面（侧边栏 + 聊天界面） |
| `AdminView.vue` | 管理员后台 |

#### 3.2.5 UI 组件

| 组件 | 功能 |
|------|------|
| `Sidebar.vue` | 侧边栏（群聊列表） |
| `ChatInterface.vue` | 聊天主界面 |
| `MessageContent.vue` | 消息内容渲染 |
| `AstrBotChat.vue` | AstrBot AI 聊天组件 |
| `PersonaManager.vue` | 人设管理组件 |
| `AdminDashboard.vue` | 管理后台仪表盘 |
| `LoginModal.vue` | 登录弹窗 |
| `UserLogin.vue` | 用户登录表单 |
| `UserProfile.vue` | 用户资料编辑 |
| `RichTextRenderer.vue` | 富文本渲染器 |
| `ConfirmDialog.vue` | 确认对话框 |
| `Toast.vue` | 提示消息组件 |

#### 3.2.6 组合式函数

| 文件 | 功能 |
|------|------|
| `useMessageWebSocket.js` | WebSocket 消息监听 |
| `useAdminDashboard.js` | 管理后台数据获取 |
| `useResponsive.js` | 响应式布局 |

### 3.3 数据流

#### 消息接收流程

```
NapCat Webhook → RootWebhookController → MessageService.saveMessage()
                                               │
                                               ├─→ 持久化到数据库
                                               ├─→ MessageBroadcastService → WebSocket → 前端
                                               └─→ @Async processMessageAsync() → AstrBotService.summarizeMessage()
                                                                                    │
                                                                                    └─→ 更新消息的 aiSummary 字段
```

#### 消息读取流程

```
前端请求 → MessageController.getMessagesByGroupId() → MessageService.getMessagesByGroupIdAndUserQqList()
                                                              │
                                                              └─→ MessageRepository → 返回消息列表
```

#### 用户认证流程

```
登录请求 → AuthController.login() → UserRepository.findByUsername()
                                            │
                                            ├─→ PasswordEncoder.matches() 验证密码
                                            ├─→ 更新 lastLoginTime
                                            └─→ JwtUtil.generateToken() → 返回 JWT 令牌
```

---

## 4. AstrBot 框架详解

### 4.1 核心配置

#### cmd_config.json

主要配置项说明（参考 [cmd_config.json](file:///d:/ai/Documents/qq-web/Astrbot/data/cmd_config.json)）：

| 配置节 | 说明 | 关键字段 |
|--------|------|----------|
| `platform_settings` | 平台通用设置 | 限流、白名单、分段回复 |
| `provider_sources` | LLM 服务商配置 | API Base、Key、超时时间 |
| `provider` | 模型配置 | 模型ID、模态支持、启用状态 |
| `provider_settings` | 推理设置 | 默认模型、唤醒词、知识库配置 |
| `dashboard` | WebUI 配置 | 端口、用户名、密码、JWT密钥 |
| `content_safety` | 内容安全 | 关键词过滤、百度AI审核 |
| `wake_prefix` | 唤醒词 | 触发机器人的前缀 |

**当前配置要点**:
- 默认使用 **SiliconFlow** 平台的 **Kimi-K2.7-Code** 模型
- 启用了分段回复功能（按句号分割）
- WebUI 监听端口 **6185**
- 支持多语言：中文、英文、日文、韩文、粤语

### 4.2 插件系统

AstrBot 支持丰富的插件生态，插件注册表位于 [plugins.json](file:///d:/ai/Documents/qq-web/Astrbot/data/plugins.json)。

#### 已注册插件分类

| 类别 | 插件名称 | 功能描述 |
|------|----------|----------|
| **记忆增强** | Iris Chat Memory | 长期记忆、知识图谱、用户画像 |
| **记忆增强** | Hindsight Memory | 接入 Hindsight Cloud 长期记忆 |
| **成本控制** | Token成本控制 | Token预算监控、缓存诊断、提示词优化 |
| **成本控制** | 配额中枢 | 按用户/群组限制调用次数和Token用量 |
| **媒体处理** | Koharu 漫画翻译 | 漫画图片翻译 |
| **媒体处理** | SerpApi 搜图 | 搜索引擎图片搜索 |
| **媒体处理** | Image Caption Cache | 图片转述结果缓存 |
| **游戏工具** | Minecraft 服务器监控 | MC服务器状态查询 |
| **游戏工具** | SIGNALIS 存档解密 | 游戏存档分析 |
| **实用工具** | Steam 价格查询 | 小黑盒价格查询 |
| **实用工具** | 易校园电费监控 | 电费余额监控和提醒 |
| **实用工具** | Firefly 博客管理 | 博客部署管理 |
| **安全工具** | LLM 告状机 | 安全告警消息推送 |
| **音乐工具** | Songloft 音乐控制 | 音乐服务器控制 |
| **优化工具** | AstrNa | AstrBot 框架优化 |

### 4.3 知识库模块

知识库数据存储在 `data/knowledge_base/` 目录，采用 **FAISS** 向量数据库：

```
knowledge_base/
├── <uuid>/                    # 知识库集合
│   ├── doc.db                 # 文档元数据
│   └── index.faiss            # FAISS 向量索引
├── kb.db                      # 知识库主数据库
├── kb.db-shm                  # SQLite 共享内存
└── kb.db-wal                  # SQLite 预写日志
```

**配置参数**（来自 cmd_config.json）：
- `kb_fusion_top_k`: 20 - 融合阶段取 top-k
- `kb_final_top_k`: 5 - 最终返回 top-k
- `kb_agentic_mode`: false - 是否启用智能体模式

### 4.4 WebUI 界面

WebUI 构建产物位于 `data/dist/`，包含以下主要页面组件：

| 组件 | 文件 | 功能 |
|------|------|------|
| Chat | ChatBoxPage, ChatPage | 聊天界面 |
| Config | ConfigPage, AstrBotConfig | 配置管理 |
| Console | ConsolePage, ConsoleDisplayer | 控制台日志 |
| KnowledgeBase | KBList, KBDetail | 知识库管理 |
| Platform | PlatformPage, AddNewPlatform | 平台管理 |
| Persona | PersonaPage, PersonaForm | 人设管理 |
| Settings | Settings | 系统设置 |
| CronJob | CronJobPage | 定时任务 |

---

## 5. GPT-SoVITS 语音合成引擎

### 5.1 技术架构

GPT-SoVITS 是一个**两阶段语音合成系统**：

```
┌─────────────────────────────────────────────────────────────────┐
│                    GPT-SoVITS 推理流程                        │
├─────────────────────────────────────────────────────────────────┤
│                                                               │
│  参考音频 (WAV)                    输入文本                    │
│       │                               │                        │
│       ▼                               ▼                        │
│  ┌───────────┐                  ┌─────────────┐               │
│  │ CN-Hubert │                  │ 文本预处理   │               │
│  │ 特征提取  │                  │ (Cleaner)   │               │
│  └────┬──────┘                  └──────┬──────┘               │
│       │                               │                        │
│       ▼                               ▼                        │
│  ┌───────────┐                  ┌─────────────┐               │
│  │ SoVITS    │                  │   BERT      │               │
│  │ 语义编码  │                  │ 语义特征    │               │
│  └────┬──────┘                  └──────┬──────┘               │
│       │                               │                        │
│       └──────────────┬────────────────┘                        │
│                      ▼                                        │
│            ┌─────────────────┐                                │
│            │     GPT 模型     │                                │
│            │  (S1: Text2Semantic)                            │
│            │  语义序列生成    │                                │
│            └────────┬────────┘                                │
│                     ▼                                         │
│            ┌─────────────────┐                                │
│            │   SoVITS 模型    │                                │
│            │  (S2: Synthesizer)                              │
│            │  声码器解码      │                                │
│            └────────┬────────┘                                │
│                     ▼                                         │
│            ┌─────────────────┐                                │
│            │   Vocoder        │                                │
│            │  (BigVGAN/HifiGAN)                              │
│            └────────┬────────┘                                │
│                     ▼                                         │
│               输出音频 (WAV)                                   │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

### 5.2 核心模块说明

#### 5.2.1 API 服务

**入口文件**: [api.py](file:///d:/ai/Documents/qq-web/GPT-SoVITS-v2pro-20250604-nvidia50/api.py)

**关键类**:

| 类名 | 职责 | 核心方法 |
|------|------|----------|
| `DefaultRefer` | 默认参考音频管理 | `is_ready()` |
| `Speaker` | 说话人模型封装 | 存储 GPT + SoVITS 模型 |
| `Sovits` | SoVITS 模型封装 | 存储 VQ 模型 + 配置 |
| `Gpt` | GPT 模型封装 | 存储 T2S 模型 + max_sec |
| `DictToAttrRecursive` | 字典转属性递归类 | 配置访问辅助 |

**API 端点**:

| 端点 | 方法 | 功能 |
|------|------|------|
| `/` | GET/POST | 语音合成推理 |
| `/set_model` | GET/POST | 切换模型权重 |
| `/change_refer` | GET/POST | 修改默认参考音频 |
| `/control` | GET/POST | 命令控制（restart/exit） |

**推理参数**:

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `text` | string | - | 待合成文本 |
| `text_language` | string | - | 文本语言 |
| `refer_wav_path` | string | - | 参考音频路径 |
| `prompt_text` | string | - | 参考音频文本 |
| `prompt_language` | string | - | 参考音频语言 |
| `top_k` | int | 15 | 采样 top-k |
| `top_p` | float | 0.6 | 采样 top-p |
| `temperature` | float | 0.6 | 温度系数 |
| `speed` | float | 1 | 语速 |
| `cut_punc` | string | - | 分句符号 |

#### 5.2.2 配置管理

**入口文件**: [config.py](file:///d:/ai/Documents/qq-web/GPT-SoVITS-v2pro-20250604-nvidia50/config.py)

**关键配置项**:

```python
# 预训练模型路径映射
pretrained_sovits_name = {
    "v1": "GPT_SoVITS/pretrained_models/s2G488k.pth",
    "v2": "GPT_SoVITS/pretrained_models/gsv-v2final-pretrained/s2G2333k.pth",
    "v3": "GPT_SoVITS/pretrained_models/s2Gv3.pth",
    "v4": "GPT_SoVITS/pretrained_models/gsv-v4-pretrained/s2Gv4.pth",
    "v2Pro": "GPT_SoVITS/pretrained_models/v2Pro/s2Gv2Pro.pth",
    "v2ProPlus": "GPT_SoVITS/pretrained_models/v2Pro/s2Gv2ProPlus.pth",
}

# 全局配置类
class Config:
    sovits_path      # SoVITS 模型路径
    gpt_path         # GPT 模型路径
    is_half          # 是否半精度推理
    cnhubert_path    # CN-Hubert 路径
    bert_path        # BERT 路径
    infer_device     # 推理设备 (cuda/cpu)
    api_port         # API 端口 (9880)
```

**设备自动检测**（`get_device_dtype_sm()` 函数）：
- 根据 GPU 显存和算力自动选择设备
- 显存 < 4GB 或算力 < 5.3 自动降级为 CPU
- 自动决定是否使用半精度（fp16）

#### 5.2.3 模型模块

**核心文件**: [models.py](file:///d:/ai/Documents/qq-web/GPT-SoVITS-v2pro-20250604-nvidia50/GPT_SoVITS/module/models.py)

**关键类**:

| 类名 | 说明 | 主要功能 |
|------|------|----------|
| `StochasticDurationPredictor` | 随机时长预测器 | 预测音素时长 |
| `SynthesizerTrn` | SoVITS v1/v2 合成器 | 向量量化 + 解码器 |
| `SynthesizerTrnV3` | SoVITS v3/v4 合成器 | CFM 流匹配解码器 |
| `Generator` | HiFi-GAN 声码器 | 梅尔频谱转波形 |

**模型版本特性**:

| 版本 | 采样率 | 声码器 | 特性 |
|------|--------|--------|------|
| v1 | 32kHz | HiFi-GAN | 基础版本 |
| v2 | 32kHz | HiFi-GAN | 5k小时训练数据 |
| v2Pro | 32kHz | ERes2Net | 更高质量，低显存 |
| v3 | 24kHz | BigVGAN | 流式推理支持 |
| v4 | 48kHz | HiFi-GAN | 金属音修复，原生48k |

#### 5.2.4 文本处理

**目录**: `GPT_SoVITS/text/`

**模块职责**:

| 文件 | 功能 |
|------|------|
| `chinese.py` | 中文拼音转换、音素处理 |
| `japanese.py` | 日文音素处理（OpenJTalk） |
| `korean.py` | 韩文音素处理（g2pk2） |
| `cantonese.py` | 粤语音素处理（ToJyutping） |
| `cleaner.py` | 文本清洗和规范化 |
| `zh_normalization/` | 中文文本正则化（数字、时间等） |
| `LangSegmenter/` | 多语种自动检测和切分 |

**文本处理流程**:
```
输入文本 → LangSegmenter 语种检测 → 按语种切分 → 各语种 Cleaner → 音素序列 + BERT特征
```

#### 5.2.5 特征提取器

**目录**: `GPT_SoVITS/feature_extractor/`

| 文件 | 功能 |
|------|------|
| `cnhubert.py` | CN-Hubert 语音编码器（提取语义特征） |
| `whisper_enc.py` | Whisper 编码器（备选方案） |

**CN-Hubert**: 用于从参考音频中提取语义特征，作为 GPT 模型的条件输入。

#### 5.2.6 说话人验证

**文件**: [sv.py](file:///d:/ai/Documents/qq-web/GPT-SoVITS-v2pro-20250604-nvidia50/GPT_SoVITS/sv.py)

**SV 类**:
```python
class SV:
    def __init__(self, device, is_half):
        # 加载 ERes2NetV2 预训练模型
    
    def compute_embedding3(self, wav):
        # 计算说话人嵌入特征
```

用于 v2Pro 版本，提取说话人特征以增强音色相似度。

### 5.3 工具模块

#### 5.3.1 UVR5 音频分离

**目录**: `tools/uvr5/`

**功能**: 将音频中的人声与伴奏分离，支持多种模型：
- MDX-Net
- BS-Roformer
- Mel-Band Roformer

**使用方式**:
```bash
python tools/uvr5/webui.py <device> <is_half> <port>
```

#### 5.3.2 ASR 语音识别

**目录**: `tools/asr/`

**支持引擎**:
- **FunASR** (达摩院): 中文语音识别
- **Faster Whisper**: 多语种语音识别

**使用方式**:
```bash
# 中文 ASR
python tools/asr/funasr_asr.py -i <input> -o <output>

# 多语种 ASR
python tools/asr/fasterwhisper_asr.py -i <input> -o <output> -l <language>
```

#### 5.3.3 音频处理工具

| 文件 | 功能 |
|------|------|
| `slice_audio.py` | 音频自动切片（数据集准备） |
| `audio_sr.py` | 音频超分辨率（24k → 48k） |
| `subfix_webui.py` | 字幕修复工具 |

---

## 6. 依赖关系

### 6.1 qq-ai-assistant 依赖

#### 后端依赖（Maven）

**核心依赖**（参考 [pom.xml](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/pom.xml)）：

| 依赖 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.0 | 应用框架 |
| Spring Security | - | 安全认证 |
| Spring Data JPA | - | 数据访问 |
| Spring WebSocket | - | WebSocket 通信 |
| MySQL Connector | - | MySQL 驱动 |
| H2 Database | - | 嵌入式数据库（开发/测试） |
| SQLite JDBC | 3.45.2.0 | SQLite 驱动 |
| MinIO | 8.5.7 | 对象存储 |
| JJWT | 0.12.3 | JWT 令牌 |
| FastJSON | 2.0.32 | JSON 处理 |
| Caffeine | - | 本地缓存 |
| Groovy | 3.0.22 | 脚本插件引擎 |
| Thumbnailator | 0.4.20 | 图片处理 |

#### 前端依赖（npm）

**核心依赖**（参考 [package.json](file:///d:/ai/Documents/qq-web/qq-ai-assistant/frontend/package.json)）：

| 依赖 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5.30 | 前端框架 |
| Vue Router | 4.6.4 | 路由管理 |
| Markdown-It | 14.1.0 | Markdown 渲染 |
| DOMPurify | 3.1.6 | HTML 净化 |
| Vite | 8.0.1 | 构建工具 |

### 6.2 AstrBot 依赖

AstrBot 作为二进制分发，依赖项已内置。主要运行时组件：

| 组件 | 版本 | 用途 |
|------|------|------|
| Python | 3.x | 运行时 |
| SQLite | - | 数据库存储 |
| FAISS | - | 向量检索 |
| Vue.js | 3.x | WebUI 前端 |

### 6.3 GPT-SoVITS 依赖

**主要 Python 包**（参考 [requirements.txt](file:///d:/ai/Documents/qq-web/GPT-SoVITS-v2pro-20250604-nvidia50/requirements.txt)）：

| 包名 | 版本 | 用途 |
|------|------|------|
| PyTorch | - | 深度学习框架 |
| PyTorch-Lightning | >=2.4 | 训练框架 |
| Transformers | 4.43-4.50 | 预训练模型 |
| Gradio | <5 | WebUI 框架 |
| FastAPI | >=0.115.2 | API 服务 |
| Librosa | 0.10.2 | 音频处理 |
| FunASR | 1.0.27 | 语音识别 |
| PEFT | - | 参数高效微调 |
| ONNXRuntime | - | ONNX 推理 |
| CTranslate2 | 4.x | 翻译加速 |

---

## 7. 项目运行方式

### 7.1 qq-ai-assistant 运行

#### 后端运行

```bash
cd qq-ai-assistant/backend

# 使用 Maven 启动（开发模式）
mvn spring-boot:run

# 或打包后运行
mvn clean package
java -jar target/qq-ai-assistant-1.0-SNAPSHOT.jar

# 服务访问地址: http://localhost:8081
```

**配置修改**:
- 编辑 `src/main/resources/application.yml` 修改数据库连接等配置
- 环境变量覆盖：通过 `.env` 文件或系统环境变量覆盖配置

#### 前端运行

```bash
cd qq-ai-assistant/frontend

# 安装依赖（首次运行）
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build

# 开发服务器访问地址: http://localhost:5173
```

**代理配置**:
- Vite 已配置代理，`/api`、`/images`、`/uploads` 转发到后端 `http://localhost:8081`
- WebSocket `/ws` 转发到后端 WebSocket

### 7.2 AstrBot 运行

**启动方式**（Windows）：
```bash
# 直接运行（二进制版本）
.\Astrbot\AstrBot.exe

# 或通过启动脚本
# WebUI 访问地址: http://localhost:6185
```

**配置修改**:
- 编辑 `data/cmd_config.json` 修改核心配置
- 通过 WebUI 管理插件和知识库

### 7.3 GPT-SoVITS 运行

#### API 模式
```bash
cd GPT-SoVITS-v2pro-20250604-nvidia50

# 启动 API 服务
python api.py -s <sovits模型路径> -g <gpt模型路径> -dr <参考音频> -dt <参考文本> -dl zh

# 使用默认配置启动（需提前配置 config.py）
python api.py

# 指定端口和设备
python api.py -p 9880 -d cuda -a 0.0.0.0

# 启用流式返回
python api.py -sm normal -mt ogg
```

#### WebUI 模式
```bash
# 启动主 WebUI
python webui.py

# 指定语言
python webui.py zh

# 启动推理专用 WebUI
python GPT_SoVITS/inference_webui.py
```

#### CLI 模式
```bash
python GPT_SoVITS/inference_cli.py \
    --gpt_model <模型路径> \
    --sovits_model <模型路径> \
    --ref_audio <参考音频> \
    --ref_text <参考文本> \
    --ref_language 中文 \
    --target_text <目标文本> \
    --target_language 中文 \
    --output_path <输出目录>
```

### 7.4 API 调用示例

#### 基本推理
```bash
# GET 请求
curl "http://localhost:9880?text=你好世界&text_language=zh"

# POST 请求
curl -X POST http://localhost:9880 \
    -H "Content-Type: application/json" \
    -d '{"text": "你好世界", "text_language": "zh"}'
```

#### 指定参考音频
```bash
curl -X POST http://localhost:9880 \
    -H "Content-Type: application/json" \
    -d '{
        "refer_wav_path": "reference.wav",
        "prompt_text": "参考文本内容",
        "prompt_language": "zh",
        "text": "待合成文本",
        "text_language": "zh",
        "top_k": 20,
        "top_p": 0.6,
        "temperature": 0.6
    }'
```

---

## 8. 配置管理

### 8.1 qq-ai-assistant 配置文件

| 文件 | 用途 | 格式 |
|------|------|------|
| `application.yml` | 后端核心配置（数据库、服务地址等） | YAML |
| `.env` | 环境变量覆盖（生产环境） | 键值对 |

**关键配置项**（参考 [application.yml](file:///d:/ai/Documents/qq-web/qq-ai-assistant/backend/src/main/resources/application.yml)）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | 8081 | 后端服务端口 |
| `spring.datasource.url` | SQLite 内存库 | 数据库连接 |
| `astrbot.api-url` | http://localhost:6185 | AstrBot API 地址 |
| `napcat.api-url` | http://localhost:6099 | NapCat API 地址 |
| `gpt-sovits.api-url` | http://localhost:8000 | GPT-SoVITS API 地址 |
| `jwt.secret` | (随机生成) | JWT 密钥 |
| `jwt.expire` | 86400000 | Token 过期时间（毫秒） |

### 8.2 AstrBot 配置文件

| 文件 | 用途 | 格式 |
|------|------|------|
| `cmd_config.json` | 核心配置 | JSON |
| `plugins.json` | 插件注册表 | JSON |
| `skills.json` | 技能配置 | JSON |
| `mcp_server.json` | MCP 服务器 | JSON |
| `data/config/astrbot_plugin_*.json` | 各插件配置 | JSON |

### 8.3 GPT-SoVITS 配置文件

| 文件 | 用途 |
|------|------|
| `config.py` | 全局配置（模型路径、设备、端口等） |
| `GPT_SoVITS/pretrained_models/` | 预训练模型文件 |
| `tools/uvr5/uvr5_weights/` | UVR5 模型权重 |
| `tools/asr/models/` | ASR 模型文件 |

---

## 9. 数据存储

### 9.1 qq-ai-assistant 数据

| 类型 | 存储方式 | 说明 |
|------|----------|------|
| 消息数据 | SQLite/MySQL/H2 | 通过 JPA 访问 |
| 文件上传 | MinIO/本地文件系统 | 图片、附件存储 |
| 用户会话 | Redis（可选） | 用户登录状态 |
| 缓存 | Caffeine | 本地缓存 |

### 9.2 AstrBot 数据

| 目录 | 存储内容 |
|------|----------|
| `data/knowledge_base/` | 知识库向量数据（FAISS + SQLite） |
| `data/config/` | 配置文件 |
| `data/t2i_templates/` | 图生图模板 |
| `data/data_v4.db` | 主数据库 |

### 9.3 GPT-SoVITS 数据

| 目录 | 存储内容 |
|------|----------|
| `GPT_SoVITS/pretrained_models/` | 预训练模型 |
| `logs/` | 训练日志 |
| `SoVITS_weights_*/` | SoVITS 微调权重 |
| `GPT_weights_*/` | GPT 微调权重 |

---

## 10. 开发与调试

### 10.1 调试记录

项目包含一个调试文档 [debug-api-json-error.md](file:///d:/ai/Documents/qq-web/debug-api-json-error.md)，记录了以下问题：
- API JSON 解析错误处理
- 音频加载错误处理
- 头像上传连接重置问题

### 10.2 TRAE 开发环境

项目包含 `.trae/` 目录，用于 TRAE IDE 开发环境配置：
- `documents/`: 设计文档
- `specs/`: 需求规格说明

---

## 11. 版本与更新

### 11.1 qq-ai-assistant

- **后端版本**: 1.0-SNAPSHOT（Maven 项目）
- **前端版本**: 1.0.0（Vue 3 + Vite）

### 11.2 AstrBot

- **配置版本**: `config_version: 2`（来自 cmd_config.json）
- **WebUI**: 构建版本已打包在 `data/dist/`

### 11.3 GPT-SoVITS

- **当前版本**: v2Pro（默认）
- **支持版本**: v1, v2, v2Pro, v2ProPlus, v3, v4
- **模型文件**: 需单独下载预训练权重

---

## 附录：常见问题

### Q: qq-ai-assistant 如何集成 AstrBot 和 GPT-SoVITS？
A: 在 `application.yml` 中配置各服务的 API 地址和 Token：
- `astrbot.api-url`: AstrBot API 地址（默认 http://localhost:6185）
- `napcat.api-url`: NapCat 平台适配器地址（默认 http://localhost:6099）
- `gpt-sovits.api-url`: GPT-SoVITS API 地址（默认 http://localhost:8000）

### Q: qq-ai-assistant 支持哪些数据库？
A: 支持三种数据库：
- **SQLite**（默认，内存模式）：开发测试使用
- **MySQL**：生产环境推荐
- **H2**：嵌入式数据库，用于快速原型

### Q: AstrBot 如何连接 QQ 平台？
A: 需要通过平台适配器（如 NapCat）连接，配置在 `platform` 字段中。

### Q: GPT-SoVITS 如何训练自定义语音？
A: 
1. 准备音频数据（至少1分钟）
2. 使用工具进行音频切片和 ASR 标注
3. 在 WebUI 中配置训练参数
4. 依次训练 S1（GPT）和 S2（SoVITS）模型

### Q: 如何添加新插件到 AstrBot？
A: 
1. 在插件市场找到插件
2. 通过 WebUI 安装或手动下载
3. 配置插件参数（生成对应的 config.json）

### Q: 知识库如何使用？
A: 
1. 在 WebUI 创建知识库集合
2. 上传文档或文本
3. 在 `cmd_config.json` 中启用知识库
4. 配置 `kb_final_top_k` 控制返回数量

---

**文档生成时间**: 2026-08-21
**更新内容**: 积分系统 + 月卡订阅体系、清理无用文件、统一异常处理
**项目路径**: `d:\ai\Documents\qq-web`