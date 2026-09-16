---
title: 消息队列指南
description: 交换机队列全表、消费者配置、手工投递与积压止损
updated: 2026-09-16
---

这一页是 RabbitMQ 的运维手册：队列全表、并发与重试配置、怎么看积压、怎么手工投一条消息、怎么止损，以及三类最常见故障的处置。全部参数取自 `backend/src/main/java/com/qqai/config/RabbitMQConfig.java` 与 `application.yml`。

## 一、交换机 / 队列 / 路由键全表

| 交换机 | 类型 | 队列 | 路由键 | 死信参数 | prefetch | 消费者 |
|--------|------|------|--------|----------|----------|--------|
| `qqai.media` | Direct, durable | `media.download.queue` | `media.download` | DLX=`qqai.media`, DLK=`media.download.dlq` | 5 | `MediaDownloadConsumer.consumeMediaDownload` |
| `qqai.media` | Direct, durable | `media.download.dlq` | `media.download.dlq` | — | 5 | `…consumeMediaDownloadDlq` |
| `qqai.voice` | Direct, durable | `voice.transcode.queue` | `voice.transcode` | DLX=`qqai.voice`, DLK=`voice.transcode.dlq` | 2 | `VoiceTranscodeConsumer.consumeVoiceTranscode` |
| `qqai.voice` | Direct, durable | `voice.transcode.dlq` | `voice.transcode.dlq` | — | 2 | `…consumeVoiceTranscodeDlq` |
| `qqai.ai` | Direct, durable | `ai.analysis.queue` | `ai.analysis` | DLX=`qqai.ai`, DLK=`ai.analysis.dlq` | 3 | `AiAnalysisConsumer.consumeAiAnalysis` |
| `qqai.ai` | Direct, durable | `ai.analysis.dlq` | `ai.analysis.dlq` | — | 3 | `…consumeAiAnalysisDlq` |
| `qqai.broadcast` | Fanout, durable | `broadcast.queue` | 忽略 | **无 DLQ** | 3 | `BroadcastConsumer.consumeBroadcast` |

要点：

- 死信**不另建交换机**，而是复用各业务交换机，通过队列参数指定路由键：

```java
QueueBuilder.durable(MEDIA_DOWNLOAD_QUEUE)
        .withArguments(Map.of(
                "x-dead-letter-exchange", MEDIA_EXCHANGE,       // qqai.media
                "x-dead-letter-routing-key", MEDIA_DOWNLOAD_DLQ_KEY)) // media.download.dlq
        .build();
```

- 消息体统一 `Jackson2JsonMessageConverter`；`BroadcastPayload.jsonPayload` 里是**预序列化好的 JSON 字符串**（绕开 `LocalDateTime` 反序列化兼容问题）。
- `broadcast.queue` 刻意不配 DLQ：广播是尽力而为，失败了重试也没有意义。

## 二、消费者与并发配置

| 容器工厂 | prefetch | 适用 | 设计理由 |
|----------|----------|------|----------|
| `mediaContainerFactory` | 5 | 图片/视频下载 | I/O 密集（HTTP 下载），并发高一点吞吐更好 |
| `voiceContainerFactory` | 2 | 语音下载 + 转码 | CPU 密集（ffmpeg / pysilk），压低并发避免打满机器 |
| `aiContainerFactory` | 3 | AI 摘要、广播 | I/O 密集（远程 LLM 调用），取中间值 |

```java
factory.setPrefetchCount(prefetch);
factory.setDefaultRequeueRejected(false);          // 重试耗尽 → reject(requeue=false) → DLQ
factory.setAdviceChain(RetryInterceptorBuilder.stateless()
        .retryOperations(rabbitRetryTemplate())
        .recoverer(new RejectAndDontRequeueRecoverer())
        .build());
```

`application.yml` 里另有默认监听器重试配置（`max-attempts: 3`、`initial-interval: 1000ms`），但**四个消费者都显式指定了自定义工厂**，真正生效的是上面 `RabbitMQConfig` 里的 `RetryTemplate`。

prefetch 值目前是**代码常量**，`application.yml` 改不动；要调并发必须改 `RabbitMQConfig` 后重新打包重启。

### 启动时的队列声明

后端启动会执行 `rabbitMqDeclarationRunner`：主动建一次连接，触发 `RabbitAdmin` 声明全部 Exchange / Queue / Binding。

```
RabbitMQ 正在建立首次连接并声明所有 Exchange/Queue/Binding ...
RabbitMQ 连接成功，所有 Exchange/Queue/Binding 已声明
```

**RabbitMQ 不可达时应用直接启动失败**（不静默降级）。因此「管理台里看不到队列」通常意味着后端从未成功启动过。

