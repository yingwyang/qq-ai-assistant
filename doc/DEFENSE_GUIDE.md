# QQ AI 助手 - 毕业设计答辩准备指南

## 一、项目简介（开场 30 秒）

> 这是一个 **QQ 群智能管理平台**，基于 NapCat 协议对接 QQ，集成 AstrBot AI 引擎实现群聊 AI 对话、智能摘要、TTS 语音合成，并提供积分订阅体系和管理后台，解决 QQ 群信息管理效率低、AI 能力缺失的问题。

### 核心亮点

| # | 亮点 | 说明 |
|---|------|------|
| 1 | AI + 消息 + 订阅三合一 | 不仅是聊天管理，还集成了 AI 对话和商业化订阅 |
| 2 | 实时 WebSocket 推送 | 消息零延迟展示，群聊体验接近原生 QQ |
| 3 | 积分按消息粒度计费 | 结合上下文长度、模型倍率、月卡折扣精确计费 |
| 4 | 媒体自动处理 | 图片 AI 分析、语音 SILK→MP3 转码、文件归档全自动化 |
| 5 | Groovy 插件引擎 | 支持热部署 AI 响应逻辑，无需重启服务 |

---

## 二、技术栈

| 层级 | 技术 | 版本 | 选型理由 |
|------|------|------|----------|
| 前端框架 | Vue 3 | 3.5 | Composition API 代码复用性高 |
| 前端构建 | Vite | 8.x | 冷启动快、HMR 迅速 |
| 前端图表 | ECharts | 6.x | 数据看板可视化 |
| 后端框架 | Spring Boot | 3.2 | 生态成熟、JPA/安全框架完善 |
| Java 版本 | JDK | 17 | LTS 长期支持 |
| 数据库 | MySQL | 8.0 | 关系型，适合事务性积分/订单 |
| 内存数据库 | H2 | 2.x | 仅测试用（test scope） |
| 消息队列 | RabbitMQ | 3.12 | 解耦耗时任务，异步处理 |
| 对象存储 | MinIO | - | 兼容 S3，存储聊天媒体文件 |
| 安全认证 | JWT + Spring Security | - | 无状态认证，支持细粒度权限 |
| AI 引擎 | AstrBot | - | 多平台 AI 聊天机器人框架 |
| 语音合成 | GPT-SoVITS | v2pro | 少样本语音克隆与 TTS |
| QQ 协议 | NapCat | OneBot 11 | 官方协议实现，稳定性好 |
| 动态脚本 | Groovy | - | 插件引擎，热部署扩展逻辑 |

---

## 三、系统架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户浏览器                              │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Vue 3 + Vite 前端                     │   │
│  │                                                         │   │
│  │  LoginPage │ HomeView │ UserCenter │ AdminView │ Docs   │   │
│  │                                                         │   │
│  │  REST API ──────────────────► WebSocket ◄──┐            │   │
│  └─────────────────────────────┬───────────────┼───────────┘   │
│                                │               │               │
└────────────────────────────────┼───────────────┼───────────────┘
                                 │               │
                                 ▼               ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot 后端                              │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Security Layer (JWT + Spring Security)                  │   │
│  └───────────────────────────┬─────────────────────────────┘   │
│                              │                                  │
│  ┌───────────────────────────▼─────────────────────────────┐   │
│  │  Controller Layer (21 个 REST 控制器)                    │   │
│  │  AuthController / MessageController / CreditsController │   │
│  │  SubscriptionController / AdminController ...            │   │
│  └───────────────────────────┬─────────────────────────────┘   │
│                              │                                  │
│  ┌───────────────────────────▼─────────────────────────────┐   │
│  │  Service Layer (29 个业务服务)                           │   │
│  │  MessageService / CreditService / SubscriptionService   │   │
│  │  NapCatService / AstrBotService / GptSovitsService      │   │   │
│  └──────────┬──────────────────────────┬───────────────────┘   │
│             │                          │                        │
│  ┌──────────▼──────────┐    ┌──────────▼──────────────────┐   │
│  │ Repository Layer   │    │     External Integrations    │   │
│  │ (JPA + Hibernate)   │    │  RabbitMQ / MinIO / AstrBot │   │
│  └──────────┬──────────┘    │  GPT-SoVITS / NapCat        │   │
│             │               └──────────┬──────────────────┘   │
│             │                          │                        │
└─────────────┼──────────────────────────┼───────────────────────┘
              │                          │
              ▼                          ▼
