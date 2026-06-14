# 铃音QQ对话 - 跨端智能聊天辅助系统

基于 SpringBoot + Vue3 + AstrBot + NapCat + GPT-SoVITS 的跨端智能聊天辅助系统。

## 项目简介

铃音QQ对话是一个创新的聊天辅助平台，通过 NapCat 监听 QQ 消息，经由 AstrBot 推送到 SpringBoot 后端存入数据库，前端从数据库获取群聊内容，结合 AI 实现消息解释与总结。

### 核心功能

- **QQ消息监听与管理** - 实时接收和存储QQ群聊消息
- **AI消息总结与分析** - 通过 AstrBot 实现智能消息分析
- **语音合成功能** - 集成 GPT-SoVITS 实现文本转语音
- **系统组件一键控制** - 一键启动/停止 AstrBot、NapCat、GPT-SoVITS
- **NapCat二维码登录** - 嵌入 NapCat 扫码登录功能
- **消息归档与清理** - 自动归档历史消息，定期清理旧数据
- **多媒体消息支持** - 支持图片、语音、视频、表情、@消息、回复消息显示
- **语音自动转换** - AMR语音自动转换为MP3格式播放
- **多用户支持** - 支持多个QQ账号登录，群聊记录相互独立
- **群头像自动下载** - 自动下载并缓存群聊头像
- **消息选择分析** - 支持选择多条消息进行AI分析
- **头像自定义** - 支持上传自定义头像，跨浏览器同步

## 技术架构

### 后端技术栈

- **Spring Boot 3.2.0** - 核心框架
- **Spring Data JPA** - 数据持久化
- **MySQL 8.0** - 主数据库，存储消息元数据
- **FFmpeg** - 语音格式转换（AMR转MP3）
- **WebSocket** - 实时消息推送
- **Maven** - 项目构建
- **Thumbnailator** - 图片缩略图处理

### 前端技术栈

- **Vue 3** - 前端框架
- **Vite** - 构建工具
- **Axios** - HTTP客户端
- **CSS Grid/Flexbox** - 响应式布局

### 第三方组件

- **AstrBot** - AI聊天与消息总结
- **NapCat** - QQ消息监听与OneBot协议实现
- **GPT-SoVITS** - 语音合成
- **FFmpeg** - 音视频处理

## 项目结构

```
qq-ai-assistant/
├── backend/                    # SpringBoot后端
│   ├── src/main/java/com/qqai/
│   │   ├── config/            # 配置类
│   │   ├── controller/        # API控制器
│   │   ├── entity/            # 实体类
│   │   ├── repository/        # 数据访问层
│   │   ├── service/           # 业务逻辑层
│   │   └── websocket/         # WebSocket处理器
│   ├── src/main/resources/
│   │   ├── application.yml    # 应用配置
│   │   └── init-mysql.sql     # 数据库初始化脚本
│   ├── uploads/               # 本地文件存储目录
│   │   ├── images/            # 图片、语音、视频存储
│   │   ├── temp/              # 临时文件
│   │   └── archive/           # 归档文件
│   └── pom.xml                # Maven配置
├── frontend/                   # Vue3前端
│   ├── src/
│   │   ├── components/        # Vue组件
│   │   │   ├── AstrBotChat.vue      # AI对话组件
│   │   │   ├── ChatInterface.vue    # 群聊消息展示
│   │   │   ├── LoginModal.vue       # 登录/系统控制
│   │   │   ├── MessageContent.vue   # 消息内容渲染
│   │   │   └── Sidebar.vue          # 左侧导航栏
│   │   ├── services/          # API服务
│   │   ├── App.vue            # 根组件（三栏布局）
│   │   └── main.js            # 入口文件
│   ├── package.json           # NPM配置
│   └── vite.config.js         # Vite配置
├── ffmpeg-8.1-essentials_build/  # FFmpeg工具（语音转换）
└── README.md                  # 项目说明
```

## 数据库设计

### 核心表结构

#### 1. **messages** - 消息表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| message_id | String | QQ消息唯一ID |
| group_id | String | 群号 |
| group_name | String | 群名称 |
| user_qq | String | 发送者QQ |
| user_nickname | String | 发送者昵称 |
| message_type | Enum | 消息类型：TEXT/IMAGE/VIDEO/AUDIO/FILE/VOICE/AT/REPLY |
| content | String | 文本内容或文件描述 |
| file_id | String | 关联的文件ID |
| at_qq | String | @的用户QQ |
| reply_to_message_id | Long | 回复的消息ID |
| ai_summary | String | AI总结内容 |
| send_time | LocalDateTime | 发送时间 |
| created_at | LocalDateTime | 创建时间 |
| archived | boolean | 是否已归档 |
| processed | boolean | 是否已处理 |
| is_self_message | boolean | 是否是登录账号发送的消息 |
| self_qq | String | 登录账号的QQ号 |