## 三、重试与死信策略

| 项 | 值 | 含义 |
|----|----|------|
| 最大尝试次数 | 3（含首次） | 失败 → 重试 → 重试，共 3 次 |
| 退避策略 | 指数 1s → 2s → 4s，上限 10s | 单条消息最长占用线程约 7 秒 |
| 重试耗尽 | `RejectAndDontRequeueRecoverer` + `defaultRequeueRejected=false` | `reject(requeue=false)`，由队列参数路由进 `*.dlq` |
| 死信再失败 | DLQ 消费者内部 `try/catch`，不回抛 | 避免「死信再死信」导致消息彻底丢失 |

DLQ 消费者的兜底动作（决定了前端最终看到什么）：

| DLQ | 动作 | 前端表现 |
|-----|------|----------|
| `media.download.dlq` | `mediaPending=false`，`content` 写 `[图片已过期]`/`[视频已过期]`/`[媒体已过期]`，推 `message_update` | 消息里显示占位文案 |
| `voice.transcode.dlq` | `mediaPending=false`，`content = [语音转码失败]` | 语音条变成失败提示 |
| `ai.analysis.dlq` | **只记日志**，不改消息（`processed` 保持 `false`、`aiSummary` 为空） | 消息正常可读，只是没摘要 |

## 四、怎么查看积压

管理台：<http://127.0.0.1:15672>（默认 `guest` / `guest`，Docker 部署时密码取 `.env` 的 `RABBITMQ_PASSWORD`）。

队列页要看的四个数字：

| 字段 | 含义 | 异常信号 |
|------|------|----------|
| `Ready` (`messages_ready`) | 待投递消息数 | 持续增长 → 消费者跟不上或没消费 |
| `Unacked` (`messages_unacknowledged`) | 已投递未确认 | 接近 prefetch 上限且不动 → 消费卡住（如下载/转码卡死） |
| `Total` | 两者之和 | — |
| `Consumers` | 在线消费者数 | 为 **0** 说明后端没在消费（没启动 / 连接断开） |

```powershell
# 单个队列深度
curl.exe -s -u guest:guest "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue" |
  ConvertFrom-Json | Select-Object messages,messages_ready,messages_unacknowledged,consumers | Format-List

# 一次看全部队列（含 DLQ）
curl.exe -s -u guest:guest "http://127.0.0.1:15672/api/queues/%2F" |
  ConvertFrom-Json | Select-Object name,messages,messages_ready,consumers | Format-Table -AutoSize
```

> `%2F` 是默认 vhost `/` 的 URL 编码，vhost 不是 `/` 时改成实际名称（如 `%2Fqqai`）。

## 五、如何手工投递一条消息

### 管理台 Publish（推荐，排查用）

`Queues` → 选中队列 → `Publish message`：

| 表单项 | 填什么 |
|--------|--------|
| Delivery mode | `2`（persistent，持久化） |
| Headers | 留空 |
| Properties | `content_type=application/json` |
| Payload | 下面各队列的 JSON 示例 |

> `content_type` 必须设为 `application/json`，否则 `Jackson2JsonMessageConverter` 可能解析失败。消费者按监听方法签名推断目标类型，**不需要**手填 `__TypeId__` 头。

**媒体下载**（发到 `media.download.queue`，队列参数会自动带 DLX）：

```json
{
  "messageId": 84852,
  "rawMessage": "[CQ:image,file=abc.jpg,url=https://multimedia.nt.qq.com.cn/...)",
  "url": "https://multimedia.nt.qq.com.cn/...",
  "groupId": "1082243522",
  "mediaType": "images",
  "extension": ".jpg",
  "fileId": "abc.jpg",
  "trustedSource": false
}
```

`mediaType` 取 `images` / `video` / `voice`（对应落盘子目录）。语音任务发到 `voice.transcode.queue`，把 `mediaType` 改成 `voice`、`extension` 改成 `.amr` 或 `.silk`。

**AI 摘要**（发到 `ai.analysis.queue`）：

```json
{ "messageId": 84972, "content": "群聊内容文本（可直接给 LLM 的纯文本）" }
```

**广播**（发到 `broadcast.queue`，`qqai.broadcast` 是 Fanout，管理台发布时需选交换机）：

```json
{
  "groupId": "1082243522",
  "jsonPayload": "{\"type\":\"message_update\",\"groupId\":\"1082243522\",\"message\":{\"id\":84972,\"aiSummary\":\"手工测试\"}}"
}
```

### HTTP API Publish

