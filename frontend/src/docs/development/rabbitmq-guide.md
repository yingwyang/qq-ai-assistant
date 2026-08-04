# RabbitMQ 消息队列

系统使用 RabbitMQ 实现消息异步处理，解耦核心链路，确保 NapCat Webhook 快速响应。

## 部署

### Docker（推荐）

```bash
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  rabbitmq:3-management
```

- **5672**：AMQP 协议端口（后端连接）
- **15672**：管理界面端口（浏览器访问）

### 本地安装

Windows：
```bash
# 安装 Erlang 后执行
rabbitmq-server
# 启用管理插件
rabbitmq-plugins enable rabbitmq_management
```

### 配置

`application-dev.yml`：
```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

## 队列说明

### 媒体下载队列

- **队列**：`media.download.queue`
- **Exchange**：`qqai.media` (Direct, routing-key: `media.download`)
- **prefetch**：5
- **用途**：异步下载图片、视频等媒体文件
- **流程**：Webhook 入库 → 投递下载任务 → 消费者下载 → 回填 URL → WebSocket 推送

### 语音转码队列

- **队列**：`voice.transcode.queue`
- **Exchange**：`qqai.voice` (Direct, routing-key: `voice.transcode`)
- **prefetch**：2（限制并发避免 CPU 打满）
- **用途**：语音文件下载 + SILK/AMR → MP3 转码
- **流程**：Webhook 入库 → 投递转码任务 → 消费者下载+ffmpeg转码 → 回填 URL → WebSocket 推送

### AI 分析队列

- **队列**：`ai.analysis.queue`
- **Exchange**：`qqai.ai` (Direct, routing-key: `ai.analysis`)
- **prefetch**：3
- **用途**：异步调用 AstrBot 进行消息 AI 总结
- **流程**：消息入库 → 投递分析任务 → 消费者调 AstrBot → 回填 aiSummary → WebSocket 推送

### 广播队列

- **队列**：`broadcast.queue`
- **Exchange**：`qqai.broadcast` (Fanout)
- **用途**：WebSocket 消息广播解耦，支持多实例部署

## 死信队列（DLQ）

每个工作队列都配有死信队列：

| 工作队列 | 死信队列 |
|----------|----------|
| media.download.queue | media.download.dlq |
| voice.transcode.queue | voice.transcode.dlq |
| ai.analysis.queue | ai.analysis.dlq |

消费失败自动重试 3 次，超过后进入 DLQ。死信消费者会将消息标记为失败状态。

## 管理界面

访问 `http://localhost:15672`，默认账号 `guest/guest`。

常用功能：
- **Queues**：查看各队列消息堆积情况
- **Connections**：查看后端连接状态
- **Exchanges**：查看 Exchange 绑定关系