---
title: AI 摘要实装方案
description: 摘要功能的现状缺口、数据模型、接口与落地计划（设计稿）
updated: 2026-09-16
---

> **状态：阶段 0、1、2、3 及其收尾项均已实装（2026-09-16 ~ 09-17）。**
>
> - 阶段 0（结构化存储与卡片展示）：`messages` 新增 `ai_tags/ai_sentiment/ai_summary_short/ai_summarized_at/ai_model`，
>   由 `AiSummaryParser` 解析模型 JSON 后回填；前端渲染「标签 + 情感 + 一句话摘要」卡片，老数据在前端兼容解析。
> - 阶段 1（单条按需摘要）：`POST /api/messages/{id}/summarize?force=false`，聊天页消息悬停出现「AI 摘要 / 重新摘要」按钮。
> - 阶段 2（批量补摘要）：`POST /api/messages/process`（参数化 + 额度 + 409 并发保护）、
>   `GET /api/messages/process/status`、`POST /api/messages/process/stop`，后台「数据维护 → AI 摘要」面板。
> - 阶段 3（群日报）：`group_digest` 表 + `POST /api/groups/{id}/digest?date=&force=`、
>   `GET /api/groups/{id}/digest/latest`、`GET /api/groups/{id}/digests`，聊天页顶部「今日速览」卡片。
> - 配置项见 `application.yml` 的 `ai.summary.*`（enabled / daily-limit / min-length / max-input-chars /
>   group-whitelist / digest-cron / digest-groups），**并已有后台可视化设置页**
>   （管理后台 → 数据维护 → 「摘要设置」）：保存后运行时立刻生效（开关/额度/白名单/cron 都实测过），
>   重启后仍保留（写入 `data/application-override.properties`，优先于 yml 与环境变量）。
> - 群日报收尾已完成：独立 `group.digest.queue`（+ DLQ，prefetch=1；异步入口 `POST /api/groups/{id}/digest/async`）、
>   定时生成（`digest-cron` 用 `SchedulingConfigurer` 动态注册，cron 为空或非法都不影响启动、保存后即时重注册）、
>   群白名单（生成与读取都受约束）、聊天页「历史速览」日期切换。
> - 仍未做：日报历史的分页浏览 UI（当前只展示最近 10 期 chip）、日报导出。
> 讨论背景：2026-09-16 移除了「消息入库即自动摘要」链路（每条消息一次 LLM 调用、队列长期积压），
> 摘要改为按需触发；但按需侧目前只有一个人工 curl 的批量接口，因此需要把这条链路真正"做实"。

## 现状盘点

### 已经具备（复用，不要重写）

| 环节 | 位置 | 说明 |
|------|------|------|
| 队列与消费者 | `RabbitMQConfig` → `AiAnalysisConsumer` | `qqai.ai` → `ai.analysis.queue`，prefetch=3，重试 3 次指数退避后进 `ai.analysis.dlq` |
| LLM 调用 | `AstrBotService.chat()` / `summarizeMessageStructured()` | 已修好 SSE 解析、`username` 必填、错误响应过滤、空响应日志（带状态码 + body 片段） |
| 提示词 | `prompts.yml` 的 `summary.structured` | 支持按群类型（GAME/STUDY/WORK/HOBBY/LIFE/SOCIAL/OTHER）注入不同模板 |
| 存储 | `messages.ai_summary`、`messages.processed` | 单列存模型原始输出 |
| 实时推送 | `MessageBroadcastService` → WebSocket `message_update` | 摘要完成前端可实时刷新 |
| 展示位 | `ChatInterface.vue` 气泡下方「AI 总结」区块 | 已有样式容器 |
| 批量入口 | `POST /api/messages/process` → `processAllUnprocessedMessages()` | 仅此一个入口，无参数 |

### 缺口（按影响排序）

