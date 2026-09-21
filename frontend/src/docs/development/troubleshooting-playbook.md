---
title: 排障手册索引
description: 通用四步定位法、一句话命令与近期修复清单
updated: 2026-09-16
---

这一页是排障入口：先按「通用四步」把问题压到**某一层**，再进对应的专项手册细查。所有命令都可直接复制执行（把 `<...>` 换成真实值，敏感值不要写进文档或工单）。

## 通用四步定位法

顺序固定：**看数据库 → 看后端日志 → 看队列积水 → 直连外部组件验证**。前一步没有结论不要跳步——跳过中间层最常见的后果是把「下游没消费」误判成「上游没上报」。

### ① 看数据库（数据到底进没进）

```sql
USE qq_chat;

-- 媒体链路：最近 10 条媒体消息
SELECT id, message_type, LEFT(content, 90) AS content, media_pending, send_time
FROM messages
WHERE message_type IN ('VIDEO','IMAGE','VOICE','AUDIO')
ORDER BY id DESC LIMIT 10;

-- AI 摘要链路：有多少条有摘要
SELECT COUNT(*) total,
       SUM(ai_summary IS NOT NULL AND ai_summary <> '') AS has_summary,
       SUM(processed = 1) AS processed
FROM messages;
```

| 看到什么 | 含义 |
|----------|------|
| `content` 是 `/images/.../xxx.mp4` | ✅ 落盘成功 |
| `content` 是 `/images/video/.../xxx.png` | ✅ 降级成功（视频原片不可用，落盘的是 QQ 缩略图） |
| `content` 是 `[视频已过期]`/`[图片已过期]`/`[媒体已过期]`/`[语音转码失败]` | ❌ 重试 3 次后进死信，由 DLQ 消费者写入占位符 |
| `content` 还是原始 CQ 码且 `media_pending=1` | ⏳ 压根没被消费（消费者没跑 / 队列积压 / 下载卡死） |
| 群里**完全查不到新消息** | 上报层问题（NapCat 未登录 / Webhook 没配 / token 不一致） |

### ② 看后端日志（谁在报错、报什么）

主日志 `backend/logs/application.log`（从 `backend/` 目录启动时）；用 jar 重定向时也可能在 `backend/app-run.log`、`backend/backend-run.log`。

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend

# 媒体链路
Select-String -Path logs\application.log -Pattern '媒体下载完成|CQ 直连获取失败|get_file|缩略图|越界|SSRF|无法获取媒体|【DLQ】' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }

# AI 摘要 / 群类型链路
Select-String -Path logs\application.log -Pattern 'AI分析完成|AstrBot 返回错误|AstrBot 响应为空|AI分析失败|【DLQ】AI分析|群类型识别|HttpHostConnectException' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }

# 鉴权与接口错误（401/403 会打 warn/error）
Select-String -Path logs\application.log -Pattern '401 UNAUTHORIZED|403 FORBIDDEN' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }
```

### ③ 看队列积水（卡在哪一级）

管理台 <http://127.0.0.1:15672>（默认 `guest` / `guest`），或直接用 API：

```powershell
curl.exe -s -u guest:guest "http://127.0.0.1:15672/api/queues/%2F" |
  ConvertFrom-Json | Select-Object name,messages,messages_ready,consumers | Format-Table -AutoSize
```

| 现象 | 含义 |
|------|------|
| `Ready` 持续增长、`Consumers = 0` | 后端没在消费（没启动 / MQ 连接断 / 消费者未注册） |
| `Unacked` 顶住 prefetch 上限且不动 | 消费卡死（下载或转码长时间无响应） |
| 某个 `*.dlq` 有积压 | 该链路反复失败，去 ② 找具体原因（DLQ 消息里带 `messageId`/`fileId`） |

### ④ 直连外部组件验证（绕过后端）

```powershell
# NapCat 是否真的登录（推荐用它判断登录态，不要用 /api/system/napcat/login-status）
curl.exe -s -X POST "http://127.0.0.1:6100/get_login_info" `
  -H "Authorization: Bearer <NAPCAT_TOKEN>" -H "Content-Type: application/json" -d '{}'
# 返回 retcode:0 且带 user_id 即为已登录

# AstrBot 是否可用（必须带 username，否则报 Missing key: username）
curl.exe -s -N -X POST "http://localhost:6185/api/v1/chat" `
  -H "X-API-Key: <ASTRBOT_TOKEN>" -H "Content-Type: application/json" `
  -d '{"message":"请回复两个字:成功","username":"summarizer","enable_streaming":false}'
# 正常是 SSE 文本流，正文在 type=plain 的 data 字段

# 端口存活（6100 NapCat / 6185 AstrBot / 8000 GPT-SoVITS / 8081 后端）
Test-NetConnection -ComputerName localhost -Port 6185 -InformationLevel Quiet
```