#### 2. **users** - 用户表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| qq | String | QQ号码（唯一） |
| nickname | String | 昵称 |
| avatar | String | 头像URL |
| token | String | 认证令牌 |
| last_login_time | LocalDateTime | 最后登录时间 |
| created_at | LocalDateTime | 创建时间 |
| active | boolean | 是否活跃 |

#### 3. **groups** - 群聊表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| group_id | String | 群号 |
| owner_qq | String | 登录者QQ号（多用户支持） |
| group_name | String | 群名称 |
| avatar | String | 群头像URL |
| member_count | Integer | 成员数量 |
| joined_time | LocalDateTime | 加入时间 |
| created_at | LocalDateTime | 创建时间 |
| active | boolean | 是否活跃 |

#### 4. **file_records** - 文件记录表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| file_id | String | 文件唯一ID |
| file_name | String | 原始文件名 |
| file_type | Enum | 文件类型：IMAGE/VIDEO/AUDIO/FILE |
| file_size | Long | 文件大小（字节） |
| mime_type | String | MIME类型 |
| storage_type | Enum | 存储类型：LOCAL/MINIO/OSS/COS |
| bucket_name | String | 存储桶名 |
| object_key | String | 对象存储路径 |
| url | String | 访问URL |
| thumbnail_url | String | 缩略图URL |
| width | Integer | 图片宽度 |
| height | Integer | 图片高度 |
| duration | Integer | 音视频时长（秒） |
| created_at | LocalDateTime | 创建时间 |

#### 5. **astrbot_conversations** - AstrBot对话会话表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| conversation_id | String | 对话唯一ID |
| group_id | String | 关联的群号 |
| user_qq | String | 用户QQ |
| user_nickname | String | 用户昵称 |
| title | String | 对话标题 |
| model | String | 使用的AI模型 |
| message_count | Integer | 消息数量 |
| total_tokens | Integer | 总Token数 |
| context_json | String | 上下文JSON数据 |
| archived | Boolean | 是否已归档 |
| time_created | LocalDateTime | 创建时间 |
| time_updated | LocalDateTime | 更新时间 |
| time_archived | LocalDateTime | 归档时间 |

#### 6. **astrbot_messages** - AstrBot对话消息表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| message_id | String | 消息唯一ID |
| conversation_id | String | 所属对话ID |
| role | Enum | 消息角色：USER/ASSISTANT/SYSTEM |
| content | String | 消息内容 |
| data | String | 扩展数据JSON |
| model | String | 使用的模型 |
| tokens | Integer | Token数 |
| prompt_tokens | Integer | 提示Token数 |
| completion_tokens | Integer | 完成Token数 |
| time_created | LocalDateTime | 创建时间 |
| time_updated | LocalDateTime | 更新时间 |

#### 7. **user_settings** - 用户设置表

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | Long | 主键ID |
| user_qq | String | 用户QQ号（唯一） |
| bot_name | String | Bot显示名称 |
| bot_avatar | String | Bot头像URL或Base64 |
| user_avatar | String | 用户头像URL或Base64 |
| updated_at | LocalDateTime | 更新时间 |

## 快速开始

### 环境要求

- Java 17+
- Node.js 18+
- Maven 3.8+
- MySQL 8.0+（或使用内置 H2 数据库）
- FFmpeg（用于语音转换，可选）

### 1. 克隆项目

```bash
git clone <repository-url>
cd qq-ai-assistant
```

### 2. 配置FFmpeg（可选）

将 FFmpeg 放置在项目根目录：
```
qq-ai-assistant/
├── backend/
├── frontend/
└── ffmpeg-8.1-essentials_build/    <-- FFmpeg目录
    └── bin/
        └── ffmpeg.exe
```

或修改后端代码中的 FFmpeg 路径为你系统的实际路径。

### 3. 配置数据库

#### 方式一：使用内置 H2 数据库（推荐，开发测试用）
无需额外配置，直接启动后端即可。H2 控制台地址：`http://localhost:8081/h2-console`

#### 方式二：使用 MySQL
```bash
# 创建数据库
mysql -u root -p < backend/src/main/resources/init-mysql.sql
```
修改 `backend/src/main/resources/application.yml` 中的数据库连接配置。

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
```

或打包后运行：
```bash
cd backend
mvn clean package
java -jar target/qq-ai-assistant-1.0-SNAPSHOT.jar
```

- 后端服务运行在 `http://localhost:8081`
- **首次启动会自动创建默认管理员账号**，控制台会输出：
  ```
  账号: admin
  密码: admin123
  ```