┌─────────────────────┐    ┌───────────────────────┐
│   MySQL 8.0         │    │   NapCat (QQ 协议)    │
│   16 张核心表       │    │   WebSocket / Webhook │
└─────────────────────┘    └──────────┬────────────┘
                                       │
                                       ▼
                              ┌───────────────────┐
                              │    QQ 群聊        │
                              └───────────────────┘
```

### 3.2 消息处理数据流

```
QQ 用户发消息
      │
      ▼
  NapCat 接收 (OneBot 11 WebSocket)
      │
      ▼
  POST /api/napcat/webhook
      │
      ▼
  RootWebhookController.receiveMessageRoot()
      │
      ├── MessageParserService.parseMessage()
      │     ├── 解析 OneBot JSON → Message 实体
      │     ├── 提取 @ 信息、reply 引用
      │     ├── 识别转发/小程序/图片/语音
      │     └── 生成唯一 message_id
      │
      ├── 媒体异步处理（RabbitMQ）
      │     ├── MediaDownloadConsumer 下载媒体到 MinIO
      │     ├── VoiceTranscodeConsumer SILK → MP3
      │     └── AiAnalysisConsumer 调用 AI 分析图片
      │
      ├── MessageService.saveMessage()
      │     ├── 存入 MySQL messages 表
      │     ├── 去重（message_id 唯一索引）
      │     └── 推 WebSocket 给所有在线前端
      │
      └── AI 触发判断
            ├── @AI → AstrBot API → AI 回复 → NapCat 发回 QQ
            ├── 关键词匹配 → 预设响应
            └── 普通消息 → 仅存储不回复
```

### 3.3 积分计费流程

```
用户发送 AI 消息
      │
      ▼
  CreditService.checkAndDeduct()
      │
      ├── 1. 查询 credit_rule 获取当前计费规则
      │
      ├── 2. 计算基础消耗
      │     基础 = default_cost_per_msg
      │
      ├── 3. 上下文额外消耗
      │     上下文数 > free_msg_count
      │     ? (actual - free) × context_extra_cost_per_msg
      │     : 0
      │
      ├── 4. 模型倍率
      │     基础消耗 × model_rates[当前模型]
      │
      ├── 5. 月卡折扣
      │     阶梯折扣 tiered_discount_thresholds
      │     或 all_tier_discount 通用折扣
      │
      └── 6. 原子扣减
            UPDATE user_credit
            SET balance = balance - ?, version = version + 1
            WHERE user_id = ? AND balance >= ?
            （乐观锁 + 余额检查，防超扣）
```

---

## 四、数据库设计

### 4.1 表清单（16 张核心表）

| 模块 | 表名 | 说明 | 行数 |
|------|------|------|------|
| **用户** | `users` | 用户账号表 | 5 |
| | `user_qq_bindings` | QQ 号绑定 | 4 |
| | `user_settings` | 用户偏好设置 | 1 |
| **积分** | `user_credit` | 积分账户 | 4 |
| | `credit_rule` | 积分规则（全局单行） | 1 |
| | `credit_transaction` | 积分流水 | 116 |
| | `subscription_order` | 订阅订单 | 38 |
| | `sign_in_record` | 签到记录 | 11 |
| | `monthly_bonus_record` | 月卡每日奖励 | 4 |
| **消息** | `messages` | 聊天消息 | 77,651 |
| | `chat_groups` | 群聊信息 | 47 |
| | `file_records` | 文件记录 | 0 |
| | `group_read_state` | 已读状态 | 51 |
| | `astrbot_conversations` | AI 对话会话 | 6 |
| | `astrbot_messages` | AI 对话消息 | 16 |
| **系统** | `audit_log` | 审计日志 | 158 |

### 4.2 核心 ER 关系

```
users 1:1 user_credit
users 1:N user_qq_bindings
users 1:N credit_transaction
users 1:N subscription_order
users 1:1 user_settings