AstrBot 正常响应形态：

```
data: {"type": "session_id", "data": null, "session_id": "..."}

data: {"type": "plain", "data": "成功", "streaming": false, ...}
```

## 一句话定位命令速查

| 要看什么 | 一句话命令 |
|----------|-----------|
| 数据库（消息/摘要落库） | `mysql -u root -p -e "SELECT COUNT(*) FROM qq_chat.messages;"` |
| 后端日志（最近报错） | `Select-String -Path backend\logs\application.log -Pattern 'ERROR|【DLQ】|401 UNAUTHORIZED|403 FORBIDDEN' \| Select-Object -Last 30` |
| 队列积水 | `curl.exe -s -u guest:guest "http://127.0.0.1:15672/api/queues/%2F" \| ConvertFrom-Json \| Select-Object name,messages,messages_ready,consumers` |
| 单队列深度 | `curl.exe -s -u guest:guest "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue" \| ConvertFrom-Json \| Select-Object messages,messages_ready,messages_unacknowledged` |
| 清空某个队列（止损） | `curl.exe -s -u guest:guest -X DELETE "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue/contents"` |
| 组件端口 | `Test-NetConnection localhost -Port 6100 -InformationLevel Quiet`（换 6185 / 8000 / 8081） |
| 组件运行状态 | `curl.exe -s "http://localhost:8081/api/system/component-status"` |
| NapCat 登录状态 | `curl.exe -s -X POST "http://127.0.0.1:6100/get_login_info" -H "Authorization: Bearer <NAPCAT_TOKEN>" -d '{}'` |
| 后端健康检查 | `curl.exe -s "http://localhost:8081/api/system/health"`（只探 NapCat 端口） |
| 依赖健康（DB/MQ） | `curl.exe -s -b cookie.txt "http://localhost:8081/actuator/health"`（cookie 需 ADMIN；返回 `components.db` / `components.rabbit`） |
| 磁盘占用 | `curl.exe -s -b cookie.txt "http://localhost:8081/api/system/disk-usage"` |
| 数据库连接数 | `mysql -u root -p -e "SHOW STATUS LIKE 'Threads_connected'; SHOW PROCESSLIST;"` |
| 重启后端 | `Get-NetTCPConnection -LocalPort 8081 -State Listen \| Select -Expand OwningProcess -Unique \| % { Stop-Process -Id $_ -Force }` |
| 媒体抓包（临时） | 把 `app.debug.log-media-payload` 改 `true` 并重启（会打印含聊天内容的完整载荷，排查完立刻关） |

## 症状分流表

| 症状 | 先看这一层 | 去哪一页 |
|------|-----------|----------|
| 群里消息完全不入库 | NapCat 登录状态 + Webhook token 一致性 | [系统配置](system-config) 的「配置 NapCat Webhook」 |
| 图片/视频/语音显示 `[xx已过期]` 或只有预览图 | `messages.content` + 媒体日志 + `media.download.dlq` | `doc/MEDIA_TROUBLESHOOTING.md` |
| 手动触发后仍没有 AI 摘要 | `messages.ai_summary`/`processed` + `ai.analysis.dlq` + AstrBot 直连 | `doc/AI_SUMMARY_TROUBLESHOOTING.md` |
| 群类型「AI 识别」显示其他群 / 置信度 0% | 群类型日志 `群类型识别完成` + AstrBot 直连 | 本页「最近修复过的问题」第 3 条 |
| 页面接口 401/403 | 后端日志的 401/403 + 浏览器 Cookie `qqai_token` | [架构说明](architecture) 的「权限矩阵」 |
| 接口 402 / 429 | 积分余额；登录/注册频率 | [API 接口文档](api-reference) 的「错误码」 |
| 队列堆积、处理变慢 | `Ready` / `Unacked` / `Consumers` | [消息队列指南](rabbitmq-guide) |
| 后端起不来 | 启动日志的密钥校验 / RabbitMQ 连接 | [部署与运维](deployment) |