### 5. 启动前端

#### 开发模式（热更新）
```bash
cd frontend
npm install
npm run dev
```
- 前端服务运行在 `http://localhost:5173`

#### 生产构建
```bash
cd frontend
npm install
npm run build
```
构建产物在 `frontend/dist/` 目录，可部署到任意静态服务器。

### 6. 登录系统

1. 打开前端页面 `http://localhost:5173`
2. 使用默认管理员账号登录：
   - 账号：`admin`
   - 密码：`admin123`
3. 管理员登录后会自动跳转到**管理员页面**，可进行：
   - 数据概览（消息统计、用户统计、趋势图）
   - 用户管理（提权/降权、禁用/启用、删除）
   - 组件控制（AstrBot / NapCat / GPT-SoVITS 启停）

### 7. 配置 NapCat

1. 启动 NapCat 并登录QQ
2. 在 NapCat WebUI 中配置 HTTP 上报：
   - URL: `http://localhost:8081`
   - Token: 配置文件中设置的 token
   - 启用 `reportSelfMessage` 以接收发送的消息

### 8. 启动 AstrBot (可选)

```bash
cd astrbot
python main.py
```

### 9. 启动 GPT-SoVITS (可选)

```bash
cd GPT-SoVITS-v2pro-20250604-nvidia50
runtime\python.exe api_v2.py -a 127.0.0.1 -p 7860
```

### 常用服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| SpringBoot 后端 | 8081 | 主 API 服务 |
| Vue 前端开发 | 5173 | 开发模式 |
| AstrBot | 6185 | AI 对话服务 |
| NapCat | 6099 | QQ 消息监听 |
| GPT-SoVITS API | 7860 | 语音合成 API |
| GPT-SoVITS WebUI | 9872 | 语音合成 WebUI |

### 管理员功能

管理员账号登录后可访问 `/admin` 页面，具备以下功能：
- **数据概览**：总消息数、群聊数、用户数、AI 对话数、近 7 天消息趋势图
- **用户管理**：查看所有用户、修改角色（ADMIN/USER）、禁用/启用账号、删除用户
- **组件控制**：一键启动/停止 AstrBot、NapCat、GPT-SoVITS
- **消息分布**：按文本/图片/视频/文件/音频/语音分类统计

## 使用说明

### 登录与系统控制

1. 打开网页后，点击左侧导航栏的"登录"按钮
2. 在弹出的登录窗口中：
   - 查看 NapCat 二维码并扫码登录
   - 一键启动/停止系统组件
   - 查看项目介绍

### 查看群聊消息

1. 从左侧导航栏的"最近对话"中选择群聊
2. 或手动输入群聊ID并点击"加载消息"
3. 消息会自动加载并显示在聊天界面
4. 收到新消息时，如果在底部会自动滚动，否则保持当前位置

### 消息显示规则

- **用户消息**（发送的消息）- 右对齐显示，蓝色气泡
- **群消息**（接收的消息）- 左对齐显示，灰色气泡
- **AI总结** - 左对齐显示，带有特殊标识
- **图片消息** - 点击可预览
- **语音消息** - QQ样式，点击播放，播放时显示波形动画
- **视频消息** - 显示视频播放器
- **表情消息** - 显示表情标识
- **@消息** - 显示被@的用户
- **回复消息** - 显示回复的消息引用

### 语音消息说明

- 语音消息会自动从 AMR 格式转换为 MP3 格式
- 转换后的语音文件存储在 `backend/uploads/images/voice/` 目录
- 浏览器可直接播放 MP3 格式
- 转换成功后自动删除原始 AMR 文件

### AI对话功能

- 右侧栏为 AstrBot AI 对话界面
- 可以向 AI 询问群聊消息相关问题
- 支持 AI 自动分析群聊内容并生成总结
- 支持多轮对话存储和管理
- 自动过滤工具调用的 JSON 内容

### 消息选择分析

- 点击"选择消息"进入选择模式
- 支持单选/多选切换
- 选中消息后点击"🤖 AI 分析"
- 分析结果会显示在右侧 AstrBot 对话框中
- 自动显示选中的消息摘要

### 头像自定义

- 点击 ⚙️ 设置按钮打开设置面板
- 支持上传本地图片或输入图片 URL
- 可自定义 Bot 名称、Bot 头像、用户头像
- 设置自动保存到后端，跨浏览器同步

## API文档