chat_groups 1:N messages
chat_groups 1:N group_read_state

astrbot_conversations 1:N astrbot_messages

subscription_order N:1 users
credit_transaction N:1 users
```

### 4.3 关键字段设计

**messages 表（聊天消息核心）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `message_id` | VARCHAR | OneBot 原始消息 ID（唯一索引，用于去重） |
| `group_id` | VARCHAR | QQ 群号 |
| `message_type` | VARCHAR | TEXT/IMAGE/VOICE/VIDEO/FILE/... |
| `user_id` | BIGINT | 系统用户 ID（关联 users 表） |
| `content` | MEDIUMTEXT | 消息内容（文本/媒体元数据 JSON） |
| `is_self_message` | BIT | 是否为自己发送的消息 |
| `media_pending` | BIT | 媒体异步下载处理中 |
| `msg_seq` | INT | OneBot 消息序号（排序用） |

**credit_rule 表（积分规则，单行全局配置）**

| 字段 | 说明 | 示例 |
|------|------|------|
| `default_cost_per_msg` | 每条消息基础消耗 | 10 |
| `new_user_bonus` | 新用户注册赠送 | 500 |
| `sign_in_points` | 签到基础积分 | 10 |
| `model_rates` | 各模型倍率（JSON） | `{"gpt-4o":2,"gpt-3.5":1}` |
| `tiered_discount_thresholds` | 阶梯折扣（JSON） | `[{"min":100,"rate":0.9}]` |
| `all_tier_discount` | 通用折扣 | 0.85 |

**subscription_order 表（订阅订单）**

| 字段 | 类型 | 说明 |
|------|------|------|
| `plan_tier` | ENUM | FREE/LITE/PRO/ULTRA/SMALL_MONTH_CARD/LARGE_MONTH_CARD/ALL |
| `status` | ENUM | PENDING/PAID/REFUNDED/CANCELLED/EXPIRED |
| `price` | DECIMAL | 订单金额 |
| `credit_amount` | INT | 到账积分 |
| `duration_days` | INT | 订阅天数 |
| `expires_at` | DATETIME | 过期时间 |

---

## 五、安全认证体系

### 5.1 认证流程

```
┌────────┐    POST /api/auth/login    ┌────────────┐
│  前端  │ ──────────────────────────► │ AuthController │
│        │     {username, password}   └──────┬───────┘
│        │                                   │ BCrypt 验证
│        │                                   ▼
│        │                            JwtUtil.generateToken()
│        │                            ├── Header: {"alg":"HS256"}
│        │                            ├── Payload: {sub, role, ver}
│        │                            └── Signature: HMAC-SHA256(secret)
│        │                                   │
│        │     {token}                       │
│        │ ◄─────────────────────────────────┘
│        │                                   │
│ localStorage                               │
│ .setItem('token', token)                   │
└────────┘                                   │
                                             │
                  后续请求                      │
                  Authorization: Bearer <token>│
                                             │
                                             ▼
                                  JwtAuthenticationFilter
                                  ├── 解析 JWT
                                  ├── 验证签名
                                  ├── 检查黑名单
                                  ├── 检查 token_version
                                  └── 设置 SecurityContext
