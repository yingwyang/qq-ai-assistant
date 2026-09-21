---
title: 架构说明
description: 拓扑、请求链路、四大 MQ 队列与鉴权体系
updated: 2026-09-16
---

这一页面向二次开发与运维：一条 QQ 消息从哪进、经过哪些组件、最后由谁推给浏览器。所有结论都标注了文件路径，可直接与代码对照。

## 技术栈快照

| 层级 | 技术 | 事实来源 |
|------|------|----------|
| 后端 | Spring Boot 3.2.0 · Java 17 | `backend/pom.xml` |
| 数据库 / ORM | MySQL 8.0（库名 `qq_chat`）· Spring Data JPA，`ddl-auto=update`，`hibernate.jdbc.batch_size=50` · Flyway（dev profile 关闭） | `application.yml` |
| 消息队列 | Spring AMQP + RabbitMQ 3.12 | `config/RabbitMQConfig.java` |
| 安全 / 实时推送 | Spring Security + JJWT 0.12.3（HttpOnly Cookie）· Spring WebSocket | `config/SecurityConfig.java`、`websocket/` |
| 前端 | Vue 3.5 · Vue Router 4 · Vite 8 · ECharts 6 | `frontend/package.json` |
| 外部组件 | NapCat(OneBot 11) 6100/6099 · AstrBot 6185 · GPT-SoVITS 8000 · MinIO 可选 | `application.yml` |

## 整体拓扑

```
  QQ 客户端
     │
     ▼
  NapCat (OneBot 11)  :6100 OneBot API / :6099 WebUI
     │  HTTP 上报 POST /（webhook-token）        │  /ws 上行（握手校验 webhook-token）
     ▼                                          ▼
 ┌───────────────────────────────────────────────────────────────┐
 │ Spring Boot 3.2 后端 :8081                                     │
 │ RootWebhookController ─▶ MessageParserService ─▶ MySQL(qq_chat)│
 │        └─▶ MessageQueueService ─▶ RabbitMQ（异步，AMQP :5672）  │
 └───────┬───────────────────────────────────────────────┬───────┘
         │                                               │ /images/** /uploads/**
         ▼                                               │
 ┌──────────────────────────────┐                        │
 │ RabbitMQ 3.12  管理台 :15672  │  qqai.media    (Direct) │
 │ 4 业务队列 + 3 个 DLQ         │  qqai.voice    (Direct) │
 │                              │  qqai.ai       (Direct) │
 │                              │  qqai.broadcast(Fanout) │
 └───────┬──────────────────────┘                        │
         │ Media / Voice / AiAnalysis / Broadcast 消费者   │
         ▼                                               │
 AstrBot :6185（摘要·对话）  GPT-SoVITS :8000（TTS）          │
                                                         │
 前端 Vue SPA :5173（vite proxy → 8081 的 /api /images /ws）│
 /ws/messages ← JWT Cookie 握手 → 按群订阅消息流            ▼
```

## 请求链路：NapCat 上报 → 入库 → 异步消费 → 前端

### 1. 上报入口与令牌校验（fail closed）

| 入口 | 说明 |
|------|------|
| `POST /` | `RootWebhookController`，NapCat 默认上报地址 |
| `POST /api/napcat`、`POST /api/napcat/webhook` | `NapCatWebhookController` 转发到 `RootWebhookController.receiveMessageRoot(...)` |

令牌接受 `Authorization: Bearer <token>`、`X-Token`、`X-OneBot-Token`、`?access_token=`，与 `napcat.webhook-token` 逐一比对：

```java
if (webhookToken == null || webhookToken.isEmpty())
    return ResponseEntity.status(503).body(Map.of("error", "ServiceUnavailable")); // 未配置 → 拒绝
if (!valid)
    return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));      // 不匹配 → 拒绝
```

日志只记 `payload` 长度与耗时，不打印聊天内容；抓包需临时开 `app.debug.log-media-payload=true`。