| # | 问题 | 证据 |
|---|------|------|
| 1 | **前端直接展示模型原始 JSON** | 后端把整段输出（可能含 ```` ```json ```` 围栏）写入 `ai_summary`，`ChatInterface.vue` 用 `{{ message.aiSummary }}` 原样渲染 |
| 2 | **批量接口无参数、无 UI、无止损** | `MessageService.processAllUnprocessedMessages()` 遍历全部 `processed=false`（当前 2200+ 条）一次性投递；前端无按钮，只能人工调接口 |
| 3 | **没有单条按需入口** | 用户看到某条消息想分析时没有按钮。注意：用户侧「选中消息分析」走的是 `AstrBotController` 的六维分析，**不写 `ai_summary`**，与摘要不是同一套能力 |
| 4 | **没有结构化字段** | 只有 `ai_summary` 一列，无法按标签/情感筛选、统计、聚合 |
| 5 | **零闸门** | 无每日额度、无群白名单、无长度过滤，一次误触即烧掉数小时额度 |

## 目标与非目标

**目标**

- G1 单条按需摘要：用户想看哪条点哪条，一次调用一条。
- G2 结构化：标签 / 情感 / 一句话摘要分列存储，前端渲染成卡片而不是 JSON。
- G3 批量可控：可按群、按时间、按条数上限投递，有预估、有进度、可一键停止。
- G4 群日报：每群每天一次调用产出「今日速览」，覆盖几百条消息而只花一次调用。
- G5 闸门与可观测：额度、白名单、限流、审计日志、队列深度与进度可见。

**非目标**

- 不恢复"每条消息自动摘要"（成本不可控，已明确移除）。
- 不改动 AI 对话 / 六维分析链路（那两条链路独立计费、独立入口）。
- 不做多语言摘要、不做向量检索。

## 数据模型

### 1. `messages` 新增列（JPA `ddl-auto=update` 自动加列，无需手工迁移）

| 列名 | 类型 | 说明 |
|------|------|------|
| `ai_tags` | VARCHAR(255) | 逗号分隔标签，如 `游戏,组队,攻略` |
| `ai_sentiment` | VARCHAR(16) | `positive` / `neutral` / `negative` |
| `ai_summary_short` | VARCHAR(500) | 一句话摘要（前端展示用） |
| `ai_summarized_at` | DATETIME | 摘要生成时间（用于"何时摘要的"与重算判断） |
| `ai_model` | VARCHAR(64) | 生成摘要的模型标识（便于换模型后回溯） |

`ai_summary` **保留**：存模型原始输出，用于回溯与重算；前端不再直接展示它。

### 2. 新表 `group_digest`（群日报）

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | BIGINT | PK | |
| `group_id` | VARCHAR(32) | NOT NULL | 群号 |
| `digest_date` | DATE | NOT NULL | 归属日期 |
| `content` | TEXT | | 日报正文（结构化 JSON 或纯文本） |
| `message_count` | INT | | 参与生成的消息条数 |
| `token_used` | INT | | 估算消耗，便于成本统计 |
| `model` | VARCHAR(64) | | 使用的模型 |
| `created_at` | DATETIME | | 生成时间 |

唯一约束 `UNIQUE(group_id, digest_date)` —— 同群同天只生成一次，重复调用直接返回已有结果（幂等）。

### 3. 老数据回填

一次性管理动作（后台按钮或启动时执行一次）：

1. 读取 `ai_summary` 非空但 `ai_summary_short` 为空的行；
2. 去掉 ```` ```json ```` 围栏后 `JSON.parse`，取 `tags` / `summary` / `sentiment` 回填三列；
3. 解析失败的行：把原文截断 500 字写入 `ai_summary_short`，`ai_tags` 置空，不重算（避免二次消耗额度）。

## 接口设计

均沿用统一响应结构 `{code, message, data, status}` 与现有错误码语义。

### 1. 单条按需摘要

```
POST /api/messages/{id}/summarize?force=false
```

| 项 | 说明 |
|---|---|
| 权限 | 登录用户；且该消息所属群对当前用户可见（复用群权限校验），管理员不受限 |
| 限流 | 每用户 10 次/分钟（复用现有 `RateLimiterService`），超限 429 |
| 幂等 | 已有 `ai_summary` 且 `force=false` → 直接返回 `cached: true`，不再调用 LLM |
| 返回 | `{ status, cached, cost, summary: { tags: [], summary, sentiment } }` |
| 错误 | 404 消息不存在 ｜ 403 无权 ｜ 429 限流 ｜ 503 AstrBot 不可用（带明确提示） |
| 实现 | 复用 `AstrBotService.summarizeMessageStructured()` + 新增的解析回填方法；**同步返回**（单条 2–16 秒，可接受） |

### 2. 批量补摘要（改造现有接口）