```

### 5.2 权限控制

| 注解 | 权限要求 | 使用场景 |
|------|----------|----------|
| `@PreAuthorize("isAuthenticated()")` | 已登录 | 所有登录用户可访问 |
| `@PreAuthorize("hasRole('ADMIN')")` | 管理员角色 | 管理后台接口 |
| `@Secured("ROLE_ADMIN")` | 管理员角色 | 审计/系统设置 |

### 5.3 Token 安全策略

| 场景 | 处理 |
|------|------|
| 修改密码 | `token_version + 1`，旧 Token 全部失效 |
| 退出登录 | Token 加入黑名单（Redis 存储） |
| Token 过期 | JWT `exp` 字段自动失效 |
| 会话劫持 | 黑名单 + 版本号双重保护 |

---

## 六、核心业务逻辑

### 6.1 实时通信

```
前端 ◄──WebSocket──► 后端
                      │
                      ├── FrontendMessageWebSocketHandler
                      │     ├── 新消息到达 → 推送给所有在线客户端
                      │     ├── 消息已读 → 广播已读状态
                      │     └── 输入中状态 → 广播 typing
                      │
                      └── NapCatWebSocketHandler
                            ├── NapCat 状态监控
                            └── QQ 连接状态变更通知
```

### 6.2 媒体处理流水线

```
消息到达 → 判断包含媒体
    │
    ├── 图片
    │     └── MediaDownloadConsumer → MinIO 存储
    │         └── AiAnalysisConsumer → AI 分析内容
    │
    ├── 语音
    │     └── MediaDownloadConsumer → 下载 SILK 文件
    │         └── VoiceTranscodeConsumer → FFmpeg 转码为 MP3
    │
    ├── 视频
    │     └── MediaDownloadConsumer → 下载视频到 MinIO
    │
    └── 文件
          └── MediaDownloadConsumer → 下载到 MinIO
              └── FilePurgeService → 根据策略自动清理
```

### 6.3 订阅状态机

```
                ┌──────────────┐
                │   PENDING    │
                └──────┬───────┘
                       │ 支付成功
                       ▼
                ┌──────────────┐
    ┌──────────►│    PAID      │◄──────────┐
    │           └──────┬───────┘           │
    │                  │ 过期               │ 退款审批通过
    │                  ▼                   │
    │           ┌──────────────┐           │
    │           │   EXPIRED    │           │
    │           └──────────────┘           │
    │                                      │
    │                  发起退款             │
    │                  ▼                   │
    │           ┌──────────────┐           │
    │           │PENDING_REFUND│           │
    │           └──────┬───────┘           │
    │                  │                   │
    │           ┌──────┴───────┐           │
    │           ▼              ▼           │
    │    ┌──────────────┐ ┌──────────────┐ │
    │    │   REFUNDED   │ │  DISPUTED    │ │
    │    └──────────────┘ └──────────────┘ │
    │                                      │
    │   (取消)                             │
    │    ┌──────────────┐                  │
    └──►│  CANCELLED   │                  │
         └──────────────┘                  │
                                           │
                    ┌──────────────────────┘
                    │
                    ▼
             ┌──────────────┐
             │   REFUNDED   │
             └──────────────┘
```

### 6.4 插件引擎（Groovy）

```java
// 插件接口
public interface AiResponsePlugin {
    String getName();
    String getVersion();
    String onMessage(Message message, String aiResponse);
}