```powershell
curl.exe -s -u guest:guest -X POST "http://127.0.0.1:15672/api/exchanges/%2F/qqai.ai/publish" `
  -H "Content-Type: application/json" `
  -d '{"properties":{"content_type":"application/json","delivery_mode":2},"routing_key":"ai.analysis","payload":"{\"messageId\":84972,\"content\":\"请总结：今天群里讨论了部署方案\"}","payload_encoding":"string"}'
```

返回 `{"routed":true}` 表示已路由进队列；`routed:false` 说明路由键写错（检查交换机与 routing key）。

## 六、队列清空与止损

```powershell
# 清空单个队列（Purge，等价于管理台 Queues → Purge Messages）
curl.exe -s -u guest:guest -X DELETE "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.queue/contents"

# 清空死信队列（确认失败原因并修好后，清掉历史积压）
curl.exe -s -u guest:guest -X DELETE "http://127.0.0.1:15672/api/queues/%2F/ai.analysis.dlq/contents"
curl.exe -s -u guest:guest -X DELETE "http://127.0.0.1:15672/api/queues/%2F/media.download.dlq/contents"
```

止损口径：

| 目标 | 做法 | 副作用 |
|------|------|--------|
| 停止继续消耗模型额度 | 清空 `ai.analysis.queue`，或先 `UPDATE messages SET processed = 1 ...` 把不想补的标成已处理再投递 | 已投递未消费的任务被丢弃（消息本身不受影响） |
| 停止消费者 | 停后端进程（`Get-Process java \| Stop-Process`），队列只堆积不消费 | 前端也一起停 |
| 清掉失败历史 | 清空 `*.dlq` | 丢失失败证据，建议先导出日志 |
| 让 DLQ 消息重跑 | 管理台 `Get messages` 复制 payload → 按第五节重新 Publish 到**原交换机 + 原路由键**（不是 `*.dlq`） | 再次失败会重新进 DLQ |

## 七、常见故障

### 1. 消费者不消费（Ready 涨、Consumers=0）

| 检查 | 命令 / 位置 | 结论 |
|------|-------------|------|
| 后端是否在跑 | 后端日志是否有 `RabbitMQ 连接成功` | 没有 → 后端没起来 / 启动时连不上 MQ |
| 队列是否有消费者 | 管理台 `Consumers` 列，或上面第 4 节的 API 示例 | 0 → 消费者没注册（代码未启用 / `@RabbitListener` 类未被扫描） |
| 是否卡在 Unacked | `messages_unacknowledged` 长期贴住 prefetch 上限 | 下载/转码线程卡死（常见于外部 HTTP 无超时响应） |
| 连接是否被拒 | 后端日志 `Connection refused` / `ACCESS_REFUSED` | 密码或 vhost 不对；`guest` 账号**只允许本机登录**，远程部署要另建用户 |

### 2. 消息进 DLQ（`*.dlq` 有积压）

判定顺序（与[排障手册](troubleshooting-playbook)一致）：

1. 看 DLQ 消费者日志：`【DLQ】媒体下载彻底失败（已重试 3 次）` / `【DLQ】语音转码彻底失败` / `【DLQ】AI分析彻底失败`，日志里带完整 payload（含 `messageId`、`fileId`、`url`），可直接定位是哪条消息。
2. 按链路查上一条失败原因：媒体看 `NapCat get_file 未能解析出可下载地址`、`视频原片缺失且未找到缩略图`、`SSRF防护：拒绝访问内网/敏感地址`；AI 看 `AstrBot 返回错误响应`、`AstrBot 响应为空`、`Connection refused`（AstrBot 没运行）。
3. 修复后清 DLQ，并考虑手工重投需要补的少数消息（不要整队列重投，否则再次失败会二次堆积）。

### 3. 预取（prefetch）过大导致「看起来不消费」

现象：`Ready` 不大但处理很慢，`Unacked` 一直顶在 prefetch 上限，日志里同类任务并发数很少。

- prefetch = **单个消费者未确认消息的上限**，不是吞吐指标。语音任务 prefetch=2 是**故意**的（转码吃 CPU），不要为了「快」随意调大。
- 真正的瓶颈通常在外部组件（NapCat 文件服务限速、AstrBot 单次 2–16 秒）。先用日志确认单条耗时，再决定是否改 `RabbitMQConfig` 里的常量并重启。
- 反例：把媒体 prefetch 调到很大，同时大量视频下载会打满带宽与磁盘 I/O，反而拖慢全部消息入库。

## 相关

- 链路与模块分层：[架构说明](architecture)
- 接口清单：[API 接口文档](api-reference)
- 环境与启动顺序：[部署与运维](deployment)
