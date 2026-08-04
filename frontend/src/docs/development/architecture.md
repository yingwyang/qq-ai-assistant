# 架构说明

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 前端 | Vue 3 + Vite | SPA 单页应用 |
| 图表 | ECharts (vue-echarts) | 数据可视化 |
| 后端 | Spring Boot 3 + Java 21 | RESTful API |
| 数据库 | MySQL 8.0 | 数据持久化 |
| 消息队列 | RabbitMQ | 异步消息处理 |
| ORM | Spring Data JPA | 数据访问层 |
| 安全 | Spring Security + JWT | 认证与授权 |
| 进程管理 | Apache Commons Exec | 插件进程生命周期管理 |

## 系统架构

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   前端 Vue   │────→│  Spring Boot  │────→│   MySQL     │
│  (Vite SPA)  │←────│   (REST API)  │←────│  Database   │
└─────────────┘     └──────┬───────┘     └─────────────┘
                           │
                    ┌──────┴───────┐
                    │  RabbitMQ    │
                    │ (消息队列)    │
                    └──────┬───────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
    ┌──────┴──────┐ ┌─────┴─────┐ ┌───────┴───────┐
    │媒体下载消费者│ │AI分析消费者│ │广播消费者     │
    │语音转码消费者│ │           │ │(WebSocket推送) │
    └─────────────┘ └───────────┘ └───────────────┘
```

## 消息处理流程

### NapCat Webhook 异步化

```
NapCat 推送消息
    ↓
RootWebhookController (轻量解析 + 入库)
    ↓ 投递到 RabbitMQ 队列
    ├── 含图片 → media.download.queue → MediaDownloadConsumer
    ├── 含语音 → voice.transcode.queue → VoiceTranscodeConsumer
    └── AI总结 → ai.analysis.queue → AiAnalysisConsumer
    ↓
消费者完成后通过 broadcast.queue (Fanout) → WebSocket 推送前端
```

### 队列架构

| 队列 | Exchange | 用途 | prefetch |
|------|----------|------|----------|
| media.download.queue | qqai.media (Direct) | 图片/视频下载 | 5 |
| voice.transcode.queue | qqai.voice (Direct) | 语音下载+转码 | 2 |
| ai.analysis.queue | qqai.ai (Direct) | AI 消息分析 | 3 |
| broadcast.queue | qqai.broadcast (Fanout) | WebSocket 消息广播 | - |

每个工作队列都配有死信队列（DLQ），消费失败 3 次后进入 DLQ。

## 目录结构

### 前端

```
frontend/src/
├── components/      # 公共组件
│   ├── Sidebar.vue
│   ├── UserMenuPopover.vue
│   └── Icon.vue
├── views/           # 页面视图
│   ├── HomeView.vue
│   ├── AdminView.vue
│   ├── DocView.vue
│   └── LoginPage.vue
├── composables/     # 组合式函数
│   ├── useTheme.js
│   └── useDashboardData.js
├── services/        # API 服务
│   └── api.js
└── router/          # 路由配置
    └── index.js
```

### 后端

```
backend/src/main/java/com/qqai/
├── config/          # 配置类 (RabbitMQConfig, AsyncConfig, ...)
├── consumer/        # MQ 消费者 (Media/AI/Broadcast)
├── controller/      # 控制器
├── entity/          # 实体类
├── repository/      # 数据访问层
├── service/         # 业务逻辑层
├── websocket/       # WebSocket 处理器
└── security/        # 安全配置
```

## 进程管理

系统采用集中式进程管理：

- **ProcessManager**：基于 Apache Commons Exec 统一管理插件进程生命周期
- **幂等启动**：启动前检查进程引用 + 端口监听状态
- **自动清理**：检测并清理残留进程
- **并发控制**：`volatile` 标志防止并发启动
- **登录联动**：登录后自动启动已配置插件，登出时自动停止