// 热加载
@Service
public class PluginManager {
    // 启动时扫描 plugins/ 目录
    // 使用 GroovyClassLoader 动态加载
    // 支持运行时增删插件，无需重启
}
```

---

## 七、项目文件结构

```
qq-ai-assistant/
│
├── backend/                              # Spring Boot 后端
│   ├── src/main/java/com/qqai/
│   │   ├── Application.java              # 启动类
│   │   ├── controller/                   # 21 个 REST 控制器
│   │   │   ├── AuthController.java       # 登录/注册
│   │   │   ├── MessageController.java    # 消息管理
│   │   │   ├── NapCatWebhookController.java  # NapCat Webhook
│   │   │   ├── RootWebhookController.java    # 消息解析核心
│   │   │   ├── CreditsController.java    # 积分查询
│   │   │   ├── SubscriptionsController.java  # 订阅管理
│   │   │   ├── AdminController.java      # 管理后台
│   │   │   ├── DashboardController.java  # 数据看板
│   │   │   └── ...
│   │   ├── service/                      # 29 个业务服务
│   │   │   ├── MessageService.java       # 消息核心逻辑
│   │   │   ├── MessageParserService.java  # OneBot 消息解析
│   │   │   ├── CreditService.java        # 积分计算与扣减
│   │   │   ├── SubscriptionService.java  # 订阅订单处理
│   │   │   ├── NapCatService.java        # NapCat 协议封装
│   │   │   ├── AstrBotService.java       # AI 引擎对接
│   │   │   ├── GptSovitsService.java     # TTS 语音合成
│   │   │   └── ...
│   │   ├── entity/                       # 16 个 JPA 实体
│   │   ├── repository/                   # 16 个数据访问接口
│   │   ├── config/                       # Spring 配置类
│   │   ├── security/                     # JWT 认证模块
│   │   ├── consumer/                     # 4 个 MQ 消费者
│   │   ├── websocket/                    # WebSocket 处理
│   │   ├── plugin/                       # Groovy 插件引擎
│   │   ├── exception/                    # 异常处理体系
│   │   ├── dto/                          # 数据传输对象
│   │   └── common/                       # 通用工具类
│   │
│   ├── src/main/resources/
│   │   ├── application.yml               # 主配置（含 dev profile）
│   │   ├── init-mysql.sql                # 数据库初始化脚本
│   │   ├── prompts.yml                   # AI 提示词模板（39KB）
│   │   └── logback-spring.xml            # 日志配置
│   │
│   ├── src/test/                         # 单元/集成测试
│   │   ├── CreditIntegrationTest.java
│   │   ├── CreditControllerIntegrationTest.java
│   │   └── RichMessageRendererTest.java
│   │
│   └── pom.xml
│
├── frontend/                             # Vue 3 前端
│   ├── src/
│   │   ├── views/                        # 5 个页面视图
│   │   │   ├── LoginPage.vue             # 登录页
│   │   │   ├── HomeView.vue              # 首页（聊天界面）
│   │   │   ├── UserCenter.vue            # 用户中心（积分/订阅）
│   │   │   ├── AdminView.vue             # 管理后台入口
│   │   │   └── DocView.vue               # 文档查看
│   │   ├── views/admin/                  # 13 个管理后台子页
│   │   │   ├── AdminDashboard.vue        # 数据看板
│   │   │   ├── AdminUsers.vue            # 用户管理
│   │   │   ├── AdminOrders.vue           # 订单管理
│   │   │   ├── AdminTransactions.vue      # 积分流水
│   │   │   ├── AdminCreditsRule.vue      # 积分规则配置
│   │   │   └── ...
│   │   ├── components/                   # 17 个 UI 组件
│   │   ├── composables/                  # 20 个组合式函数
│   │   ├── services/api.js               # API 请求封装
│   │   ├── router/index.js               # 路由配置 + 守卫
│   │   ├── docs/                         # 内置 Markdown 文档
│   │   └── utils/                        # 工具函数
│   ├── public/                           # 静态资源
│   ├── index.html
│   ├── vite.config.js
│   └── package.json
│
├── doc/                                  # 项目文档
│   ├── README.md                         # 详细技术文档
│   ├── CODE_WIKI.md                      # 代码百科
│   └── user-manual.md                    # 用户手册
│
├── .env                                  # 本地环境变量（不提交 Git）
├── .env.example                          # 环境变量模板
├── docker-compose.yml                    # Docker 部署编排
├── .gitignore
└── README.md                             # 项目首页 README
```

---

## 八、答辩高频问题

### Q1: 项目创新点是什么？

> 1. **积分 + 订阅混合计费**：按消息粒度实时扣费，结合上下文长度、模型倍率、阶梯折扣精确计费，同时支持月卡包月，解决 AI 资源成本回收问题
> 2. **NapCat + AstrBot 深度集成**：不仅是协议转发，还实现了媒体自动下载、SILK→MP3 转码、AI 图片分析等增值功能
> 3. **Groovy 插件引擎**：支持热部署 AI 响应逻辑，管理员可动态添加关键词自动回复，无需重启服务
> 4. **消息智能归档**：90 天以上历史消息自动归档，冷热数据分离，保证查询性能

### Q2: 遇到的最大技术挑战？

> **QQ 消息去重与顺序保证**
>
> **问题**：NapCat 在网络波动时会重复推送同一条消息，且高并发下消息到达顺序可能不一致
>
> **解决方案**：
> 1. 数据库 `message_id` 字段加唯一索引，INSERT 冲突自动跳过
> 2. 使用 `msg_seq`（OneBot 消息序号）作为排序依据，而非 INSERT 时间
> 3. `server_recv_ms` 记录服务端接收毫秒时间戳，配合 msg_seq 双重排序
> 4. Redis 缓存最近 100 条 message_id 做快速去重（命中直接跳过，不查库）

### Q3: RabbitMQ 的作用？为什么不用直接调用？

> **作用**：耗时任务异步解耦
>
> | 任务 | 耗时 | 同步后果 |
> |------|------|----------|
> | 媒体文件下载 | 1-5 秒 | 阻塞 Webhook 响应 |
> | SILK→MP3 转码 | 2-10 秒 | CPU 密集，阻塞主线程 |
> | AI 图片分析 | 5-30 秒 | 调用外部 API，不稳定 |
>
> **为什么不用直接调用**：NapCat Webhook 有超时机制（默认 5 秒），超时会认为消息投递失败并重试，导致重复消费。用 MQ 后 Webhook 立即返回 200，耗时任务异步处理。

### Q4: 积分系统如何防止作弊？

> 1. **操作原子性**：余额扣减使用条件更新
>    ```sql
>    UPDATE user_credit
>    SET balance = balance - ?, version = version + 1
>    WHERE user_id = ? AND balance >= ?
>    ```
>    数据库层面防止超扣，`version` 字段实现乐观锁
>
> 2. **防刷机制**：
>    - 注册接口 IP 限流：1 分钟内同 IP 最多 5 次
>    - AI 消息频率限制：单用户每分钟最多 30 条
>    - 消费禁用标记：`user_credit.consumption_banned` 可冻结恶意用户
>
> 3. **会话安全**：修改密码后 `token_version + 1`，所有旧 Token 立即失效

### Q5: 前后端如何通信？

> 三种通信方式：
>
> | 方式 | 用途 | 实现 |
> |------|------|------|
> | REST API | 常规数据 CRUD | `@RestController` + `axios` |
> | WebSocket | 实时推送新消息 | `WebSocketHandler` + 浏览器原生 API |
> | SSE | AI 对话流式响应 | `SseEmitter` + `EventSource` |
>
> 前端通过路由守卫 + `localStorage` 缓存登录状态，后端通过 JWT 无状态认证，同源 Cookie 自动携带。

### Q6: 为什么选择 Spring Boot 而不是 SSM？

> 1. **自动配置**：内嵌 Tomcat，无需外部部署 WAR 包
> 2. **生态完整**：Spring Security、Spring Data JPA、Spring AMQP 开箱即用
> 3. **配置管理**：`application.yml` + Profile 切换多环境
> 4. **社区成熟**：中文文档/教程多，遇到问题容易找到解决方案
> 5. **与 Vue 配合好**：前后端分离，RESTful API 天然适配

### Q7: 为什么要做积分系统？

> 1. **回收 AI 成本**：调用 LLM API 是付费的，需要通过积分消耗覆盖成本
> 2. **防止滥用**：积分门槛有效防止恶意刷 AI 资源
> 3. **商业化可能**：订阅套餐为未来商业化打基础
> 4. **用户行为激励**：签到送积分、邀请奖励等提升用户粘性

### Q8: 消息表有 77,651 条数据，如何优化查询？

> 1. **索引优化**：
>    - `idx_group_time(group_id, send_time)`：按群聊+时间查询
>    - `idx_user_time(user_qq, send_time)`：按用户+时间查询
>    - `idx_group_msgid(group_id, message_id)`：消息去重
>    - `idx_archived(archived)`：已归档消息过滤
>
> 2. **分页查询**：使用 Spring Data `Pageable`，每页默认 20 条
>
> 3. **冷热分离**：`archived` 字段标记归档消息，查询默认过滤
>
> 4. **定时归档**：`MessageArchiveService` 每日凌晨 2 点将 90 天以上消息标记归档

### Q9: MinIO 和 MySQL 为什么分开存文件？

> | 对比项 | MySQL | MinIO |
> |--------|-------|-------|
> | 存储内容 | 消息元数据（文本、JSON） | 媒体文件（图片/视频/音频） |
> | 优势 | 事务性、索引查询、关联查询 | 海量存储、S3 兼容、CDN 加速 |
> | 代价 | BLOB 存储影响查询性能 | 需要单独部署维护 |
>
> 设计理由：媒体文件可能很大（几十 MB），存入 MySQL 会导致：
> 1. 数据库膨胀过快，备份恢复变慢
> 2. BLOB 数据影响索引效率
> 3. 无法利用 CDN 加速分发

### Q10: 如果让你重新设计，会做哪些改进？

> 1. **缓存层**：引入 Redis 缓存热点数据（积分规则、用户配置）
> 2. **搜索引擎**：引入 Elasticsearch 支持消息全文检索
> 3. **微服务拆分**：将消息/积分/AI 拆分为独立服务
> 4. **容器化**：完善 Docker Compose + Kubernetes 部署
> 5. **监控告警**：接入 Prometheus + Grafana 监控系统
> 6. **CI/CD**：GitHub Actions 自动构建部署

---

## 九、演示流程建议（约 12 分钟）

| 步骤 | 操作 | 时长 | 备注 |
|------|------|------|------|
| **1** | 登录 admin 账号 | 30s | 展示 JWT 认证流程 |
| **2** | 首页消息列表 | 1min | WebSocket 实时推送 77K 消息分页 |
| **3** | 切换群聊 | 1min | 展示群聊列表 + 已读状态 |
| **4** | AI 对话演示 | 2min | AstrBot 接入，展示 AI 回复 |
| **5** | 用户中心 | 2min | 积分流水、签到、订阅购买 |
| **6** | 管理后台 | 2min | 用户管理、积分规则、订单管理 |
| **7** | 数据看板 | 1min | ECharts 图表展示 |
| **8** | 退出登录 | 30s | 展示 Token 失效流程 |

---

## 十、快速记忆口诀

```
一句话：QQ 群智能管理，AI + 积分 + 订阅三合一
架构：前端 Vue，后端 Spring，NapCat 接 QQ，AstrBot 做 AI
亮点：积分按消息扣，月卡打折，MQ 异步，JWT 认证
难点：消息去重、媒体转码、防作弊
数据：16 张表，77K 消息，116 条积分流水
技术栈：Spring Boot 3.2 + Vue 3.5 + MySQL 8 + RabbitMQ + MinIO
```

---

## 附录：关键 API 接口

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/auth/login` | 用户登录 | 公开 |
| POST | `/api/auth/register` | 用户注册 | 公开 |
| GET | `/api/auth/me` | 获取当前用户信息 | 已登录 |
| GET | `/api/messages` | 分页查询消息 | 已登录 |
| POST | `/api/messages` | 发送消息 | 已登录 |
| GET | `/api/credits/balance` | 查询积分余额 | 已登录 |
| GET | `/api/credits/transactions` | 查询积分流水 | 已登录 |
| POST | `/api/credits/sign-in` | 签到获取积分 | 已登录 |
| POST | `/api/subscriptions/purchase` | 购买订阅 | 已登录 |
| GET | `/api/admin/users` | 用户列表 | 管理员 |
| GET | `/api/admin/orders` | 订单列表 | 管理员 |
| GET | `/api/admin/config/credit-rule` | 获取积分规则 | 管理员 |
| PUT | `/api/admin/config/credit-rule` | 更新积分规则 | 管理员 |
| GET | `/api/admin/dashboard/stats` | 数据看板统计 | 管理员 |
| POST | `/api/napcat/webhook` | NapCat 消息回调 | NapCat Token |