## 仓库里已有的两份专项手册

这两份手册是逐次故障复盘沉淀下来的，**遇到媒体或 AI 摘要问题优先读它们**（本页只做索引与通用方法）：

| 手册 | 覆盖内容 | 结构 |
|------|----------|------|
| `doc/MEDIA_TROUBLESHOOTING.md` | 图片/语音/视频入库全链路：三级取源逻辑、安全约束（越界保护 / SSRF 白名单 / 体积上限）、2026-09-15「视频无法入库」完整复盘、命令与配置速查、症状→处置表、防复发要点 | §0 五分钟定位 ①数据库 ②日志 ③队列 ④磁盘与 NapCat → §1 链路全貌 → §3 三级取源 → §4 故障复盘 → §5 速查 → §6 症状表 → §7 防复发 |
| `doc/AI_SUMMARY_TROUBLESHOOTING.md` | AI 摘要链路：SSE 解析、`username` 必填、错误响应不得写库、2026-09-15「摘要 100% 失败」复盘、批量补历史摘要（含止损）、群类型识别同源故障 | §0 五分钟定位 ①数据库 ②日志 ③队列 ④直连 AstrBot → §1 链路全貌 → §2 复盘 → §3 症状表 → §4 批量补摘要 → §5 防复发 → §6 群类型识别 |

媒体手册里的三级取源是本系统最值得记住的设计：**① CQ 码直连 → ② NapCat `get_file` 兜底 → ③ 视频缩略图兜底**；AI 手册里最值得记住的是**同一外部接口只保留一份解析实现**（`AstrBotService.chat` + `extractChatReply`）。

## 最近修复过的问题

| # | 问题（现象） | 根因 | 修复与验证 |
|---|--------------|------|------------|
| 1 | 视频全部落成 `[视频已过期]`，磁盘没有文件（2026-09-15） | 三层叠加：QQ(NT) 默认只保留 68KB 缩略图、不落原片；NapCat 视频段 `url` 只给本地绝对路径；来源越界保护把这类合法路径一并拒掉，且没有降级兜底 | 增加可信来源标记 `trustedSource`（消息段已佐证 → 允许复制 uploads 之外的 QQ 缓存）+ 三级取源 + **视频缩略图兜底**（由 `...\Video\<yyyy-MM>\Ori\<uuid>.mp4` 推导 `...\Thumb\<uuid>_0.png` 落盘，前端渲染成预览图并提示「原视频未缓存，仅预览」）+ 体积上限可配 + 失败日志明确到具体原因。验证：`content` 变为 `/images/video/.../xxx.mp4`（0.74MB 落盘） |
| 2 | 1251 条消息 0 条摘要、118 次 `【DLQ】AI分析彻底失败`（2026-09-15） | 四个独立缺陷叠加：按整段 JSON 读 `response` 字段（实际是 SSE 流）→ 永远 null；请求缺必填 `username`；**把 `{"status":"error",...}` 当摘要写库**（污染 365 条）；AstrBot 未运行被前三个问题掩盖 | 抽出统一入口 `AstrBotService.chat(message, apiKey, tag)`：补 `username`、显式 `enable_streaming=false`、逐行解析 SSE 且兼容整段 JSON、**显式拒绝 `status=error`/`retcode!=0`**、空响应打印状态码与 body 片段；模型名改为配置项 `astrbot.summary-model`（不再硬编码）；污染的 365 条重置为 `NULL/processed=0`。验证：`messageId=84972` → `processed=1` + 结构化 JSON，队列与 DLQ 归零 |
| 3 | 群类型「AI 识别」恒为「其他群 / 置信度 0% / LLM 返回为空」，**且照样扣 1 积分**（2026-09-16） | `GroupTypeRecognitionService` 里存在 AstrBot 调用的**第二份实现**，犯了与第 2 条完全相同的两个错（缺 `username`、未解析 SSE） | 删除自写调用，改调 `AstrBotService.chat(...)`；`RecognitionResult` 增加 `success` 标记，**失败结果不再写 24h 缓存**；`GroupController` 改为「识别成功后才扣费」（余额不足返回 **402** `INSUFFICIENT_CREDITS`，失败返回 `RECOGNITION_FAILED` 且不扣费）。验证：群 `674405515` → `{"recognizedType":"HOBBY","confidence":0.82}`，失败路径无新增积分流水 |
| 4 | 队列长期积压、模型额度被持续消耗（2026-09-16） | 原实现「每条消息入库 → 投 `ai.analysis.queue` → 调一次 LLM」，群活跃时实测单队列积压 149+ 条 | **移除入库自动摘要**（`MessageService.saveMessage()` 中不再投递），改为按需：管理员 `POST /api/messages/process` 批量补，或单条手动分析；队列与消费者保留不变。⚠️ 由此「新消息没有摘要」「`processed=0` 持续增长」都是**预期行为**，不是故障 |
| 5 | 月卡「每日额外积分」看不见、疑似未发放（2026-09-16） | 原来在登录/自动登录时静默发放，界面完全无感知，且可能与签到重复 | 改为**随「每日签到」一次性发放**：小月卡 `+100`、大月卡 `+300`、**双持叠加 `+400`**（`MONTHLY_CARD_DAILY-{date}` + `monthly_bonus_record` 唯一约束保幂等）；签到卡与订阅页显示双持状态；文案由「每日登录 +N」改为「每日签到额外 +N」 |
| 6 | 管理后台订单列表「筛选条件残留」 | 筛选条件集中在 `useAdminOrders.js` 的一个 `reactive` 对象里（含 `statusSelected` 多选数组），跨页签/再次进入时若未重置，仍按上次条件查询 → 列表为空但界面看不出原因 | 统一由 `resetOrderFilters()` 清空全部条件（订单号/关键字/状态多选/起止日期/金额区间）并回到第 0 页重新加载。**处置**：列表异常为空时先点「重置」再查；对接口直查用 `GET /api/credits/admin/orders` 不带任何筛选参数 |
| 7 | 图标缺失、暗色主题下样式错乱（2026-09-16 登录页改版同期） | `Icon.vue` 是**按 `name` 匹配 `<path>` 的内联 SVG**，没有兜底分支：`name` 拼错或组件没传就渲染成空白（表现为「图标不见了」）；颜色靠 `fill="currentColor"` 继承主题。管理后台样式使用 CSS 变量（`var(--card-bg, #fff)`、`var(--text-primary, #333)`），未走变量的硬编码颜色在暗色下就会突兀 | 图标统一走 `Icon.vue` 并传对 `name`；管理后台共享样式统一使用主题变量；`useTheme.js` 支持 light/dark（`localStorage` 记忆 + 未设置时跟随 `prefers-color-scheme`），登录页完成暗色适配 |

