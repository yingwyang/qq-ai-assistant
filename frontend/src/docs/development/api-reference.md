---
title: API 接口文档
description: 按模块列出主要接口、统一响应结构与错误码
updated: 2026-09-16
---

这一页给二次开发和联调用：接口清单、鉴权方式、统一响应结构、错误码。路径与参数均取自 `backend/src/main/java/com/qqai/controller/` 下的实际注解。

## 通用约定

### 认证方式

| 项 | 说明 |
|----|------|
| 主方式 | 登录成功后下发 **HttpOnly Cookie `qqai_token`**（`SameSite=Lax`、`path=/`、`maxAge` 与令牌同长），同源请求由浏览器自动携带 |
| 兼容方式 | `Authorization: Bearer <token>`（过滤器优先读 Header，再读 Cookie），便于 curl / 第三方客户端调试 |
| 有效期 | 默认 24h；登录体带 `"rememberMe": true` 时为 30 天（`JWT_REMEMBER_ME_EXPIRATION`） |
| 失效条件 | 令牌过期 / `jti` 在黑名单（登出）/ 用户被禁用 / `tokenVersion` 不一致（改密码后自增） |

```bash
# 登录并用 cookie 文件保存登录态（后续接口 -b cookie.txt）
curl.exe -s -c cookie.txt -X POST "http://localhost:8081/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<ADMIN_PASSWORD>"}'
```

### 统一响应结构

绝大多数接口返回 `ApiResponse<T>`（`dto/common/ApiResponse.java`，`@JsonInclude(NON_NULL)`，空字段不会出现）：

```json
{
  "code": 200,
  "message": "ok",
  "data": { "balance": 1200 },
  "status": "ok"
}
```

| 字段 | 含义 |
|------|------|
| `code` | 业务码，成功恒为 `200`，失败为对应 HTTP 语义码（400/401/403/404/429/500…） |
| `message` | 人类可读信息，成功为 `ok` |
| `data` | 业务数据（对象 / 数组 / `null`） |
| `status` | `ok` \| `error`，前端据此判断，比 `code` 更直观 |
| `errorCode` | 机器可读错误码（如 `INSUFFICIENT_CREDITS`、`ORDER_001`），仅错误时出现 |
| `details` | 附加明细（如余额不足时的上下文） |
| `traceId` | 链路追踪 ID，报错时把它和后端日志一起看最快 |

### 错误码

HTTP 层（由 `SecurityConfig` 的 entry point / `GlobalExceptionHandler` 产生）：

| HTTP | 典型 `errorCode` | 触发场景 | 处理建议 |
|------|------------------|----------|----------|
| 400 | `VALIDATION_ERROR`、`BAD_ARGUMENT` | 参数校验失败、非法参数 | 修正请求体 |
| 401 | `AUTH_401`、`INVALID_TOKEN`、`UNAUTHORIZED` | 未登录 / 令牌过期 / 令牌非法 / 用户被禁用 | 重新登录 |
| 402 | `INSUFFICIENT_CREDITS` | 积分不足（当前见 `POST /api/groups/{groupId}/recognize-type`） | 签到或购买积分 |
| 403 | `FORBIDDEN`、`ADMIN_REQUIRED`、`AUTH_403_ADMIN_REQUIRED` | 非管理员访问管理接口 | 用 ADMIN 账号 |
| 404 | `NOT_FOUND`、`ORDER_001` | 资源/订单不存在 | 核对 ID |
| 405 | `METHOD_NOT_ALLOWED` | 方法用错（如用 GET 调 POST） | 查本文档 |
| 409 | —（`{"code":409}`） | `POST /api/messages/process` 已有任务在执行 | 等任务结束再调 |
| 413 | `MEDIA_413` | 上传超限（默认 100MB） | 压缩或调大 `spring.servlet.multipart.*` |
| 429 | —（`{"code":429}`） | 登录（IP+用户名 5 次/分）、注册（IP 5 次/分）被限流 | 1 分钟后再试 |
| 500 | `SYSTEM_500`、`DATABASE_ERROR` | 未捕获异常 / 数据库异常 | 拿 `traceId` 查日志 |
| 503 | —（`{"error":"ServiceUnavailable"}`） | `napcat.webhook-token` 未配置，Webhook 拒绝服务 | 配置令牌并重启 |

> 401/403 由 Spring Security 直接写出，结构是 `{"error":"...","code":401,"path":"..."}`（不带 `data`/`status`），前端拦截器需同时兼容这两种形态。