### 消息相关API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/messages` | 创建消息 |
| GET | `/api/messages/group/{groupId}` | 获取群聊消息 |
| GET | `/api/messages/group/{groupId}/paged` | 分页获取群聊消息 |
| POST | `/api/messages/process` | 处理所有未处理消息 |
| POST | `/api/messages/upload` | 上传文件 |
| POST | `/api/messages/send-with-file` | 发送带文件的消息 |
| GET | `/api/messages/file/{fileId}` | 获取文件信息 |
| DELETE | `/api/messages/file/{fileId}` | 删除文件 |
| POST | `/api/messages/archive` | 手动触发归档 |
| GET | `/api/messages/recent-groups` | 获取最近对话的群聊 |

### AstrBot相关API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/astrbot/callback` | 接收AstrBot消息推送 |
| POST | `/api/astrbot/analyze` | 分析群聊消息（AI总结） |
| GET | `/api/astrbot/status` | 获取AstrBot状态 |
| POST | `/api/astrbot/send` | 发送消息给AstrBot（带对话存储） |

### AstrBot对话管理API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/astrbot/conversations` | 获取对话列表 |
| POST | `/api/astrbot/conversations` | 创建新对话 |
| GET | `/api/astrbot/conversations/{conversationId}` | 获取对话详情 |
| GET | `/api/astrbot/conversations/{conversationId}/messages` | 获取对话消息 |
| PUT | `/api/astrbot/conversations/{conversationId}/title` | 更新对话标题 |
| POST | `/api/astrbot/conversations/{conversationId}/archive` | 归档对话 |
| DELETE | `/api/astrbot/conversations/{conversationId}` | 删除对话 |
| GET | `/api/astrbot/conversations/{conversationId}/stats` | 获取对话统计 |

### 用户设置API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/user/settings?userQq={qq}` | 获取用户设置 |
| POST | `/api/user/settings` | 保存用户设置 |

### NapCat相关API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/` | 接收NapCat消息推送（根路径，核心端点） |
| POST | `/api/napcat` | 接收NapCat消息推送（备用路径） |
| POST | `/api/napcat/webhook` | Webhook备用路径 |

### 系统控制API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/system/component-status` | 获取系统组件状态 |
| GET | `/api/system/health` | 健康检查 |
| POST | `/api/system/start-all` | 启动所有组件 |
| POST | `/api/system/stop-all` | 停止所有组件 |
| POST | `/api/system/start-astrbot` | 启动AstrBot |
| POST | `/api/system/stop-astrbot` | 停止AstrBot |
| POST | `/api/system/start-napcat` | 启动NapCat |
| POST | `/api/system/stop-napcat` | 停止NapCat |
| POST | `/api/system/start-gptsovits` | 启动GPT-SoVITS |
| POST | `/api/system/stop-gptsovits` | 停止GPT-SoVITS |
| GET | `/api/system/napcat/qrcode` | 获取登录二维码路径 |
| GET | `/api/system/napcat/qrcode-image` | 获取二维码图片 |
| GET | `/api/system/napcat/login-status` | 获取登录状态 |

## 配置说明

### 后端配置 (application.yml)

```yaml
server:
  port: 8081
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 100MB

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/qq_chat?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
    username: root
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver
  
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: true

# AstrBot配置
astrbot:
  api-url: http://localhost:6185
  token: your-astrbot-token

# NapCat配置
napcat:
  api-url: http://localhost:6099
  token: your-napcat-token
  webhook-token: your-webhook-token
  self-qq: your-qq-number

# GPT-SoVITS配置
gpt-sovits:
  api-url: http://localhost:7860
  token: your-gpt-sovits-token

# MinIO配置（可选）
minio:
  endpoint: http://localhost:9000
  access-key: admin
  secret-key: password123
  bucket-name: qq-chat-files
  secure: false

# 文件上传配置
file:
  upload:
    max-size: 100MB
    allowed-types: image/*,video/*,audio/*,application/*
  storage:
    temp-path: ./uploads/temp
    archive-path: ./uploads/archive
    local-path: ./uploads/images

# 消息归档配置
archive:
  days-before: 90
  cron: 0 0 2 * * ?  # 每天凌晨2点执行
```

### NapCat配置

在 `napcat/NapCat.Shell/config/onebot11_{qq}.json` 中配置：

```json
{
  "network": {
    "httpClients": [
      {
        "enable": true,
        "name": "qq-chat",
        "url": "http://localhost:8081",
        "reportSelfMessage": true,
        "messagePostFormat": "array",
        "token": "your_token",
        "debug": true
      }
    ]
  }
}
```

## 消息存储策略

### 本地文件存储

