---
title: AI 摘要为空
description: 摘要按需生成、AstrBot 未运行等
updated: 2026-09-16
---

> ⚠️ **先读这一条：2026-09-16 起，消息入库不再自动生成 AI 摘要。**
> 摘要现在**只在手动触发时生成**，所以「新消息没有摘要」是**预期行为，不是故障**。本页适用于「手动触发后仍然失败」的排查。

## 新消息没有摘要（先确认是不是正常现象）

**现象**：群里不断进新消息，`ai_summary` 一直是空；数据库里 `processed = 0` 的消息越来越多。

**原因**：原自动摘要链路（每条消息入库就投一次 LLM）已**移除**——群活跃时队列长期积压（实测 149+ 条待分析），持续消耗模型额度。现在只有两个生产者：管理员调 `POST /api/messages/process` 批量补，或单条手动分析。因此 `processed = 0` 会随入库持续增长，**没人消费它是设计如此**。

**处理步骤**

```sql
USE qq_chat;
SELECT COUNT(*) AS unprocessed FROM messages WHERE processed = 0;
SELECT COUNT(*) AS total,
       SUM(ai_summary IS NOT NULL AND ai_summary <> '') AS has_summary,
       SUM(processed = 1) AS processed FROM messages;
```

1. 确认不需要摘要 → 什么都不用做，不消耗任何额度；
2. 需要摘要 → 按下一节手动触发（**务必先按时间筛选**）。

**验证**：触发后 `has_summary` 开始增长，日志出现 `AI分析完成: messageId=…, summaryLen=…`，页面上出现摘要标签。

## 如何手动触发摘要（注意会一次性投递全部未处理消息）

**现象**：需要给历史消息补摘要，但不知道从哪里点。

**原因**：`POST /api/messages/process` 会把当时**所有 `processed = 0` 的消息一次性投递**到 `ai.analysis.queue`，每条都真实调用一次 AstrBot（约 2～16 秒）。2000 条约需**数小时**并消耗大量模型额度，所以必须先筛选。

**处理步骤**

1. **先筛掉不想补的**（最稳的止损方式）：

```sql
USE qq_chat;
SELECT COUNT(*) FROM messages WHERE processed = 0;   -- 心里有数再触发
-- 示例：只补最近 3 天，更早的标记为已处理，避免被一次性投递
UPDATE messages SET processed = 1 WHERE processed = 0 AND send_time < NOW() - INTERVAL 3 DAY;
```

2. 管理员登录后触发（需 ADMIN 权限，内部有并发锁，重复点会返回 409「任务正在执行中，请勿重复调用」）：

```powershell
curl.exe -s -c cookie.txt -X POST "http://127.0.0.1:8081/api/auth/login" `
  -H "Content-Type: application/json" `
  -d '{"username":"<管理员用户名>","password":"<密码>"}' | Select-Object -First 1
curl.exe -s -b cookie.txt -X POST "http://127.0.0.1:8081/api/messages/process"
```

   也可以在消息页对单条消息执行「手动分析」，只补一条。
3. 中途想止损：清空队列即可（**已投递未消费的任务全部丢弃，消息本身不受影响**）：
   `curl.exe -s -u <MQ用户名>:<MQ密码> -X DELETE "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue/contents"`
4. 观察进度：

```powershell
curl.exe -s -u <MQ用户名>:<MQ密码> "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue" |
  ConvertFrom-Json | Select-Object messages,messages_ready,consumers | Format-List
```

**验证**：队列深度下降、`processed = 1` 数量上升；抽样检查摘要内容（正常是结构化 JSON，如 `{"tags":[...],"summary":"...","sentiment":"neutral"}`）：

```sql
SELECT id, processed, LEFT(ai_summary, 120) AS summary FROM messages
WHERE ai_summary IS NOT NULL AND ai_summary <> '' ORDER BY id DESC LIMIT 5;
```

> 提示：`processed = 0` 的消息不会自动重算，也不会过期作废；想补随时可以补，不想补就一直放着。

## 触发后仍然失败：按日志分类定位

**现象**：触发了批量补摘要，但任务全部失败，摘要依然为空；`ai.analysis.dlq` 增长，日志出现 `【DLQ】AI分析彻底失败（已重试 3 次）`。

**原因**：消费者抛异常 → 重试 **3 次** → 仍失败进死信队列 `ai.analysis.dlq`；DLQ 消费者**只记日志、不改消息**（`processed` 保持 `false`）。具体原因看日志关键字：

| 日志片段 | 含义 | 处置 |
|---|---|---|
| `HttpHostConnectException: Connect to http://localhost:6185 … Connection refused` | **AstrBot 没运行**（最常见） | 启动 AstrBot（见下） |
| `AstrBot 响应为空: tag=…, status=…, body前300字符=…` | 连上了但解析不到内容 | 看 body 片段：`Missing key: username` → 请求缺字段；SSE 结构变化 → 解析逻辑要跟着改 |
| `AstrBot 返回错误响应: …` / `AstrBot 返回非 0 retcode: …` | AstrBot 明确报错或返回错误码 | 按提示补字段、换 token |
| `【DLQ】AI分析彻底失败（已重试 3 次）` | 最终失败，消息无摘要 | 按根因修好后重新触发批量补 |
| `消息为空内容，跳过AI摘要` / `消息已处理，跳过AI分析` | 正常逻辑（置 `processed=true` / 幂等跳过） | 无需处理 |