### 2. 事件过滤与去重

只处理 `post_type = message | message_sent` 且 `message_type = group`（群聊），其余直接回 `{"status":"ok"}`。`self_id`（或 `X-Self-ID`、`napcat.self-qq`）用于判定「是否登录账号自己发的」。去重基准是 **(messageId, groupId)**——QQ 的 `message_id` 只在群内唯一，命中即返回 `{"status":"ok","duplicate":true}`；并发重复上报由数据库唯一约束（`DataIntegrityViolationException`）兜住。

### 3. 轻量解析 → 入库 → 投递任务

`MessageParserService.parseLightweight(json)` **只解析、不下载**：解析文本/CQ 码、回复引用、合并转发、小程序卡片，并把媒体段收集成 `MediaTaskPayload` 列表。随后：

```java
message.setMediaPending(hasMediaTasks);              // 前端显示占位符的开关
Message saved = messageService.saveMessage(message); // 入库 + 广播 new_message
for (MediaTaskPayload task : parsed.getMediaTasks()) {
    task.setMessageId(saved.getId());
    if ("voice".equals(task.getMediaType())) messageQueueService.sendVoiceTranscode(task);
    else                                     messageQueueService.sendMediaDownload(task);
}
groupService.saveGroupInfo(ownerQq, groupId, groupName);
```

### 4. 消费者回填与二次推送

| 消费者 | 成功动作 | 失败动作 |
|--------|----------|----------|
| `MediaDownloadConsumer` | `content = 本地路径`、`mediaPending = false`、推 `message_update` | 重试 3 次 → DLQ 写 `[图片已过期]`/`[视频已过期]`/`[媒体已过期]` |
| `VoiceTranscodeConsumer` | `content = 本地 MP3 路径`、`mediaPending = false`、推 `message_update` | 重试 3 次 → DLQ 写 `[语音转码失败]` |
| `AiAnalysisConsumer` | `aiSummary = 结果`、`processed = true`、推 `message_update` | 重试 3 次 → DLQ **只记日志**（摘要缺失不影响消息可读） |
| `BroadcastConsumer` | 按群号推给所有订阅会话 | 内部 `try/catch` 吞掉异常，不重试、不进 DLQ |

## 四大 MQ 队列与交换机

| 交换机（类型） | 队列 | 路由键 | 工厂 / prefetch | 消费者 |
|----------------|------|--------|-----------------|--------|
| `qqai.media`（Direct） | `media.download.queue` + `media.download.dlq` | `media.download` / `media.download.dlq` | `mediaContainerFactory` / 5 | `MediaDownloadConsumer`（含 DLQ 方法） |
| `qqai.voice`（Direct） | `voice.transcode.queue` + `voice.transcode.dlq` | `voice.transcode` / `voice.transcode.dlq` | `voiceContainerFactory` / 2 | `VoiceTranscodeConsumer`（含 DLQ 方法） |
| `qqai.ai`（Direct） | `ai.analysis.queue` + `ai.analysis.dlq` | `ai.analysis` / `ai.analysis.dlq` | `aiContainerFactory` / 3 | `AiAnalysisConsumer`（含 DLQ 方法） |
| `qqai.broadcast`（Fanout） | `broadcast.queue`（**无 DLQ**） | 忽略路由键 | `aiContainerFactory` / 3 | `BroadcastConsumer` |

死信通过**队列参数**声明，复用各自的 Direct 交换机：

```java
QueueBuilder.durable(MEDIA_DOWNLOAD_QUEUE)
    .withArguments(Map.of("x-dead-letter-exchange", MEDIA_EXCHANGE,
                          "x-dead-letter-routing-key", MEDIA_DOWNLOAD_DLQ_KEY))
    .build();
```

消息体统一 `Jackson2JsonMessageConverter`（`BroadcastPayload.jsonPayload` 存**预序列化 JSON 字符串**，绕开 `LocalDateTime` 反序列化问题）。启动时 `rabbitMqDeclarationRunner` 主动建连以触发 `RabbitAdmin` 声明全部 Exchange/Queue/Binding；**RabbitMQ 不可达则应用启动失败**，不静默降级。

