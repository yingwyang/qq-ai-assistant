# AI 摘要链路故障排查手册

> 与 `doc/MEDIA_TROUBLESHOOTING.md`（媒体链路）配套。本文记录 **2026-09-15「AI 摘要 100% 失败」故障**的根因、排查方法与修复验证。
> 目标：同类问题 **5 分钟内定位**。

---

## 0. TL;DR · 五分钟定位

### ① 看数据库（摘要是否落库）

```sql
USE qq_chat;
SELECT COUNT(*) total,
       SUM(ai_summary IS NOT NULL AND ai_summary <> '') AS has_summary,
       SUM(processed = 1) AS processed
FROM messages;

-- 抽样看摘要内容是否正常（注意排除被错误响应污染的情况）
SELECT id, processed, LEFT(ai_summary, 120) AS summary
FROM messages WHERE ai_summary IS NOT NULL AND ai_summary <> '' ORDER BY id DESC LIMIT 5;
```

判读：

| 现象 | 含义 |
|---|---|
| `has_summary` 长期为 0 / 增长缓慢 | 摘要链路故障（见 ②） |
| 摘要内容是 `Missing key: username`、`Unauthorized`、`{"status":"error",...}` | **解析器把接口报错当成摘要写了库**（本手册 §2 的坑之一） |
| 摘要为 `{"tags":[...],"summary":"...","sentiment":"..."}` | ✅ 正常（`summary.structured` 模板的结构化输出） |

### ② 看后端日志

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Select-String -Path app-run.log -Pattern 'AI分析完成|AI摘要响应为空|AstrBot 返回错误|AI分析失败|【DLQ】AI分析|HttpHostConnectException' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }
```

| 日志片段 | 含义 | 处置 |
|---|---|---|
| `AI分析完成: messageId=..., summaryLen=...` | ✅ 成功 | — |
| `AI摘要响应为空: status=..., body前300字符=...` | 已连上 AstrBot 但解析不到内容 | 看 body 片段：若是 `Missing key: username` → 请求缺字段；若是 SSE 结构变化 → 解析逻辑要跟着改 |
| `AstrBot 返回错误响应: ...` | AstrBot 明确报错（缺字段/鉴权失败） | 按提示补字段或换 token |
| `HttpHostConnectException: Connect to http://localhost:6185 ... Connection refused` | **AstrBot 没运行** | 启动 AstrBot（最常见原因） |
| `【DLQ】AI分析彻底失败（已重试 3 次）` | 重试 3 次仍失败，消息无摘要 | 按上面的根因处置；修好后可批量补（见 §4） |

### ③ 看队列积水

RabbitMQ 管理台 <http://127.0.0.1:15672>（guest/guest）：

- `ai.analysis.queue` 堆积 → 消费者没跟上，或每条都失败后重试占满；
- `ai.analysis.dlq` 增长 → 持续失败，按 ② 定位。

```powershell
curl.exe -s -u guest:guest "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue" |
  ConvertFrom-Json | Select-Object messages,messages_ready | Format-List
```

### ④ 直接测 AstrBot（绕过后端）

```powershell
# 必须带 username，否则返回 {"status":"error","message":"Missing key: username"}
curl.exe -s -N -X POST "http://localhost:6185/api/v1/chat" `
  -H "X-API-Key: <ASTRBOT_TOKEN>" -H "Content-Type: application/json" `
  -d '{"message":"请回复两个字:成功","username":"summarizer","enable_streaming":false}'
```

正常返回是 **SSE 文本流**（每行 `data: {json}`）：

```
data: {"type": "session_id", "data": null, "session_id": "..."}

data: {"type": "plain", "data": "成功", "streaming": false, ...}
```

内容在 `type=plain` 的 `data` 字段里。

---

## 1. 链路全貌

```
消息入库（RootWebhookController）
  └─ messageQueueService.sendAiAnalysis(AiAnalysisPayload{messageId, content})
        └─ RabbitMQ  ai.analysis.queue（aiContainerFactory：prefetch=3，重试 3 次）
              └─ AiAnalysisConsumer.consumeAiAnalysis
                    ├─ 已 processed → 跳过
                    ├─ AstrBotService.renderMessageForSummary(message)   ← 富媒体渲染（图片 URL 等）
                    ├─ AstrBotService.summarizeMessageStructured(...)    ← 调 AstrBot /api/v1/chat
                    │     └─ extractChatReply(rawBody)                   ← SSE/JSON 解析（本文重点）
                    ├─ 成功：ai_summary = 结果、processed = true、WebSocket 推送 message_update
                    └─ 失败：抛异常 → 重试 3 次 → ai.analysis.dlq（仅日志，不改消息）
```

相关文件：`service/AstrBotService.java`（`doSummarize` / `extractChatReply`）、`consumer/AiAnalysisConsumer.java`、`service/PromptTemplateService` + `resources/prompts.yml`（`summary.structured` 模板）。

---

## 2. 本次故障复盘（2026-09-15）

### 现象

- 全库 1251 条消息 **0 条有 AI 摘要**、`processed` 全为 0；
- 日志累计 **118 次**【DLQ】AI分析彻底失败；
- 每条消息都白跑 3 次重试 + 1 次死信。

### 根因（三个独立缺陷叠加，另有 1 个环境因素）