业务错误码常量见 `exception/ErrorCode.java`：`AUTH_401`、`VALIDATION_ERROR`、`INSUFFICIENT_CREDITS`、`ALREADY_SIGNED_IN`、`ORDER_001`~`ORDER_010`、`FILE_TOO_LARGE`、`SYSTEM_500`。

### 公开接口白名单

以下路径**无需登录**（`SecurityConfig` 放行 + `JwtAuthenticationFilter.PUBLIC_PATHS` 完全跳过过滤器）：

| 路径 | 说明 |
|------|------|
| `POST /api/auth/login`、`POST /api/auth/register`、`POST /api/auth/logout` | 认证入口（登出即使令牌已过期也应幂等成功） |
| `GET /api/system/health` | 健康检查 |
| `GET /api/system/component-status`、`GET /api/system/napcat/login-status`、`GET /api/system/napcat/qrcode-image` | 登录页状态灯与扫码 |
| `POST /`、`POST /webhook`、`POST /api/napcat`、`POST /api/napcat/webhook` | NapCat 上报（令牌在控制器内校验） |
| `/ws`、`/ws/**` | WebSocket 握手（鉴权在拦截器） |
| `GET /api/avatar/**`、`/uploads/avatars/**` | 头像展示 |
| `/images/**`、`/uploads/**` | 聊天媒体（`<img>/<video>` 无法带 JWT Header，靠同源 Cookie） |

## 认证 /api/auth/*

| 方法 | 路径 | 权限 | 关键参数 | 返回关键字段 |
|------|------|------|----------|--------------|
| POST | `/api/auth/login` | 公开 | `username`、`password`、`rememberMe`(可选) | `id`、`token`、`username`、`nickname`、`role`、`avatar`、`email`、`createdAt`、`lastLoginTime` |
| POST | `/api/auth/register` | 公开（受 `APP_REGISTRATION_ENABLED` 与限流控制） | `username`、`password`、`nickname` | 无（成功即 200）；密码需 8–64 位且含大小写+数字 |
| GET | `/api/auth/me` | 登录 | — | 同上用户字段（自动登录场景会顺带触发组件幂等启动） |
| PUT | `/api/auth/profile` | 登录 | `nickname`、`email` | 更新后的用户字段 |
| POST | `/api/auth/change-password` | 登录 | `oldPassword`、`newPassword` | 无；成功后 `tokenVersion+1`，**其它端登录态全部失效** |
| POST | `/api/auth/logout` | 公开 | — | 无；注销当前 `jti` 并清 Cookie（不会停共享组件） |

## 消息 /api/messages/*

| 方法 | 路径 | 权限 | 关键参数 | 说明 |
|------|------|------|----------|------|
| POST | `/api/messages` | 登录 | `Message` 实体 JSON | 手工入库（一般由 Webhook 调用） |
| GET | `/api/messages/group/{groupId}` | 登录 | `selfQq`(可选) | 群消息全量（前端首屏） |
| GET | `/api/messages/group/{groupId}/paged` | 登录 | `page=0`、`size=50`、`selfQq` | 分页列表 |
| GET | `/api/messages/group/{groupId}/since` | 登录 | `afterId`、`selfQq` | 增量拉取（WebSocket 兜底轮询） |
| GET | `/api/messages/group/{groupId}/members` | 登录 | — | 群成员昵称映射 |
| GET | `/api/messages/recent-groups` | 登录 | — | 最近会话列表（含未读数） |
| POST | `/api/messages/read/{groupId}` / `read-all` | 登录 | — | 标记已读 |
| POST | `/api/messages/process` | **ADMIN** | — | 批量补 AI 摘要（把 `processed=false` 全部投递 `ai.analysis.queue`）；并发调用返回 409 |
| POST | `/api/messages/upload` | 登录 | `file`(multipart)、`fileType` | 上传文件，返回 `FileRecord` |
| POST | `/api/messages/send-with-file` | 登录 | `groupId`、`userQq`、`userNickname`、`content`、文件 | 发消息带附件 |
| GET | `/api/messages/file/{fileId}` | 登录 | — | 文件元信息 |
| GET | `/api/messages/download` | 登录 | `path` | 下载文件（校验归属） |
| DELETE | `/api/messages/file/{fileId}` | 登录 | — | 删除文件记录 |
| DELETE | `/api/messages/{messageId}` | 登录 | — | 软删除单条消息 |
| POST | `/api/messages/delete-batch` | 登录 | `ids`、`deleteMedia` | 批量删除 |
| POST | `/api/messages/group/{groupId}/delete-by-types` | 登录 | `types` | 按消息类型清理 |
| POST | `/api/messages/group/{groupId}/delete-conversation` | 登录 | `ownerQq` | **硬删除**整个会话（消息+媒体+群+已读状态），不可恢复 |
| POST | `/api/messages/purge-media` | **ADMIN** | `IMAGE/VIDEO/AUDIO` 开关 | 按类型清理媒体文件 |
| GET | `/api/messages/media-files` | 登录 | `type`、`page`、`size` | 媒体文件列表 |
| POST | `/api/messages/delete-media-files` | **ADMIN** | 文件列表 | 删除指定媒体 |
| POST | `/api/messages/archive` | **ADMIN** | `daysBefore=90` | 手动归档历史消息 |