### 重试与死信策略

```java
SimpleRetryPolicy:        maxAttempts = 3
ExponentialBackOffPolicy: initial 1000ms, multiplier 2.0, maxInterval 10000ms
recoverer = new RejectAndDontRequeueRecoverer()
factory.setDefaultRequeueRejected(false)   // 重试耗尽 → reject(requeue=false) → 按队列参数进 DLQ
```

| 关注点 | 实现 | 运维含义 |
|--------|------|----------|
| 重试 | 3 次（含首次），退避 1s→2s→4s | 单条失败消息最多占用线程约 7 秒 |
| 重试耗尽 | `reject(requeue=false)` | 进对应 `*.dlq`，不会无限循环 |
| prefetch | 媒体 5 / 语音 2 / AI 3 | 语音是 CPU 密集（ffmpeg/pysilk），刻意压低并发 |
| DLQ 消费者 | 不回抛异常 | 避免「死信再死信」导致消息彻底丢失 |
| 广播 | 无 DLQ、无重试 | 「尽力而为」语义，推送失败不阻塞业务 |

prefetch 是 `RabbitMQConfig` 里的常量，配置文件改不动；调并发需改代码后重新打包。

## WebSocket 实时推送

| 端点 | 方向 | 握手校验 | 注册位置 |
|------|------|----------|----------|
| `/ws` | NapCat → 后端（上行） | `NapCatHandshakeInterceptor` 校验 `napcat.webhook-token` | `SecurityConfig#registerWebSocketHandlers` |
| `/ws/messages` | 后端 → 前端（下行） | `JwtHandshakeInterceptor` 校验 JWT Cookie / JTI 黑名单 / 用户存在 / `tokenVersion` 一致 | 同上 |

前端连上后必须**按群订阅**（`{"action":"subscribe","groupId":"123456789"}`，退订用 `unsubscribe`），服务端强制做归属校验（`FrontendMessageWebSocketHandler#canSubscribe`）：当前用户绑定的 QQ（`user_qq_binding`，`active=true`）必须真的拥有该群（`groups.owner_qq`），否则回 `subscribe_denied`。

| 下行 `type` | 触发时机 |
|-------------|----------|
| `new_message` | `MessageService.saveMessage()` 入库后 |
| `message_update` | 媒体下载/转码完成、AI 摘要回填、消息被删除 |
| `subscribed` / `subscribe_denied` | 订阅结果回执 |

广播走「调用方 → `qqai.broadcast` Fanout → `BroadcastConsumer` → `broadcastToGroup`」，因此入库与消费者**不会被 WebSocket 写阻塞**，也为多实例部署留了 fanout 语义。

## 鉴权体系

### 令牌与 Cookie

| 项 | 事实 |
|----|------|
| Cookie | `qqai_token`：`HttpOnly=true`、`SameSite=Lax`、`path=/`、`maxAge` 与令牌同长；`secure` 由 `app.cookie.secure` 控制（默认 false 供本地 HTTP 开发，**生产 HTTPS 必须设 `APP_COOKIE_SECURE=true`**）。登录响应体**不再回传 JWT 明文**（只走这个 Cookie，避免 XSS/插件直接读走令牌） |
| JWT Claims | `sub`=username、`uid`、`tv`(tokenVersion)、`role`、`jti`、`iat`、`exp` |
| 有效期 | 默认 24h（`jwt.expiration=86400000`）；「记住我」30 天（`jwt.remember-me-expiration`） |
| 兼容 | 仍接受 `Authorization: Bearer <token>`（过滤器先读 Header，再读 Cookie） |
| 登出 | `jti` 写入 Caffeine 内存黑名单 + 下发过期 Cookie；**不**停共享组件 |

