# AI 摘要链路故障排查手册

> 与 `doc/MEDIA_TROUBLESHOOTING.md`（媒体链路）配套。本文记录 **2026-09-15「AI 摘要 100% 失败」故障**的根因、排查方法与修复验证。
> 目标：同类问题 **5 分钟内定位**。

> ⚠️ **2026-09-16 变更：消息入库不再自动触发 AI 摘要。**
> 原链路（`MessageService.saveMessage()` → `ai.analysis.queue` → 每条消息调一次 LLM）已**删除**：群活跃时队列长期积压（实测积压 149+ 条待分析），持续消耗模型额度。
> 现在摘要**只在手动触发时生成**：管理员 `POST /api/messages/process`（批量补）或单条手动分析。
> 因此 **"新消息没有摘要"不再是故障，而是预期行为**；本手册适用于"手动触发后仍然失败"的排查。

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
手动触发：管理员 POST /api/messages/process（processAllUnprocessedMessages）
  └─ messageQueueService.sendAiAnalysis(AiAnalysisPayload{messageId, content})
        └─ RabbitMQ  ai.analysis.queue（aiContainerFactory：prefetch=3，重试 3 次）   ← 2026-09-16 起唯一生产者
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
| 群类型「AI 识别」恒显示 其他群 / 置信度 0% / `LLM 返回为空` | 群类型识别服务自己拼了一套 AstrBot 请求（缺 `username` + 未解析 SSE） | 见 §6，已改为复用 `AstrBotService.chat()` |

---

## 4. 批量补历史摘要（按需执行）

> 2026-09-16 起自动摘要已移除，**`processed = 0` 会随消息入库持续增长，这是预期现象**（不再有人自动消费它）。
> 需要摘要时按下面的方式主动补；不需要就放着，不消耗任何额度。

从未处理的消息（`processed=false`，截至 2026-09-16 已有约 2000+ 条）不会自动重算。需要补时二选一：

1. **走应用接口**（推荐）：管理员登录后调用 `POST /api/messages/process`（内部 `messageService.processAllUnprocessedMessages()`），会把所有 `processed=false` 的消息投递到 `ai.analysis.queue`；
2. **直接发 MQ**：向 `ai.analysis.queue` 投递 `AiAnalysisPayload{messageId, content}`。

> ⚠️ 注意：**该接口会把当时所有 `processed=0` 的消息一次性投递**，每条都真实调用一次 AstrBot（约 2–16 秒）。2000 条约需 **数小时**并消耗大量模型额度。
> 强烈建议先分批（把不想补的标成已处理），或挑重点群补：
> ```sql
> -- 查看待补数量
> SELECT COUNT(*) FROM messages WHERE processed = 0;
> -- 只补最近 3 天（示例：先把更早的标记为已处理，避免被一次性投递）
> UPDATE messages SET processed = 1 WHERE processed = 0 AND send_time < NOW() - INTERVAL 3 DAY;
> ```
> 中途想止损：清空队列即可（已投递未消费的任务全部丢弃，消息本身不受影响）：
> ```powershell
> curl.exe -s -u guest:guest -X DELETE "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue/contents"
> ```

---

## 5. 防复发要点

1. **同一外部接口只保留一份解析实现**：`extractChatReply()` 已抽为公共方法，控制器链路后续应改为复用，杜绝"两份实现只有一份对"。
2. **请求字段对照官方要求**：AstrBot `/api/v1/chat` 必填 `username`，且 `enable_streaming` 显式关闭；新接入方先按 §0 ④ 用 curl 打通再写代码。
3. **解析器必须拒绝错误响应**：`status=error` / `retcode!=0` / `data=null` 一律视为失败，绝不写库（本次踩过）。
4. **失败日志要带证据**：空响应时打印 HTTP 状态 + body 片段，避免再出现"只看到空摘要，无从下手"。
5. **模型名不要硬编码**：用配置（`astrbot.summary-model`）或交给 AstrBot 默认模型。
6. **依赖服务健康检查**：AstrBot/NapCat 未运行时，MQ 会快速积压并批量进死信；排查时先确认 6185/6100 端口在监听。
7. **不要在消息入库主链路上挂 LLM 调用**：群活跃时会把额度烧在低价值内容上，并让队列长期积压（2026-09-16 已移除自动摘要，改为按需触发）。若将来要恢复"自动"，至少加配置开关 + 白名单群 + 限流，别直接恢复成"每条都分析"。