## 群 /api/groups/*

> ⚠️ 本组接口返回**裸 Map**（`{"status":"ok"|"error", ...}`），不走 `ApiResponse` 包装。

| 方法 | 路径 | 权限 | 关键参数 | 返回关键字段 |
|------|------|------|----------|--------------|
| GET | `/api/groups/{groupId}/type` | 登录 | — | `groupType`、`groupTypeLabel`、`groupTypeIcon`、`recommendedAnalysisTypes` |
| PUT | `/api/groups/{groupId}/type` | 登录 | `groupType`（`GAME/STUDY/WORK/HOBBY/LIFE/SOCIAL/OTHER`） | 同上 |
| POST | `/api/groups/{groupId}/recognize-type` | 登录（非管理员扣积分） | `forceRefresh=false` | `recognizedType`、`recognizedTypeLabel`、`confidence`、`reason`、`cost`；积分不足返回 **402** `INSUFFICIENT_CREDITS`，识别失败返回 `errorCode=RECOGNITION_FAILED` 且**不扣费** |

## 积分 /api/credits/*

| 方法 | 路径 | 权限 | 关键参数 | 返回关键字段 |
|------|------|------|----------|--------------|
| GET | `/api/credits/balance` | 登录 | — | `balance`、`totalEarned`、`totalSpent`、`todaySignInDone`、`streakDays`、`signInBasePoints`、`monthlyCardBonus`、`monthlyCardBonusTier`、`monthlyCardTier`、`signInPoints`、`subscriptionTier`、`subscriptionExpiresAt` |
| POST | `/api/credits/sign-in` | 登录 | — | `points`(合计)、`basePoints`、`monthlyCardBonus`、`streakDays`、`newBalance`；重复签到 400 `ALREADY_SIGNED_IN` |
| GET | `/api/credits/sign-in/status` | 登录 | `range=7` | 签到日历与连续天数 |
| GET | `/api/credits/transactions` | 登录 | `type`、`direction`、`start`、`end`、`relatedId`、`page=0`、`size=20`(上限 200) | `content`、`totalElements`、`totalPages`、`incomeTotal`、`spendTotal`、`net` |
| GET | `/api/credits/trend` | 登录 | `days=7`(上限 365) | 每日收支趋势数组 |
| GET | `/api/credits/rewards` | 登录 | — | 可得积分清单（签到/新人/月卡等） |

**月卡加成口径（代码事实）**：小月卡每日 `+100`、大月卡每日 `+300`，**双持叠加 `+400`**，随「每日签到」一次性发放（`CreditService.signInToday` → `grantMonthlyCardDailyBonus`），幂等键 `MONTHLY_CARD_DAILY-{yyyy-MM-dd}`。

## 订阅 /api/subscriptions/*

| 方法 | 路径 | 权限 | 关键参数 | 返回关键字段 |
|------|------|------|----------|--------------|
| GET | `/api/subscriptions/plans` | 登录 | — | `plans`、`directPlans`、`monthlyCards`、`groups`；直购档 `LITE/PRO/PROPLUS/ULTRA/MEGA`，月卡 `SMALL_MONTH_CARD`(30 元/30 天)、`LARGE_MONTH_CARD`(68 元/30 天) |
| POST | `/api/subscriptions/purchase` | 登录 | `planCode`、`paymentMethod`(默认 `MANUAL`) | `orderNo`、`status`(PENDING)、`price`、`creditAmount`、`message`；同档 30 天内重复下单命中幂等，直接返回已有订单 |
| GET | `/api/subscriptions/orders` | 登录 | `page`、`size`、`status` | 我的订单分页 |
| GET | `/api/subscriptions/orders/{orderNo}` | 登录（仅本人） | — | 订单详情；非本人 403 |
| POST | `/api/subscriptions/orders/{orderNo}/cancel` | 登录 | `reason` | 取消订单 |
| POST | `/api/subscriptions/orders/{orderNo}/refund-request` | 登录 | `reason` | 申请退款 → `PENDING_REFUND` |
| POST | `/api/subscriptions/orders/{orderNo}/dispute` | 登录 | `reason` | 申请纠纷 → `DISPUTED` |