**处理步骤**

1. 一条命令把相关日志捞出来：

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Select-String -Path logs\application.log -Pattern 'AI分析完成|AstrBot 响应为空|AstrBot 返回错误响应|AstrBot 返回非 0 retcode|AI分析失败|【DLQ】AI分析|HttpHostConnectException' |
  Select-Object -Last 25 | ForEach-Object { $_.Line }
```

2. **AstrBot 未运行** → 先确认端口，再启动：

```powershell
Get-NetTCPConnection -LocalPort 6185 -State Listen -ErrorAction SilentlyContinue |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

   启动方式：后台「组件控制」→ AstrBot →「启动」；或到 AstrBot 目录手工执行 `astrbot run`，之后打开 <http://localhost:6185> 确认 WebUI 可用。
3. **绕过后端直接测 AstrBot**（区分是 AstrBot 的问题还是后端的问题）：

```powershell
curl.exe -s -N -X POST "http://localhost:6185/api/v1/chat" `
  -H "X-API-Key: <ASTRBOT_TOKEN>" -H "Content-Type: application/json" `
  -d '{"message":"请回复两个字:成功","username":"summarizer","enable_streaming":false}'
```

   正常返回是 SSE 文本流（每行 `data: {json}`），内容在 `type=plain` 的 `data` 字段里：

   ```
   data: {"type": "session_id", "data": null, "session_id": "..."}

   data: {"type": "plain", "data": "成功", "streaming": false, ...}
   ```

   返回 `{"status":"error","message":"Missing key: username"}` → 请求缺 `username`（本项目已修复，再现说明调用方自己拼了请求）；连不上 → AstrBot 未启动或地址/端口不对；鉴权失败 → `ASTRBOT_TOKEN` 不对。
4. 检查模型名配置 `ASTRBOT_SUMMARY_MODEL`（对应 `astrbot.summary-model`）：**留空 = 交给 AstrBot 用它自己的默认模型**（推荐）；填了实例里不存在的模型名（历史上硬编码过 `gpt-3.5-turbo`，而实例只挂了 Kimi/DeepSeek）会被拒或返回空。修改后需**重启后端**生效。
5. 修好后重新触发批量补，并确认死信队列不再增长。

**验证**：日志出现 `AI分析完成: messageId=…, summaryLen=…` 且不再出现 `【DLQ】AI分析`；`ai.analysis.queue` 与 `ai.analysis.dlq` 都归零；抽样摘要为结构化 JSON，**不是**报错文案。

> 提示：若摘要内容是 `Missing key: username`、`Unauthorized` 或 `{"status":"error",...}`，说明**错误响应被当成摘要写进了库**（历史踩过的坑），需要重置后重算：
> ```sql
> UPDATE messages SET ai_summary = NULL, processed = 0
> WHERE ai_summary LIKE '%Missing key%' OR ai_summary LIKE '%Unauthorized%';
> ```

## 顺带一提：群类型「AI 识别」失败与多扣的积分

**现象**：点「AI 识别群类型」后固定显示「其他群 / 置信度 0% / 判断依据：LLM 返回为空」，而且历史上**照样扣了 1 积分**。

**原因**：群类型识别走**同一个 AstrBot 接口**，但曾有一份独立实现（缺 `username`、未解析 SSE），犯了与摘要链路完全相同的两个错。现已改为复用统一的 `chat()` 调用；同时扣费时机后移：**只做余额校验，识别成功后才扣 1 积分**，失败直接返回错误且**不扣费**。

**处理步骤**

1. 确认后端为修复后的版本（调用方不再自写 HTTP 调用）；
2. 管理员实测一次识别：

```powershell
curl.exe -s -b cookie.txt -X POST "http://127.0.0.1:8081/api/groups/<群号>/recognize-type?forceRefresh=true"
```

3. 给历史失败退费用管理员调账接口补回：

```powershell
curl.exe -s -b cookie.txt -X POST "http://127.0.0.1:8081/api/credits/admin/adjust" `
  -H "Content-Type: application/json" `
  -d '{"userId":<用户ID>,"amount":2,"reason":"群类型识别失败退费"}'
```

**验证**：成功返回形如 `{"recognizedType":"HOBBY","confidence":0.82,"reason":"…","status":"ok"}`（耗时可能十几秒）；失败返回 `{"status":"error","errorCode":"RECOGNITION_FAILED","message":"AI 识别失败：…"}`；失败时 `credit_transaction` 表**没有**新增「群类型识别」的 OUT 流水。