---

## 6. 同源故障：群类型 AI 识别恒失败（2026-09-16）

### 现象

群类型设置面板点「AI 识别群类型」后固定显示：**其他群 / 置信度 0% / 判断依据：LLM 返回为空**——而且**照样扣了 1 积分**（`credit_transaction` 里能查到 `remark=群类型识别` 的 OUT 流水）。

### 根因

`GroupTypeRecognitionService.callLlm()` 是 **AstrBot 调用的第二份实现**（第一份是 `AstrBotService.doSummarize`），犯了与 §2 完全相同的两个错：

| # | 缺陷 | 后果 |
|---|---|---|
| 1 | 请求体只放 `message` / `enable_streaming`，**漏了必填 `username`** | AstrBot 直接返回 `{"status":"error","message":"Missing key: username"}` |
| 2 | 响应按整段 JSON 取 `response` 字段，**没做 SSE 解析** | `response` 字段不存在 → 返回 null → 前端显示"LLM 返回为空" |

即再次验证了 §2 的结论：**同一个外部接口被写了两遍，只有一遍是对的**——这次坏的是另一份。

### 修复内容

| 文件 | 改动 |
|---|---|
| `service/AstrBotService.java` | 把已验证的调用逻辑抽为公共方法 `chat(message, apiKey, tag)`：补 `username`、显式 `enable_streaming=false`、走 `extractChatReply()` 解析 SSE、空响应打印状态码与 body 片段；`doSummarize()` 改为调用它 |
| `service/GroupTypeRecognitionService.java` | 删除自写的 `RestTemplate` 调用，改调 `astrBotService.chat(...)`；`RecognitionResult` 增加 `success` 标记；**失败结果不再写 24h 缓存**（否则 24h 内重试都命中同一条失败记录） |
| `controller/GroupController.java` | 改为**识别成功后才扣积分**：先只校验余额（不足返回 402 `INSUFFICIENT_CREDITS`），失败直接返回 `status=error` + `errorCode=RECOGNITION_FAILED`，不扣费 |

### 验证证据

```powershell
# 管理员登录后实测（群 674405515）
curl.exe -s -b cookie.txt -X POST "http://127.0.0.1:8081/api/groups/674405515/recognize-type?forceRefresh=true"
# → {"recognizedType":"HOBBY","confidence":0.82,"reason":"群聊内容主要围绕虚拟主播/UP主…","status":"ok"}   耗时 16.4s
# 空群（无消息）→ {"status":"error","errorCode":"RECOGNITION_FAILED","message":"AI 识别失败：群聊无消息，无法判断"}
# 且 credit_transaction 无新增流水 ✅（失败不扣费）
```

日志对照（`backend\logs\application.log`）：

```
11:15:07 群类型识别完成 groupId=674405515: type=OTHER, confidence=0.0, reason=LLM 返回为空    ← 修复前
11:23:20 群类型识别完成 groupId=674405515: type=HOBBY, confidence=0.82, reason=群聊内容主要围绕… ← 修复后
```

### 遗留项

- ⚠️ **未修（用户选择保留现状）**：`GET /api/system/napcat/login-status` 在 QQ 已登录时仍返回 `loggedIn:false`。原因是它用 `?token=` 查询参数请求 **OneBot(6100)** 的 WebUI 端点（被拒），随后又因 `cache/qrcode.png` 是 5 分钟内的新文件而判定"正在等待登录"。判断真实登录状态请用：
  ```powershell
  curl.exe -s -X POST "http://127.0.0.1:6100/get_login_info" -H "Authorization: Bearer <NAPCAT_TOKEN>" -H "Content-Type: application/json" -d '{}'
  ```
  返回 `retcode:0` 且带 `user_id` 即为已登录。