### 每次请求的校验链（`JwtAuthenticationFilter`）

1. 签名与过期（`JwtUtil.validateToken`）；
2. `jti` 是否在内存黑名单（`TokenBlacklistService`，重启即失效，由第 4 步兜底）；
3. 用户存在且 `active=true`；
4. 令牌 `tv` 与 `users.token_version` 一致——**改密码会 `tokenVersion+1`**，旧令牌立即永久失效。

任一步失败即 `401 {"error":"登录已过期，请重新登录","code":401}`。

### 权限矩阵（`SecurityConfig#filterChain`）

| 路径 | 规则 |
|------|------|
| `/api/auth/login`、`/register`、`/logout`、`/api/system/health` | 公开 |
| `/`、`/webhook`、`/api/napcat`、`/api/napcat/**` | 公开（令牌在控制器内校验） |
| `/ws`、`/ws/**` | 公开（握手鉴权在拦截器） |
| `GET /api/avatar/**`、`/uploads/avatars/**` | 公开（头像展示） |
| `POST`/`DELETE` `/api/avatar/**` | `ROLE_ADMIN` |
| `/images/**`、`/uploads/**` | 放行（`<img>/<video>` 带不了 JWT Header，靠同源 Cookie 建立认证上下文） |
| `/api/admin/**`、`/api/credits/admin/**` | `ROLE_ADMIN` |
| `/api/system/start-*`、`stop-*`、`restart-*`、`/api/system/napcat/auto-configure` | `ROLE_ADMIN` |
| `/api/system/tts`、`/tts/**`、`/convert-voice` | 登录即可（积分在服务内扣减） |
| `/api/system/napcat/qrcode`、`qrcode-path`、`qrcode-image` | `ROLE_ADMIN`（二维码＝机器人账号接管入口，普通用户不需要） |
| `/api/system/napcat/login-status`、`/api/system/component-status` | 公开（登录页状态灯，只暴露布尔量） |
| 其余 | `authenticated()` |

方法级另有 `@EnableMethodSecurity` + `@PreAuthorize("hasRole('ADMIN')")`（如 `POST /api/messages/process`、`PersonaController` 写接口），服务层再由 `SecurityHelper.requireAdmin()` 兜底。

### 公开接口白名单

`JwtAuthenticationFilter.PUBLIC_PATHS` 中的路径会**完全跳过过滤器**（连 Cookie 都不解析）：`/api/auth/{login,register,logout}`、`/api/system/health`、`/api/system/napcat/login-status`、`/api/system/component-status`、`/`、`/webhook`、`/api/napcat`、`/api/napcat/**`、`/ws`、`/ws/**`、`/uploads/avatars/**`。原因很实际：带着**过期** Cookie 打开登录页时若仍走过滤器会被判 401，导致「无法重新登录」。

> 安全收口（2026-09-21）：`/api/system/napcat/qrcode-image` 已从这个白名单**移除**。此前它被 `permitAll`，任何人无需登录即可拉取 QQ 机器人登录二维码图片（扫码即等于接管机器人账号），并可通过 `qrcode-path` 读到服务器绝对路径。现在三个二维码入口统一为 `ROLE_ADMIN`；纯状态接口（`login-status`、`component-status`）按上面的登录页原因继续公开。

## 模块分层

| 后端包（`backend/src/main/java/com/qqai/`） | 职责 | 规模 |
|-------------------------------------------|------|------|
| `controller` | REST 入口、参数校验、权限注解、组装 `ApiResponse` | 21 个 |
| `service` | 业务编排（消息、媒体、积分、订阅、组件进程、备份…） | 29 个 |
| `consumer` | 4 个 `@RabbitListener`（各含 DLQ 方法） | 4 个 |
| `repository` / `entity` | Spring Data JPA 仓储与实体 | 16 / 16 |
| `config` / `security` / `websocket` | 安全、MQ、CORS、握手、异常处理 · `JwtUtil`/`JwtAuthenticationFilter`/黑名单 · 上行下行处理器 | — |
| `common` / `util` / `dto` / `exception` / `plugin` | `SecurityHelper`、`RateLimiterService`、`ProcessManager`、CQ 码与富消息渲染 · 传输对象 · `BizException`+错误码 · Groovy 插件引擎 | — |