订单状态机（`entity/enums/OrderStatus.java`）：`PENDING → PAID → (PENDING_REFUND | DISPUTED) → REFUNDED`，另有 `CANCELLED`、`EXPIRED`。订阅到期由 `SubscriptionExpireScheduler`（cron `0 0 2 * * ?`）扫成 `EXPIRED` 并把 `tier` 降回 `FREE`。

## 系统 /api/system/*

| 方法 | 路径 | 权限 | 关键参数 | 说明 |
|------|------|------|----------|------|
| POST | `/api/system/tts` | 登录（扣积分） | `text`、`character`(可选) | 生成语音，返回音频 URL |
| GET | `/api/system/tts/characters` | 登录 | — | 音色列表（配置里的 `gpt-sovits.characters`） |
| POST | `/api/system/tts/switch-character` | 登录 | `character` | 切换当前音色 |
| POST | `/api/system/convert-voice` | 登录 | `path` | 按需把 `.amr/.silk` 转 MP3，返回 `audioUrl` |
| GET | `/api/system/health` | 公开 | — | `status`、`timestamp`、`napcat` 可用性 |
| GET | `/api/system/component-status` | 公开 | — | `astrbot`/`napcat`/`gptsovits` 的 `running`、`status`（端口探测：6185/6100/8000） |
| GET | `/api/system/napcat/login-status` | 公开 | — | `loggedIn`；⚠️ 已知在已登录时可能返回 `false`，真实状态请用 6100 的 `get_login_info` |
| GET | `/api/system/napcat/qrcode` / `qrcode-path` / `qrcode-image` | 公开 | — | 扫码登录 |
| POST | `/api/system/start-all` / `stop-all` | **ADMIN** | — | 一键启停，顺序为 AstrBot → NapCat → GPT-SoVITS |
| POST | `/api/system/start-astrbot` / `stop-astrbot` | **ADMIN** | — | 单组件控制（另有 `start-napcat`、`stop-napcat`、`start-gptsovits`、`stop-gptsovits`） |
| POST | `/api/system/napcat/auto-configure` | **ADMIN** | — | 检测新 QQ 并自动写 Webhook 配置 |
| GET | `/api/system/disk-usage` | 登录 | — | `uploads` 目录占用 |

## 管理 /api/admin/*、/api/credits/admin/*

| 方法 | 路径 | 权限 | 关键参数 |
|------|------|------|----------|
| GET | `/api/admin/users` | ADMIN | `page`、`size`、`keyword` |
| PUT | `/api/admin/users/{id}/role` | ADMIN | `role`（`ADMIN`/`USER`） |
| PUT | `/api/admin/users/{id}/active` | ADMIN | `active` |
| POST | `/api/admin/users/{id}/reset-password` | ADMIN | `newPassword`（同样要求 8–64 位含大小写+数字） |
| DELETE | `/api/admin/users/{id}` | ADMIN | — |
| GET | `/api/admin/logs`、`/api/admin/audit-logs` | ADMIN | 日志与审计分页 |
| GET/PUT | `/api/admin/config` | ADMIN | 系统配置读写 |
| POST | `/api/admin/backup`、`GET /api/admin/backup/list`、`GET /api/admin/backup/{fileName}/download` | ADMIN | 数据库备份（落 `data/backups/`） |
| POST | `/api/admin/archive` | ADMIN | `days=90` 归档历史消息 |
| GET/PUT | `/api/credits/admin/rule` | ADMIN | 积分规则（价格、签到分、月卡加成、折扣阶梯等） |
| GET | `/api/credits/admin/user-credits` | ADMIN | `keyword`、`page`、`size` |
| POST | `/api/credits/admin/adjust` | ADMIN | `userId`、`amount`、`reason`（正数发放、负数扣除，用于退费/补偿） |
| GET | `/api/credits/admin/transactions` | ADMIN | `userId`、`type`、`direction`、`start`、`end` |
| GET | `/api/credits/admin/orders` / `orders/export` / `orders/pending-count` | ADMIN | `orderNo`、`keyword`、`status`、`start`、`end`、`minPrice`、`maxPrice` |
| POST | `/api/credits/admin/orders/manual-create` | ADMIN | `userId`、`planCode`、`priceCents` |
| POST | `/api/credits/admin/orders/{orderNo}/approve-payment` | ADMIN | 确认到账（`markPaid`，发放积分与权益） |
| POST | `/api/credits/admin/orders/{orderNo}/cancel` | ADMIN | `reason` |
| POST | `/api/credits/admin/orders/{orderNo}/refund` | ADMIN | `reason`、`ratio`（0–1，按比例退款并回退积分/tier） |
| POST | `/api/credits/admin/orders/{orderNo}/approve-refund` / `reject-refund` | ADMIN | `reason` |
| POST | `/api/credits/admin/orders/{orderNo}/resolve-dispute` | ADMIN | `agree`、`reason` |