- 💰 修复前用户 11:15 的两次失败各扣了 1 积分，可用管理员调账接口补回：
  `POST /api/credits/admin/adjust`，body `{"userId":2,"amount":2,"reason":"群类型识别失败退费"}`。

---

## 7. 图片识别不出来：AI 摘要/AI 分析对图片只答"内容未知"（2026-09-19）

### 现象

- 单条 **AI 摘要**：图片消息的摘要固定是「`中性` … 在群内发送了一张图片（内容未知，未提供可辨内容）」，标签只有 `#图片分享 #未明确`；
- **AI 分析**（群聊速览）：图片消息只显示 `[图片]`，合并转发里的图片同样无内容（"已转发 1 条聊天记录 … 无月: [图片]"）；
- AstrBot 的 AI 对话面板单独发图却能正常描述 → 说明**模型本身能看图**，问题在我们这条链路。

### 根因（三个缺陷叠加，缺一不可）

| # | 缺陷 | 后果 |
|---|---|---|
| 1 | 图片只以**文本 URL** 注入提示词（旧"方案 B"：`附图 1: http://127.0.0.1:8081/images/…jpg`） | 模型无法访问该 URL，只能看到一行路径 → 答"内容未知" |
| 2 | 带图请求没指定**视觉配置档案** | AstrBot Agent 的模型来自配置档案（请求体的 `model` 对 Agent 不生效）：默认档案是纯文本模型 → 图片段被丢弃 |
| 3 | 视觉档案绑定的人格 `tools = null` | AstrBot 认为"null = 全部工具"，注入 `send_message_to_user` / `future_task` → 模型去"调工具"回复「请稍等片刻」，摘要正文变成工具调用文本（`send_message_to_user messages [{"type":"plain",…}]`）。**且会话会记住 persona**，复用旧会话会一直沿用旧人格 |

第 3 点还解释了另一种表现：`<|begin_of_box|>{…json…}<|end_of_box|>`、`<tool_call>…</tool_call>` 混进摘要——都是 Agent 管线产物，不是模型看不懂图。

### 修复内容

**AstrBot 侧（环境，脚本可重建）**

| 对象 | 内容 |
|---|---|
| persona `vision_task_quiet` | `system_prompt` 一句话，**`tools = []`**（不挂任何工具） |
| 配置档案 `vision-task` | 主模型沿用 `siliconflow/zai-org/GLM-4.5V`，人格 = `vision_task_quiet` |
| 配置档案 `vision` | 保持不变，继续给 AI 对话面板用（保留用户自己的 `qq_总结bot` 人格） |