### 已知未修项（别当新 bug 查）

| 项 | 说明 | 替代方案 |
|----|------|----------|
| `GET /api/system/napcat/login-status` 在 QQ 已登录时可能返回 `loggedIn:false` | 它用 `?token=` 请求 OneBot(6100) 的 WebUI 端点被拒，随后又因 `cache/qrcode.png` 是 5 分钟内的新文件而误判「等待登录」 | 用 `POST http://127.0.0.1:6100/get_login_info` 判断（`retcode:0` + `user_id` 即已登录） |
| 群类型识别失败的退费 | 修复前失败的两次识别各扣了 1 积分 | 管理员用 `POST /api/credits/admin/adjust`，body `{"userId":2,"amount":2,"reason":"群类型识别失败退费"}` |

## 出问题时的最小信息集

提 issue / 找人对齐时，先准备这五项，基本可以省掉一轮往返：

1. **时间线**：大概什么时候开始、之前改了什么（代码/配置/组件升级）——「哪次改动之后开始失败」比盲读代码快得多；
2. **一条具体样本**：`messages.id`（或群号 + 时间），配 `SELECT id, content, media_pending, ai_summary, processed FROM messages WHERE id = <id>;`
3. **日志片段**：按上面 ② 的 grep 取最近 20 行（别贴整份日志）；
4. **队列状态**：第 ③ 步的输出（`Ready`/`Unacked`/`Consumers`，含 `*.dlq`）；
5. **外部组件状态**：6100 / 6185 / 8000 的端口探测结果与 `component-status` 返回。

## 相关

- 通用环境与启动：[部署与运维](deployment)
- 队列与死信细节：[消息队列指南](rabbitmq-guide)
- 链路与鉴权：[架构说明](architecture) · 接口与错误码：[API 接口文档](api-reference)