| # | 缺陷 | 位置 | 后果 |
|---|---|---|---|
| 1 | **响应解析格式错误** | `AstrBotService.doSummarize` 用 `objectMapper.readTree(整体)` 并读 `response` 字段；而 AstrBot 返回的是 **SSE 文本流**（`data: {...}`，内容在 `type=plain` 的 `data` 里） | 永远解析不到 → 返回 null → 消费者抛"AI分析返回空摘要" |
| 2 | **请求缺必填字段 `username`** | 同上，请求体只放了 `message` / `model` / `temperature` | AstrBot 直接返回 `{"status":"error","message":"Missing key: username"}` |
| 3 | **错误响应被当成摘要写库** | 修复 #1 时新增的 JSON 兜底分支遍历了 `message` 字段 | 365 条消息的 `ai_summary` 被写成了 `Missing key: username`（已全部重置重算） |
| 4 | 环境因素 | AstrBot 未运行 | 期间的失败是 `Connection refused`，与代码无关，但会掩盖真实问题 |

另外：原请求**硬编码 `model: "gpt-3.5-turbo"`**（实例里只挂了 Kimi/DeepSeek 等），现已改为配置项 `astrbot.summary-model`（默认留空 = 用 AstrBot 默认模型）。

### 为什么"手动分析正常、自动摘要全挂"

`AstrBotController`（网页聊天/手动分析链路）**自己写了一份 `line.startsWith("data: ")` 的 SSE 解析**，所以正常；`AstrBotService`（后台自动摘要）是另一份实现，没做 SSE 解析。**同一个接口两处各写一遍，只有一处是对的**——现在 `AstrBotService.extractChatReply()` 已经是可复用的公共方法（控制器后续也可迁过来，避免再次分叉）。

### 修复内容

| 文件 | 改动 |
|---|---|
| `service/AstrBotService.java` | 新增 `extractChatReply()`：先按 SSE 逐行取 `type=plain` 的 `data`；再兼容整段 JSON（`response`/`data`/`text`），并**显式拒绝 `status=error`/`retcode!=0` 的错误响应**；请求补 `username`、显式 `enable_streaming=false`；`model` 改为配置 `astrbot.summary-model`；响应为空时打日志（带 HTTP 状态与 body 前 300 字符） |
| `resources/application.yml` | 新增 `astrbot.summary-model`（默认空） |

### 验证证据

- 修复后发测试消息 → `messages.id=84972`：`processed=1`，`ai_summary={"tags":["项目评审","会议通知","工作"],"summary":"ai-probe3通知大家明天下午三点召开项目评审会…","sentiment":"neutral"}` ✅
- 修复窗口期被污染写入 `Missing key: username` 的 365 条已重置为 `NULL/processed=0`，等待重算 ✅
- `ai.analysis.queue` / `.dlq` 均为 0，不再出现 `【DLQ】AI分析` 日志 ✅

---

## 3. 常见症状 → 处置速查表

| 症状 | 最可能原因 | 处置 |
|---|---|---|
| 摘要全为 0 | AstrBot 未运行 / 解析格式不匹配 / 请求缺字段 | 按 §0 四步逐项确认；先看日志的 `HttpHostConnectException` 与 `body前300字符` |
| 摘要内容是报错文案 | 解析器未过滤错误响应 | 确认 `extractChatReply` 的 `status=error` / `retcode!=0` 分支未被回退 |
| 新消息没有摘要但日志正常 | 队列积压 / 消费者未订阅 | 查 `ai.analysis.queue` 深度与后端日志是否有 `消费AI分析任务` |
| `processed` 一直为 0 | 每次分析都失败（重试 3 次后进 DLQ） | 看 `.dlq` 与失败日志 |
| 摘要内容为空字符串 | 消息内容为空（渲染后为空） | 属正常逻辑（消费者直接置 `processed=true`） |
| 摘要中带工具调用 JSON | 模型返回了 tool-call 结构 | 前端已有过滤（`utils/messageFilter.js`），如需后端过滤可复用该策略 |

---

## 4. 批量补历史摘要（可选）

被重置或从未处理的消息（当前约 1252 条）不会自动重算——队列已排空。需要补时二选一：

1. **走应用接口**（推荐）：管理员登录后调用 `POST /api/messages/process`（内部 `messageService.processAllUnprocessedMessages()`），会把所有 `processed=false` 的消息重新投递到 `ai.analysis.queue`；
2. **直接发 MQ**：向 `ai.analysis.queue` 投递 `AiAnalysisPayload{messageId, content}`。

> 注意：每条消息都会真实调用一次 AstrBot（约 2–10 秒），1252 条约需 **1–2 小时**并消耗模型额度。建议分批（例如只补最近 N 天），或挑重点群补。
> 相关 SQL：
> ```sql
> -- 查看待补数量
> SELECT COUNT(*) FROM messages WHERE processed = 0;
> -- 只补最近 3 天（示例：先把更早的标记为已处理，避免被一次性投递）
> UPDATE messages SET processed = 1 WHERE processed = 0 AND send_time < NOW() - INTERVAL 3 DAY;
> ```

---

## 5. 防复发要点

1. **同一外部接口只保留一份解析实现**：`extractChatReply()` 已抽为公共方法，控制器链路后续应改为复用，杜绝"两份实现只有一份对"。
2. **请求字段对照官方要求**：AstrBot `/api/v1/chat` 必填 `username`，且 `enable_streaming` 显式关闭；新接入方先按 §0 ④ 用 curl 打通再写代码。
3. **解析器必须拒绝错误响应**：`status=error` / `retcode!=0` / `data=null` 一律视为失败，绝不写库（本次踩过）。
4. **失败日志要带证据**：空响应时打印 HTTP 状态 + body 片段，避免再出现"只看到空摘要，无从下手"。
5. **模型名不要硬编码**：用配置（`astrbot.summary-model`）或交给 AstrBot 默认模型。
6. **依赖服务健康检查**：AstrBot/NapCat 未运行时，MQ 会快速积压并批量进死信；排查时先确认 6185/6100 端口在监听。

---

*最后更新：2026-09-15（AI 摘要故障修复后记录）*