一键补齐（幂等，只新增不改动已有档案/人格）：

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant
python backend\scripts\ensure_astrbot_vision_task.py
# 然后重启 AstrBot 让新人格/档案生效：
# POST /api/system/stop-astrbot → POST /api/system/start-astrbot
```

**后端侧**

| 文件 | 改动 |
|---|---|
| `service/AstrBotService.java` | 新增带图摘要 `summarizeMessageStructured(..., List<String> imageUrls)`：图片 → `/api/v1/file` 换 `attachment_id` → message 段 `[{"type":"plain"},{"type":"image"}]`；新增 `chat(Object, …)` 带视觉档案重载；**每次带图请求用全新 `session_id`**（避免沿用旧会话记住的工具人格）；追加"图片已随附、请直接描述画面、禁止答'内容未知'"提示词；`sanitizeReply` 增加 `<\|begin_of_box\|>` 等特殊 token 与代码围栏清理；整段是 JSON 时直接返回（不再被"丢 JSON 行 + 按句去重"拆坏） |
| `controller/AstrBotController.java` | `/analyze`、`/analyze-selected` 带图时 `config_name=vision-task` + 新 `session_id`；提示词从"附图 URL"改为计数说明 + `augmentPromptForImages()` 强约束；`/send-with-image`（AI 对话）仍用 `vision` |
| `controller/MessageController.java`、`consumer/AiAnalysisConsumer.java` | 单条摘要与队列摘要都改调带图重载 |
| `util/RichMessageRenderer.java` | **合并转发卡片**：子消息 `localUrl` 收进 `imageUrls`（图片随消息段送出）、CQ 码清成 `[图片]/[语音]/@某人` 可读占位、展示上限 3 → 10 条 |
| `service/AiSummaryParser.java` | 新增 `extractLastJsonObject()`：答案前后夹带工具调用 JSON 时，取**最后一个**完整 JSON 对象解析（原来整段不是纯 JSON 就退化成"把垃圾当摘要") |
| `service/GroupDigestService.java` | 群日报输入里的 `/images/…jpg` 路径换成 `[图片]` 占位（此前是把路径当正文喂模型） |
| `resources/application.yml` | 新增 `astrbot.vision-task-config-name`（默认 `vision-task`），与 `astrbot.vision-config-name`（默认 `vision`）分工 |

### 验证证据

```powershell
# 单条图片消息摘要（消息 107144 是一条 GIF 动图）
POST /api/messages/107144/summarize?force=true
# → {"tags":["卡通形象","可爱表情","玫瑰元素"],
#    "summary":"九宫格展示的蓝绿色毛发卡通形象手持红玫瑰的多种表情","sentiment":"positive"}

# 合并转发里的图片（消息 105854）
POST /api/astrbot/analyze-selected  {"groupId":"114621418","messageIds":[105854]}
# → "群友正在分享动漫角色相关内容，主要是一张展示多个女性动漫角色的图片。"

# 多图分析（群 674405515，6 条含 3 张图）
POST /api/astrbot/analyze-selected  {"groupId":"674405515","messageIds":[107535,107534,107528,107513,107508,107504]}
# → "**图片内容补充**：
#     1. 第一张图片：黄色卡通角色在不同姿势的九宫格，似乎是搞笑动画片段
#     2. 第二张图片：动漫风格女性角色在海边场景，带有幽默文字
#     3. 第三张图片：娱乐直播界面，显示游戏画面和弹幕互动，中间有中奖提示框"
```

修复前同两条消息的输出分别是「内容未知，未提供可辨内容」与「已转发 1 条聊天记录 … [图片]」。

### 再遇到时的定位顺序

1. **图片有没有真的送出去**：AstrBot 库里查附件类型 —— `type` 必须是 `image`，`mime_type` 必须是 `image/*`；若为 `file` / `application/octet-stream`，说明上传时 multipart 的 Content-Type 不对（AstrBot 按它判类型）：
   ```powershell
   python -c "import sqlite3;c=sqlite3.connect(r'D:\ai\Documents\qq-web\Astrbot\data\data_v4.db');print(*c.execute('select attachment_id,type,mime_type,path from attachments order by rowid desc limit 5'),sep=chr(10))"
   ```
2. **档案对不对**：后端日志里应有 `本次请求带图 N 张，已切换到视觉配置: vision-task`；直接测 AstrBot 时 body 里要带 `"config_name":"vision-task"`。
3. **人格有没有挂工具**：`select persona_id, tools from personas` —— 抽取任务用的人格必须 `tools = []`；出现 `send_message_to_user` / `future_task` 文本就是人格挂满工具或复用了旧会话。
4. **模型支持不支持图片**：AstrBot 里 `provider[].modalities` 里声明 image 不一定真支持（本机 27 个模型里 25 个都这么写）；以 AI 对话面板实测为准。

---

*最后更新：2026-09-19（新增 §7 图片识别链路：视觉档案 + 无工具人格 + 多模态消息段；§6 群类型识别同源故障；§2 为 2026-09-15 AI 摘要故障）*