约定：**controller 不直接碰 repository**，跨表事务放 service；consumer 只做「取任务 → 调 service → 回填 + 广播」。

| 前端目录（`frontend/src/`） | 职责 |
|---------------------------|------|
| `views/` + `views/admin/` | 页面视图（管理后台 = 1 个壳 + 13 个懒加载子组件） |
| `components/`、`composables/` | 通用组件（含内联 SVG 图标 `Icon.vue`）与组合式逻辑（`useMessageWebSocket`、`useTheme`…） |
| `services/api.js`、`router/` | 请求封装（同源 Cookie）与路由 |
| `docs/` | 站内文档：`tree.js` 定结构，Markdown 由 `import.meta.glob` 构建期内联，零请求 |

## 关键设计取舍

### 为什么媒体/AI/广播都走 MQ 而不是同步做

- **webhook 必须快**：NapCat 上报是同步 HTTP，若在请求里下载视频或调 LLM，NapCat 侧超时会重推，造成重复消息与雪崩。
- **失败要能隔离重试**：QQ 文件服务、AstrBot 抖动是常态，重试 3 次 + 死信兜底远比「一次失败就丢」可靠。
- **资源要能限流、广播要解耦**：语音转码是 CPU 密集，prefetch=2 卡住并发；媒体下载是 I/O 密集，给到 5；广播进 Fanout 队列后，调用方不再被随时可能断开的 WebSocket 写阻塞。

### 为什么消费者方法不加 `@Transactional`

`downloadMediaToLocal` / `summarizeMessage` 内含 HTTP 下载或远程调用（可能数十秒）。放进事务会**长时间持有数据库连接**，并发一高就耗尽连接池。现在只依赖 `findById/save` 各自的内置事务，写完即释放。

### 为什么 AI 摘要改成「按需生成」

原实现是「消息入库 → `ai.analysis.queue` → 每条调一次 LLM」，群活跃时队列长期积压（实测单队列 149+ 条待分析），持续烧额度却产出低价值摘要。2026-09-16 起 `MessageService.saveMessage()` 中该投递被移除，摘要只在这两种情况产生：管理员批量 `POST /api/messages/process`（内部 `processAllUnprocessedMessages()`），或单条手动触发分析。**「新消息没有摘要」是预期行为，不是故障**；队列与消费者保留不变，手动作业照常消费。

### 幂等与一致性

| 场景 | 机制 |
|------|------|
| 重复上报同一条 QQ 消息 | `(messageId, groupId)` 预查 + 数据库唯一约束 |
| 媒体任务重复消费 / AI 任务重复消费 | 消费者先看 `mediaPending` / `processed`，已处理直接 return |
| 重复签到 | `sign_in_record` UNIQUE(userId, sign_in_date) |
| 月卡每日加成重复发放 | `relatedId = MONTHLY_CARD_DAILY-{yyyy-MM-dd}` + `monthly_bonus_record` 唯一约束 |
| 重复退款 | 退款流水 `relatedId = REFUND-{orderNo}[-R{ratio}]` 查重 |
| 改密码后旧令牌 | `tokenVersion+1`（持久化，重启不失效） |

### 静态媒体的鉴权取舍

`/images/**`、`/uploads/**` 在 `SecurityConfig` 中放行：`<img>` / `<video>` 无法携带 JWT Header，只能靠浏览器同源自动带 Cookie。为此过滤器特意把这些路径排除在白名单之外——带 Cookie 时仍会建立认证上下文，为将来收紧成「登录后可见」留好入口。头像目录 `/uploads/avatars/**` 则完全公开（群里展示他人头像无法逐一鉴权）。