```
POST /api/messages/process
{ "groupId": "808521399", "start": "2026-09-14", "end": "2026-09-16", "limit": 200, "minLength": 8, "onlyText": true }
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `groupId` | 空 | 指定群；与 `start` 至少给一个，否则 **400**（杜绝"一键 2200 条"） |
| `start` / `end` | 空 | 时间范围（按消息发送时间） |
| `limit` | 100 | 单次投递上限，硬上限 500（管理员 2000） |
| `minLength` | 8 | 内容长度小于该值的消息跳过 |
| `onlyText` | true | 跳过媒体占位符（`[图片已过期]` 等）与纯表情 |

返回 `{ status, queued, skipped, estimatedTokens }`；并发保护：已有任务进行中返回 **409**。

### 3. 任务进度与停止

```
GET  /api/messages/process/status
POST /api/messages/process/stop
```

`status` 返回：`{ queueDepth, processing, doneToday, totalPending, running }`（`queueDepth` 读 RabbitMQ 管理 API，`doneToday` 统计 `ai_summarized_at >= 今天`）。
`stop`：清空 `ai.analysis.queue`（**保留 DLQ**），用于止损；写审计日志。

### 4. 群日报

```
POST /api/groups/{groupId}/digest?date=2026-09-16   # 生成（幂等：当天已有则返回已有）
GET  /api/groups/{groupId}/digest/latest            # 最新一期
GET  /api/groups/{groupId}/digests?limit=30         # 历史列表
```

权限：同群可见性；每群每天默认 1 次（重复调用返回缓存，不重复扣额度）。
输入拼接：当天该群消息按时间排序，逐条截断后拼接，总长超 `maxInputChars`（默认 12000 字符）则按"两端保留 + 中间省略"处理。

### 5. 配置项（新增后台「AI 摘要」设置，或并入现有积分规则页）

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关，关闭后所有摘要接口返回 403 |
| `dailyLimit` | 300 | 全站每日摘要条数上限，超限批量接口直接拒绝 |
| `groupWhitelist` | 空=全部 | 参与摘要的群；空表示不限制 |
| `minLength` | 8 | 最短参与长度 |
| `maxInputChars` | 12000 | 群日报拼接上限 |
| `digestCron` | 空=不定时 | 如 `0 50 23 * * ?`（每天 23:50） |
| `digestGroups` | 空 | 定时生成日报的群列表 |

## 消费链路改造

1. `AiAnalysisConsumer`（单条）
   - 拿到模型输出后：先尝试解析 JSON → 回填 `ai_tags` / `ai_sentiment` / `ai_summary_short` / `ai_summarized_at` / `ai_model`，同时保留 `ai_summary` 原文；
   - 解析失败：不抛异常（**不触发重试**，避免重试烧额度），`ai_summary_short` 存去掉围栏后的截断文本，并记 `warn`；
   - `processed=true` 语义保持不变：表示"这条消息已经处理过，不必再投递"。
2. 新增 `GroupDigestService`
   - 取当天消息 → 拼接/截断 → 一次 `chat()` 调用 → 解析 → 写 `group_digest`；
   - 走**独立队列** `group.digest.queue`（避免与单条摘要互相阻塞；单条摘要可能积压几十分钟）。
3. 新增 `AiSummaryQuotaService`：每日额度计数（Redis/Caffeine 或直接查库统计）、白名单校验、限流。

## 前端交互

### 1. 单条摘要卡片（`ChatInterface.vue`）

- 消息悬停（移动端长按）显示「AI 摘要」按钮；已有摘要则显示"重新摘要"（`force=true`）。
- 点击 → 按钮转圈（同步请求，2–16 秒）→ 完成后气泡下方渲染卡片：
  - 标签 chips（点击可跳转搜索该标签）
  - 情感徽章：`positive` 绿 / `neutral` 灰 / `negative` 红（中文：积极 / 中性 / 消极）
  - 一句话摘要正文
- **兼容旧数据**：只有 `ai_summary` 时，前端尝试剥离 ```` ```json ```` 围栏后 `JSON.parse`；失败则纯文本展示（不再出现裸 JSON）。

### 2. 批量面板（管理后台 → 数据维护 → 新增「AI 摘要」区块）

- 表单：群（下拉）、时间范围、条数上限、最短长度；
- 提交前显示**预估**：`将投递 N 条 ≈ X 万 token ≈ 约 Y 分钟`（按 1.5k token/条、8 秒/条估算）；
- 提交后显示进度：队列深度、处理中、今日已完成、剩余待处理；提供「停止」按钮（清空队列）；
- 全程写审计日志（操作人、参数、投递条数）。

### 3. 群日报

- 群聊页顶部新增「今日速览」卡片：日期 + 一句话总览 + 展开看完整日报 + "重新生成"（管理员）；
- 侧栏或群设置里可查看历史日报列表；
- 管理员可为某群开启"每日自动生成"。

## 风控与配额