```
uploads/images/
├── images/{groupId}/{date}/{uuid}.jpg    # 图片
├── voice/{groupId}/{date}/{uuid}.mp3     # 语音（已转换）
├── video/{groupId}/{date}/{uuid}.mp4     # 视频
└── avatars/group_{groupId}.jpg           # 群头像
```

### 语音转换

- 接收的 AMR 格式语音自动转换为 MP3
- 使用 FFmpeg 进行转换
- 转换成功后删除原始 AMR 文件

### 归档策略

- 自动归档90天前的消息（可配置）
- 每天凌晨2点执行归档任务
- 归档后的消息标记为 archived=true
- 支持手动触发归档

### 多用户支持

- 支持多个QQ账号同时登录
- 每个用户的群聊记录相互独立（通过 owner_qq 字段区分）
- 群头像按群号存储，多用户共享

## 前端特性

### 布局

- 三栏布局：左侧导航栏(240px) + 中间群消息(50%) + 右侧AstrBot(50%)
- 响应式设计，适配不同屏幕尺寸

### 自动刷新

- 消息列表每5秒自动刷新
- 群聊列表每30秒自动刷新
- 智能滚动：仅在底部时自动滚动，阅读历史消息时保持位置

### 登录状态管理

- 使用 localStorage 持久化登录状态
- 支持记住当前选中的群聊

## 开发计划

### 已实现功能

- [x] 基础消息接收与存储
- [x] 群聊消息展示
- [x] 用户头像显示
- [x] 图片消息解析与显示
- [x] 语音消息接收与播放
- [x] AMR转MP3自动转换
- [x] 视频消息支持
- [x] 表情消息支持
- [x] @消息支持
- [x] 回复消息支持
- [x] 左侧导航栏最近对话
- [x] 系统组件一键控制
- [x] NapCat二维码登录
- [x] 消息归档与清理
- [x] 发送消息存储
- [x] 用户-群聊关联关系
- [x] 本地文件存储
- [x] 收到新消息自动滚动（仅在底部时）
- [x] AI消息总结功能
- [x] 多用户/多机器人支持
- [x] 群头像自动下载
- [x] **AstrBot对话存储功能** - 完整的多轮对话存储和管理
  - [x] 对话会话表设计（astrbot_conversations）
  - [x] 对话消息表设计（astrbot_messages）
  - [x] 对话历史持久化存储
  - [x] 对话列表管理和切换
  - [x] 自动标题生成
  - [x] Token使用量统计
  - [x] 对话归档和清理
- [x] **消息选择分析功能** - 选择多条消息进行AI分析
  - [x] 消息选择模式（单选/多选）
  - [x] 选中消息摘要显示
  - [x] AI分析结果展示
- [x] **头像自定义功能** - 支持自定义头像和名称
  - [x] 本地图片上传
  - [x] URL输入支持
  - [x] 后端持久化存储
  - [x] 跨浏览器同步
- [x] **JSON内容过滤** - 自动过滤工具调用的JSON
  - [x] 独立的消息过滤模块
  - [x] 支持多种JSON格式过滤

### 待实现功能

- [ ] 语音合成功能（后端已实现，前端待集成）
- [ ] 消息搜索功能
- [ ] 多账号同时在线支持
- [ ] 消息撤回处理
- [ ] 文件下载功能
- [ ] WebSocket实时推送（替代轮询）

## 常见问题

### 1. 消息不显示

- 检查NapCat是否正确配置HTTP上报
- 检查后端服务是否正常运行
- 检查数据库连接是否正常
- 检查 `self_qq` 配置是否与登录的QQ一致

### 2. 头像不显示

- 检查网络连接是否正常
- QQ头像API需要外网访问
- 检查浏览器控制台是否有错误
- 群头像会自动下载到本地缓存

### 3. 语音播放失败

- 检查 FFmpeg 是否正确安装
- 检查 FFmpeg 路径配置是否正确
- 查看后端日志获取详细错误信息
- 确认 AMR 转 MP3 转换是否成功

### 4. 系统组件启动失败

- 检查组件路径配置是否正确
- 检查端口是否被占用
- 查看后端日志获取详细错误信息
- 确认组件是否已正确安装

### 5. AI总结不工作

- 检查 AstrBot 是否已启动
- 检查 AstrBot 配置是否正确
- 查看后端日志获取API调用错误

## 贡献指南

欢迎提交Issue和Pull Request！

## 许可证

MIT License

## 联系方式

如有问题，请通过以下方式联系：
- 提交GitHub Issue
- 发送邮件至项目维护者

---

**注意**：本项目仅供学习和研究使用，请遵守相关法律法规和QQ用户协议。