> 退款幂等键：`REFUND-{orderNo}`（全额）或 `REFUND-{orderNo}-R{ratio}`（按比例），重复退款抛 `ALREADY_REFUNDED`。

## 用户中心与 AI 对话

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET/PUT | `/api/user/profile` | 登录 | 个人资料 |
| GET/POST/DELETE | `/api/user/qq-bindings`、`/qq-bindings/send-code`、`/{bindingId}/default` | 登录 | QQ 绑定（决定能看到哪些群） |
| POST | `/api/user/avatar` | 登录 | 上传头像（`file`） |
| GET/POST | `/api/user/settings` | 登录 | 用户设置（`userId` 只信任 JWT，不信任请求体） |
| GET | `/api/user/dashboard/stats` 等 5 个 | 登录 | 个人维度统计 |
| GET | `/api/dashboard/*` | 登录 | `stats`、`message-trend`、`group-ranking`、`qq-ranking`、`message-type-distribution`、`hourly-distribution`、`ai-trend` |
| POST | `/api/astrbot/analyze`、`/analyze-selected` | 登录（扣积分） | AI 分析/摘要（`groupId`、`messageCount`、`type`） |
| GET/POST | `/api/astrbot/conversations*` | 登录 | 对话会话 CRUD、标题、归档、统计 |
| GET/POST | `/api/astrbot/models`、`/set-model` | 登录 | 模型列表与切换 |
| POST | `/api/astrbot/callback`、`/send` | 登录 | AstrBot 回调入口与消息发送 |
| GET | `/api/persona/*` | 读：登录；写：ADMIN | 人格模板列表/详情/增删改/默认 |

## 文档 /docs/**（无后端接口）

站内帮助文档**没有** `/api/docs/*` 端点：Markdown 原文放在 `frontend/src/docs/`，由 `composables/useDocs.js` 在**构建期**用 `import.meta.glob('../docs/**/*.md', { eager: true })` 全部内联，阅读与搜索都是纯前端行为、零网络请求。页面元数据（标题/描述/顺序）来自 `frontend/src/docs/tree.js`，页面顶部的展示信息优先取各自文件里的 YAML front-matter。新增页面：放 md 文件 → 在 `tree.js` 对应分类里加一行。

## Webhook（根路径）

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/` | `napcat.webhook-token` | NapCat 默认上报地址（`RootWebhookController`） |
| POST | `/webhook`、`/api/napcat`、`/api/napcat/webhook` | 同上 | 等价入口，转发到同一逻辑 |

请求头接受 `Authorization: Bearer <token>`、`X-Token`、`X-OneBot-Token`，或查询参数 `?access_token=`。成功返回 `{"status":"ok","id":<数据库ID>}`；重复消息返回 `{"status":"ok","duplicate":true}`；令牌错误 401；令牌未配置 503。

## 权限速查

| 角色 | 能做什么 |
|------|----------|
| 未登录 | 登录/注册/登出、健康检查、组件状态、NapCat 扫码、Webhook、静态媒体与头像 |
| `USER` | 自己绑定的 QQ 所拥有的群的读写、消息与媒体、签到/积分、订阅与订单、AI 对话与 TTS（消耗积分）、用户中心 |
| `ADMIN` | 以上全部 + 用户管理、订单审批/退款/纠纷、积分调账与规则、组件启停、配置、日志、备份归档、批量补摘要、媒体清理 |

细粒度规则（含方法级 `@PreAuthorize`）见[架构说明](architecture)的「权限矩阵」一节。