1. **每日额度**：`dailyLimit`（默认 300 条/天）——批量接口先算额度再投递，超限直接拒绝并提示剩余额度；
2. **群白名单**：不在白名单的群，单条摘要也拒绝（避免把无关群烧掉）；
3. **长度与内容过滤**：`minLength`、跳过媒体占位符与纯表情；
4. **限流**：单条摘要 10 次/分钟/用户；
5. **审计**：批量投递、停止、日报生成全部写 `audit_log`；
6. **止损**：`stop` 一键清空队列 + `enabled=false` 全局开关。

## 成本估算（按当前数据规模）

| 场景 | 调用次数 | Token（约） | 耗时（约） |
|---|---|---|---|
| 单条摘要 | 1 | 1.5k | 2–16 秒 |
| 批量补 2200 条（当前 `processed=0`） | 2200 | 330 万 | 5–10 小时（prefetch=3 串行） |
| 群日报（20 个群，每天） | 20 | 24 万 | 3–6 分钟 |
| 群日报（20 群 × 30 天） | 600 | 720 万 | — |

> 结论：**群日报是性价比最高的形态**——一次调用覆盖几百条消息，成本比逐条摘要低两个数量级，且更贴近"今天群里聊了什么"的真实需求。

## 分阶段落地计划

| 阶段 | 内容 | 工作量 | 验收标准 |
|---|---|---|---|
| **阶段 0** | 结构化回填 + 前端卡片（不新增 LLM 调用） | 约 0.5 天 | 现有 100 条摘要在界面上显示为「标签 + 情感 + 摘要」；老数据 JSON 解析失败仍能正常展示 |
| **阶段 1** | 单条按需摘要（接口 + 按钮 + 限流 + 幂等） | 约 1 天 | ✅ **已实装**：点击按钮 9–18 秒内出卡片；重复点击（未加 force）返回缓存不重复调用；无权限 403、不存在 404、超频 429 均实测通过 |
| **阶段 2** | 批量工具（参数化 + 预估 + 进度 + 停止 + 审计） | 约 1–2 天 | ✅ **已实装**：不带范围提交返回 400「必须指定 groupId 或 start」；投递后 `status` 返回本批进度（batchSize/batchDone/processing）；重复投递返回 **409**；停止清空队列并写审计。⚠️ 实测发现：**不能**用「队列深度 > 0」判断进行中——消费者 prefetch=3 会立刻取走消息使队列归零，改用「今日已完成 − 本批基数 < 本批条数」判断 |
| **阶段 3** | 群日报（新表 + 服务 + 独立队列 + 前端速览 + 可选定时） | 约 1 天 | ✅ **已实装**：同群同天重复调用 0 秒返回缓存；实测首次生成 19.4s（307 条消息 → 一句话总览 + 5 个标签 + 情感）；`force=true` 重新生成会就地覆盖同一行（表内仍 1 行）。⚠️ 未做：独立 `group.digest.queue`（当前同步生成）、定时 `digestCron`、群白名单 |

建议顺序即上表顺序：阶段 0 立刻可见效果且零成本，阶段 3 收益最大。

## 风险与回滚

| 风险 | 应对 |
|---|---|
| 额度失控 | `dailyLimit` + 群白名单 + `stop` 一键清队列 + `enabled` 总开关 |
| 结构化解析失败 | 不重试、保留原文、`ai_summary_short` 存截断文本 |
| 队列互相阻塞 | 群日报走独立队列 `group.digest.queue` |
| 单条接口被刷 | 每用户 10 次/分钟限流 + 群可见性校验 |
| 换模型后质量波动 | `ai_model` 记录来源，可定位并只重算指定模型产出的摘要 |
| 回滚 | 关闭 `enabled` 即停用全部新入口；新增列/表不影响旧代码（JPA 只增不删） |

## 附：与现有能力的关系

| 能力 | 入口 | 是否写 `ai_summary` | 计费 |
|---|---|---|---|
| **AI 摘要**（本文） | 单条按钮 / 批量面板 / 群日报 | 是 | 计入 `dailyLimit`（不额外扣用户积分） |
| 选中消息六维分析 | 群聊页选中消息 → AI 分析 | 否 | 扣积分（`analyze_cost_per_msg`） |
| AI 对话 | 对话面板 | 否 | 按 Token 扣积分 |

> 三者共用同一个 AstrBot `/api/v1/chat` 调用与 SSE 解析（`AstrBotService.chat()`），
> 但提示词模板、存储与计费口径完全不同 —— 实装摘要时**不要**改动分析/对话两条链路。
