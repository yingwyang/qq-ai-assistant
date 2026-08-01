# 铃音QQ对话 - 跨端智能聊天辅助系统

<p align="center">
  <strong>作者：何彦珏 (He Yanjue)</strong> · 东华理工大学 · 软件学院 · 软件工程<br>
  <a href="mailto:2488130337@qq.com">📧 2488130337@qq.com</a> · 
  <a href="tel:19079444215">📱 19079444215</a>
</p>

基于 **Spring Boot 3 + Vue 3 + AstrBot + NapCat + GPT-SoVITS** 的跨端智能聊天辅助系统。

---

## 项目简介

铃音QQ对话是一个创新的聊天辅助平台：

- 通过 **NapCat** 监听 QQ 消息（含私聊 / 群聊 / 自己发送的消息）
- 经由 **AstrBot** 实现智能消息解释与总结
- **Spring Boot** 后端持久化到 MySQL 数据库
- **Vue 3** 前端提供群聊浏览、消息选择分析、多用户管理、个性化头像等能力
- 集成 **GPT-SoVITS** 实现文本转语音（可选）

---

## 核心功能

| 模块 | 说明 |
|------|------|
| QQ 消息监听与管理 | 实时接收 / 存储 / 归档群聊消息 |
| AI 消息总结与分析 | 通过 AstrBot 实现智能消息分析 |
| 语音合成功能 | 集成 GPT-SoVITS 实现文本转语音（可选） |
| 组件一键控制 | 启动 / 停止 AstrBot、NapCat、GPT-SoVITS |
| NapCat 二维码登录 | 扫码登录，获取机器人QQ号 |
| 消息归档与清理 | 自动归档历史消息，定期清理旧数据 |
| 多媒体消息 | 图片 / 语音 / 视频 / 表情 / @消息 / 回复消息 |
| @消息智能显示 | 自动将 `[CQ:at,qq=xxx]` 解析为 `@昵称`，无昵称时回退为 `@QQ号` |
| 语音自动转换 | AMR → MP3 自动转码，浏览器直接播放 |
| 多用户 / 多QQ支持 | 每个系统用户可绑定多个QQ号，消息互相独立 |
| 群头像自动下载 | 自动下载并缓存群聊头像 |
| 消息选择分析 | 选择多条消息交由 AI 分析并输出摘要 |
| 转发记录折叠展示 | 转发给 AstrBot 的聊天记录以可折叠面板呈现，支持展开查看，@账号名高亮显示 |
| AI 富文本回复 | AstrBot 回复支持 Markdown 渲染：标题、列表、代码块、链接、表格、折叠摘要、目录 |
| 后端插件目录 | 通过 `backend/plugins/` 下的 Groovy 脚本自定义 AI 回复后处理（自动链接、摘要卡片等） |
| 头像自定义 | 上传自定义头像，跨浏览器同步 |
| 管理员后台 | 用户管理、角色切换（ADMIN/USER）、禁用启用、数据统计 |
| **QQ 绑定验证** | 通过向目标 QQ 发送验证码私信，验证用户身份，确保只有 QQ 账号主人才能绑定 |
| **AI 多提供商配置** | 支持配置多个大模型提供商（SiliconFlow/OpenAI 等），可切换、增删，分账号持久化 |
| **智能体管理** | 内置人格管理面板，支持自定义系统提示词、开场白、工具调用配置 |

---

## 技术架构

### 后端技术栈

- **Spring Boot 3.2.x** — 核心框架
- **Spring Security + JWT** — 登录鉴权
- **Spring Data JPA (Hibernate)** — 数据持久化
- **MySQL 8.0+** — 主数据库
- **FFmpeg** — 语音格式转换（AMR → MP3）
- **WebSocket** — 实时消息推送
- **Maven** — 项目构建
- **Thumbnailator** — 图片缩略图处理
- **MinIO**（可选）— 对象存储

### 前端技术栈

- **Vue 3** — 前端框架
- **Vite** — 构建工具
- **Axios** — HTTP 客户端
- **CSS Grid / Flexbox** — 响应式布局

### 第三方组件

- **AstrBot** — AI 聊天与消息总结
- **NapCat** — QQ 消息监听与 OneBot 协议实现
- **GPT-SoVITS** — 语音合成（可选）
- **FFmpeg** — 音视频处理

---

## 项目结构

```
qq-ai-assistant/
├── backend/                            # Spring Boot 后端
│   ├── src/main/java/com/qqai/
│   │   ├── common/                    # 通用工具（头像解析、限流、安全助手）
│   │   ├── config/                    # 配置类（Security / WebSocket / JWT / CORS ...）
│   │   ├── controller/                # API 控制器（15+ 个）
│   │   ├── dto/                       # 数据传输对象（auth / message / user / webhook）
│   │   ├── entity/                    # JPA 实体类（10 张表）
│   │   ├── event/                     # 事件监听（消息保存事件）
│   │   ├── exception/                 # 自定义异常（业务/权限/未找到/未授权）
│   │   ├── plugin/                    # Groovy 插件接口与插件管理器
│   │   ├── repository/                # 数据访问层（10 个 Repository）
│   │   ├── security/                  # 安全模块（JWT 过滤器 / AuthPrincipal）
│   │   ├── service/                   # 业务逻辑层（18+ 个 Service）
│   │   ├── util/                      # 工具类（CQ 码解析）
│   │   ├── websocket/                 # WebSocket 处理器（前端/NapCat）
│   │   └── Application.java           # 启动入口
│   ├── src/main/resources/
│   │   ├── application.yml.example    # 应用配置模板（数据库 / 组件路径 / JWT）
│   │   ├── application-dev.yml.example # 开发环境配置模板
│   │   ├── init-mysql.sql             # 完整数据库初始化脚本
│   │   └── db/migration/              # Flyway 迁移脚本
│   ├── plugins/                       # Groovy 插件脚本目录（热加载）
│   ├── scripts/                       # Python 脚本（SILK 语音转码）
│   ├── uploads/                       # 本地文件存储目录
│   │   ├── images/                    # 图片 / 语音 / 视频
│   │   ├── avatars/                   # 用户/群头像
│   │   ├── tts/                       # TTS 语音合成产物
│   │   ├── temp/                      # 临时文件
│   │   └── archive/                   # 归档文件
│   └── pom.xml                        # Maven 配置
├── frontend/                          # Vue 3 前端
│   ├── src/
│   │   ├── components/                # 组件（AstrBotChat / ChatInterface / Sidebar ...）
│   │   ├── composables/               # 组合式函数（9 个 use* 钩子）
│   │   ├── views/                     # 页面视图（Home / Admin / Login / UserCenter）
│   │   ├── router/                    # 路由配置
│   │   ├── services/                  # API 服务层（统一 fetch 封装）
│   │   ├── utils/                     # 工具函数（消息过滤）
│   │   ├── App.vue                    # 根组件（三栏布局）
│   │   ├── main.js                    # 入口文件
│   │   └── style.css                  # 全局样式
│   ├── public/                        # 静态资源（图标 / SVG）
│   ├── index.html
│   ├── package.json                   # NPM 配置
│   └── vite.config.js                 # Vite 配置
├── .env.example                       # 环境变量模板
├── .gitignore
├── AGENTS.md                          # Codex 协作手册
└── README.md
```

---

## 数据库设计

共 **10 张核心表**，完整 DDL 见 [`backend/src/main/resources/init-mysql.sql`](backend/src/main/resources/init-mysql.sql)。

### 表结构总览

| 表名 | 说明 |
|------|------|
| `users` | 系统登录账号（含 admin / 普通用户） |
| `user_qq_bindings` | 用户 ↔ QQ号 的多对多绑定（一个用户可绑多个QQ） |
| `chat_groups` | 群聊基本信息，含 owner_qq 用于区分用户所属群 |
| `messages` | 核心消息表，承载所有QQ文本 / 图片 / 语音等消息 |
| `file_records` | 文件元数据（图片 / 视频 / 语音 / 文件） |
| `astrbot_conversations` | AstrBot 对话会话表 |
| `astrbot_messages` | AstrBot 对话消息表（按会话聚合） |
| `user_settings` | 用户个性化设置（Bot 名称 / AstrBot Key / **AI 多提供商配置**） |
| `group_read_state` | **群聊阅读进度表**（每个用户/每个群聊的 last_read_time，用于高效计算未读消息数） |
| `audit_logs` | 审计日志表（记录关键操作） |

### 表字段详情

#### 1. users — 用户表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| username | VARCHAR(20) UNIQUE NOT NULL | 登录账号（唯一） |
| nickname | VARCHAR(100) | 昵称 |
| avatar | VARCHAR(255) | 头像URL |
| token | VARCHAR(255) | 认证令牌 |
| password | VARCHAR(255) NOT NULL | 密码（BCrypt加密存储） |
| role | VARCHAR(50) DEFAULT 'USER' | 角色：`ADMIN` / `USER` |
| last_login_time | TIMESTAMP | 最后登录时间 |
| created_at | TIMESTAMP | 创建时间 |
| active | BOOLEAN DEFAULT TRUE | 是否活跃 |

#### 2. user_qq_bindings — 用户QQ绑定表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| user_id | BIGINT NOT NULL | 用户ID（关联 users.id） |
| qq_number | VARCHAR(20) NOT NULL | QQ号 |
| nickname | VARCHAR(100) | QQ昵称 |
| avatar | VARCHAR(255) | QQ头像URL |
| active | BOOLEAN DEFAULT TRUE | 是否激活（解绑= false） |
| is_default | BOOLEAN DEFAULT FALSE | 是否为默认QQ账号 |
| created_at / updated_at | TIMESTAMP | 创建 / 更新时间 |

#### 3. chat_groups — 群聊表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| group_id | VARCHAR(50) NOT NULL | 群号 |
| owner_qq | VARCHAR(20) NOT NULL | 所属登录账号QQ |
| group_name | VARCHAR(200) | 群名称 |
| avatar | VARCHAR(255) | 群头像URL |
| member_count | INT | 成员数量 |
| joined_time | TIMESTAMP | 加入时间 |
| created_at | TIMESTAMP | 创建时间 |
| active | BOOLEAN DEFAULT TRUE | 是否活跃 |

#### 4. messages — 消息表（核心）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| message_id | VARCHAR(100) | QQ消息唯一ID |
| group_id | VARCHAR(50) NOT NULL | 群号 |
| group_name | VARCHAR(200) | 群名称 |
| user_qq | VARCHAR(20) NOT NULL | 发送者QQ |
| user_nickname | VARCHAR(100) | 发送者昵称 |
| message_type | ENUM(8种) DEFAULT 'TEXT' | TEXT / IMAGE / VIDEO / AUDIO / FILE / VOICE / AT / REPLY |
| content | TEXT | 文本内容或文件描述 |
| file_id | VARCHAR(100) | 关联 file_records.file_id |
| at_qq | VARCHAR(20) | @的用户QQ |
| reply_to_message_id | BIGINT | 回复的消息ID |
| ai_summary | TEXT | AI总结内容 |
| send_time | TIMESTAMP NOT NULL | 发送时间 |
| created_at | TIMESTAMP | 入库时间 |
| archived / processed | BOOLEAN | 归档标记 / 处理标记 |
| is_self_message | BOOLEAN DEFAULT FALSE | 是否为登录账号自己发送 |
| self_qq | VARCHAR(20) | 登录账号的QQ号 |

#### 5. file_records — 文件记录表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| file_id | VARCHAR(100) UNIQUE NOT NULL | 文件唯一ID |
| file_name | VARCHAR(500) | 原始文件名 |
| file_type | ENUM('IMAGE','VIDEO','AUDIO','FILE') NOT NULL | 文件类型 |
| file_size | BIGINT | 文件大小（字节） |
| mime_type | VARCHAR(100) | MIME类型 |
| storage_type | ENUM DEFAULT 'MINIO' | LOCAL / MINIO / OSS / COS |
| bucket_name / object_key / url / thumbnail_url | VARCHAR | 存储元信息 |
| width / height / duration | INT | 图片尺寸 / 音视频时长 |
| created_at | TIMESTAMP | 创建时间 |

#### 6. astrbot_conversations — AstrBot 对话会话表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| conversation_id | VARCHAR(64) UNIQUE NOT NULL | 对话唯一ID |
| group_id / user_qq / user_nickname | VARCHAR | 关联的群 / 用户 |
| title | VARCHAR(255) | 对话标题（自动生成） |
| model | VARCHAR(50) | 使用的AI模型 |
| message_count / total_tokens | INT | 统计 |
| context_json | TEXT | 上下文JSON |
| archived | BOOLEAN | 是否归档 |
| time_created / time_updated / time_archived | TIMESTAMP | 时间戳 |

#### 7. astrbot_messages — AstrBot 对话消息表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| message_id | VARCHAR(64) UNIQUE NOT NULL | 消息唯一ID |
| conversation_id | VARCHAR(64) NOT NULL | 所属对话（外键 → conversation_id） |
| role | ENUM('USER','ASSISTANT','SYSTEM') | 消息角色 |
| content / data | TEXT | 消息内容 / 扩展JSON |
| model / tokens / prompt_tokens / completion_tokens | VARCHAR / INT | 模型与 Token 统计 |
| time_created / time_updated | TIMESTAMP | 时间戳 |

#### 8. user_settings — 用户设置表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| user_id | VARCHAR(50) UNIQUE NOT NULL | 用户ID |
| bot_name | VARCHAR(100) | Bot 显示名称 |
| astrbot_api_key | VARCHAR(1000) | AstrBot API Key |
| llm_api_key | VARCHAR(1000) | 当前激活大模型 API Key |
| llm_base_url | VARCHAR(500) | 当前激活大模型 Base URL |
| llm_model | VARCHAR(200) | 当前选中的模型名称 |
| llm_models | TEXT | 模型列表 JSON（字符串数组） |
| providers | TEXT | **多提供商配置 JSON**：`[{name, apiKey, baseUrl}]` |
| updated_at | TIMESTAMP | 更新时间（自动更新） |

- `providers` 字段存储完整的多提供商列表，支持用户切换不同 AI 服务商
- `llm_api_key` / `llm_base_url` 为当前激活提供商的冗余快照，保持向后兼容
- 旧数据自动迁移：若 `providers` 为空但 `llm_api_key` 有值，读取时自动构造单提供商配置

#### 9. group_read_state — 群聊阅读进度表（未读计数基础）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| user_id | BIGINT NOT NULL | 用户ID（关联 users.id） |
| group_id | VARCHAR(50) NOT NULL | 群号（关联 messages.group_id） |
| last_read_time | TIMESTAMP | 用户最后阅读该群聊的时间点（NULL = 从未点进该群） |
| created_at / updated_at | TIMESTAMP | 创建 / 更新时间 |
| UNIQUE(user_id, group_id) | UNIQUE KEY | 同一用户同一群聊只有一条记录 |

- 未读数算法：`COUNT(messages WHERE group_id=xxx AND send_time > last_read_time AND archived=false)`
- 点击侧边栏某群聊项 → 调用 `POST /api/messages/read/{groupId}`，服务端 `INSERT ... ON DUPLICATE KEY UPDATE`，将 `last_read_time` 更新为 NOW。
- 一键全部标已读：`POST /api/messages/read-all` → 批量把当前用户全部群聊的 last_read_time 更新为 NOW。
- 多用户隔离：不同用户之间互不影响未读数。

---

## 快速开始

### 环境要求

- **Java 17+**
- **Node.js 18+**
- **Maven 3.8+**
- **MySQL 8.0+**（推荐使用 docker / 本地安装）
- **FFmpeg**（用于语音转换，可选）

### 步骤一：部署 MySQL 数据库

1. 安装并启动 MySQL 8.0+（默认端口 3306）。
2. 使用 root 用户登录，或创建新用户：

    ```sql
    CREATE USER IF NOT EXISTS 'qq_chat'@'localhost' IDENTIFIED BY 'your_password';
    GRANT ALL PRIVILEGES ON qq_chat.* TO 'qq_chat'@'localhost';
    FLUSH PRIVILEGES;
    ```

3. 执行项目提供的完整初始化脚本：

    ```bash
    # Linux / macOS
    mysql -u root -p < backend/src/main/resources/init-mysql.sql

    # Windows PowerShell
    Get-Content backend/src/main/resources/init-mysql.sql | mysql -u root -p
    ```

    脚本会自动创建数据库 `qq_chat` 并创建上述 **8 张表**，同时插入一条默认管理员账号：`admin / admin123`（BCrypt 加密后的密码已在 SQL 中预置）。

    > 如果你选择不手动初始化数据库，也可以让 JPA 的 `ddl-auto: update` 自动建表；首次启动时 `DataInitializer` 会自动创建默认管理员。

### 步骤二：配置后端

编辑 [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml)，主要配置项如下（均可通过环境变量覆盖）：

```yaml
spring:
  datasource:
    url:      ${DB_URL:jdbc:mysql://localhost:3306/qq_chat?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:your_password_here}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update       # 首次部署建议用 update；生产建议 validate

server:
  port: ${SERVER_PORT:8081}

# AstrBot
astrbot:
  api-url:  ${ASTRBOT_API_URL:http://localhost:6185}
  token:    ${ASTRBOT_TOKEN:xxx}
  data-path: ${ASTRBOT_DATA_PATH:./Astrbot/data}

# NapCat
napcat:
  api-url:       ${NAPCAT_API_URL:http://localhost:6099}
  token:         ${NAPCAT_TOKEN:xxx}
  webhook-token: ${NAPCAT_WEBHOOK_TOKEN:xxx}
  self-qq:       ${NAPCAT_SELF_QQ:你的机器人QQ号}

# GPT-SoVITS (可选)
gpt-sovits:
  api-url: ${GPT_SOVITS_API_URL:http://localhost:7860}
  token:   ${GPT_SOVITS_TOKEN:xxx}

# MinIO (可选)
minio:
  endpoint:    ${MINIO_ENDPOINT:http://localhost:9000}
  access-key:  ${MINIO_ACCESS_KEY:admin}
  secret-key:  ${MINIO_SECRET_KEY:password123}
  bucket-name: ${MINIO_BUCKET:qq-chat-files}

# FFmpeg 路径
ffmpeg:
  path: ${FFMPEG_PATH:ffmpeg}

# 文件上传 / 消息归档
file.upload.max-size: 100MB
archive.days-before:  90
archive.cron:         0 0 2 * * ?    # 每天凌晨 2 点

# JWT（生产环境务必修改 secret）
jwt:
  secret:     ${JWT_SECRET:your-256-bit-secret-key-for-jwt-signing-qq-ai-assistant}
  expiration: ${JWT_EXPIRATION:86400000}
```

### 步骤三：启动后端

```bash
cd backend

# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：先打包，后运行 jar
mvn clean package -DskipTests
java -jar target/qq-ai-assistant-1.0-SNAPSHOT.jar
```

后端服务运行在：**http://localhost:8081**

首次启动时，如果数据库中不存在任何 `ADMIN` 角色的用户，控制台会输出：

```
========================================
  默认管理员账号已创建
  账号: admin
  密码: admin123
========================================
```

### 步骤四：启动前端

```bash
cd frontend
npm install
npm run dev
```

前端开发模式运行在：**http://localhost:5173**

生产构建：

```bash
npm run build
# 产物在 frontend/dist/，可部署到 Nginx / 任意静态服务器
```

### 步骤五：登录系统

1. 打开浏览器，访问前端页面 `http://localhost:5173`。
2. 使用默认管理员账号登录：
    - 账号：`admin`
    - 密码：`admin123`
3. 管理员登录后：
    - 可进入 **管理员页面** — 查看系统统计、管理用户角色 / 启停账号。
    - 进入 **用户主页** — 绑定自己的QQ号，查看 NapCat 推送过来的消息。
4. 首次绑定QQ号后，该QQ号的群聊与消息才会显示在首页。

### 步骤六：配置 NapCat Webhook（关键）

1. 启动 NapCat 并扫码登录机器人账号。
2. 在 NapCat WebUI 中配置 HTTP Webhook 上报：

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
            "token": "与 napcat.webhook-token 保持一致",
            "debug": true
          }
        ]
      }
    }
    ```

3. 其中：
    - `url` 必须指向后端地址（8081 端口）
    - `token` 必须与 `application.yml` 中的 `napcat.webhook-token` 一致
    - `reportSelfMessage: true` 用于接收机器人自己发送的消息

### 步骤七：配置 AstrBot（可选）

```bash
# 在 AstrBot 目录
python main.py
```

并将 `application.yml` 中的 `astrbot.api-url`、`astrbot.token` 指向对应服务。

### 步骤八：配置 GPT-SoVITS（可选）

```bash
cd GPT-SoVITS-v2pro-20250604-nvidia50
runtime\python.exe api_v2.py -a 127.0.0.1 -p 7860
```

---

## 常用服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 后端 | 8081 | 主 API 服务 |
| Vue 前端开发 | 5173 | Vite 开发服务器 |
| AstrBot | 6185 | AI 对话服务 |
| NapCat | 6099 | QQ 消息监听 |
| GPT-SoVITS API | 7860 | 语音合成 API |
| GPT-SoVITS WebUI | 9872 | 语音合成 WebUI |
| MinIO（可选） | 9000 | 对象存储 |

---

## 使用说明

### 登录与系统控制

打开网页后可：
- 查看 NapCat 二维码并扫码登录
- 一键启动 / 停止系统组件（AstrBot / NapCat / GPT-SoVITS）
- 在管理员页面查看系统统计

### 查看群聊消息

- 左侧导航栏的"最近对话"中选择群聊
- 或手动输入群聊ID并点击"加载消息"
- 新消息会自动滚动（仅当你位于消息列表底部时）

### 消息显示规则

- **用户消息**（自己发送）：右对齐，蓝色气泡
- **群消息**（他人发送）：左对齐，灰色气泡
- **AI 总结**：左对齐，带特殊标识
- **图片 / 语音 / 视频 / @ / 回复 / 表情**：各自独立的 UI 样式

### 语音消息

- AMR → MP3 自动转码
- 存储在 `backend/uploads/images/voice/`
- 浏览器可直接播放
- 转码成功后自动删除原始 AMR 文件

### AI 对话 / 消息选择分析

- 右侧栏为 AstrBot 对话界面
- 点击"选择消息"进入多选模式，选中后点击"AI 分析"
- 自动显示选中消息的摘要与 AI 结论

### 头像自定义

- 点击 ⚙️ 设置按钮，上传本地图片或输入图片 URL
- 可自定义 Bot 名称、用户头像
- 设置自动保存到后端 `user_settings` 表，跨浏览器同步

### AI 多提供商配置

- 在设置面板左侧管理多个 AI 提供商（增删、切换）
- 每个提供商独立配置 ID、API Key、Base URL
- 支持从提供商 API 动态获取模型列表
- 支持自定义模型、启用/禁用、设为当前模型
- 配置按账号持久化到 `user_settings.providers` 字段

### 智能体管理

- 设置面板中的"智能体"标签页
- 支持自定义系统提示词、开场白、工具调用配置
- 支持设置默认人格、排序、分类管理

### QQ 账号绑定与验证

为确保安全，绑定 QQ 号时需要进行身份验证：

1. **进入绑定页面**：登录后进入「用户中心」→「个人信息」或直接在首页侧边栏点击头像菜单
2. **输入QQ号**：在绑定表单中输入要绑定的QQ号
3. **发送验证码**：点击「获取验证码」按钮，系统会通过 NapCat 向目标 QQ 号发送验证码私信
4. **验证身份**：在手机 QQ 上查看收到的验证码，输入到系统中完成验证
5. **完成绑定**：验证通过后，该 QQ 号正式绑定到您的账号

**安全机制**：
- 每个 QQ 号只能被一个用户绑定（全局唯一）
- 只有收到验证码并正确输入的人才能完成绑定
- 验证码有效期为 5 分钟
- 每个 QQ 号每天最多发送 10 次验证码

**解绑操作**：
- 在绑定列表中找到要解绑的 QQ 号
- 点击「解绑」按钮，确认后解除绑定关系
- 解绑后该 QQ 号可被其他用户绑定

---

## 消息存储策略

```
backend/uploads/images/
├── images/{groupId}/{date}/{uuid}.jpg    # 图片
├── voice/{groupId}/{date}/{uuid}.mp3     # 语音（已转码为 MP3）
├── video/{groupId}/{date}/{uuid}.mp4     # 视频
└── avatars/group_{groupId}.jpg           # 群头像
```

默认全部为 **本地存储**；可通过 `minio.*` 配置切到对象存储（需要已启动 MinIO 实例）。

---

## 前端特性

- **三栏布局**：左（导航 / 最近对话） + 中（消息） + 右（AI 对话 / 设置）
- **消息列表每 5 秒自动刷新**，群聊列表每 30 秒刷新
- **智能滚动**：仅当你在消息底部时自动滚动；阅读历史消息时不打扰
- **JWT 登录**：Token 存储于 localStorage，过期后自动跳转登录页

---

## 常见问题

### 1. 消息不显示

- 检查 NapCat 是否配置了 HTTP 上报，并可访问 `http://localhost:8081`
- 检查 `napcat.webhook-token` 是否与 NapCat 配置一致
- 登录账号后，务必在"个人设置 → QQ账号绑定"中绑定对应 QQ 号
- 检查数据库连接是否正常，`messages` 表是否在写入

### 2. 头像不显示

- QQ 头像 API 需要外网访问权限
- 群头像会自动下载到本地缓存（`uploads/images/avatars/`）

### 3. 语音播放失败

- 检查 FFmpeg 路径配置（`ffmpeg.path`）是否正确
- FFmpeg 必须可执行（Windows 注意 `.exe` 路径，Linux/macOS 使用绝对路径）
- 查看后端日志，搜索 `FFmpeg` 或 `AMR` 关键字

### 4. 验证码显示乱码

- **原因**：发送验证码私信时未指定 UTF-8 编码，导致中文变为乱码
- **解决方案**：此问题已在版本更新中修复（`NapCatService.java` 中 `StringEntity` 添加 UTF-8 编码）
- 若仍遇到此问题，请更新代码并重新部署

### 5. QQ 绑定提示"已被其他用户绑定"

- **原因**：同一个 QQ 号在系统中只能被一个用户绑定（全局唯一约束）
- **解决方案**：
  - 确认该 QQ 号是否已被其他用户绑定
  - 联系管理员查询绑定记录，确认是否需要解绑后重新绑定
  - 检查数据库中是否存在残留的 `active=true` 的绑定记录

### 6. 系统组件启动失败

- 检查组件路径 / 端口是否被占用
- NapCat 需登录至少一次，生成配置目录
- AstrBot 需要依赖环境（Python 3.9+ / Poetry / venv 等）

### 7. AI 总结不工作

- 检查 AstrBot 是否正常启动
- 检查 `astrbot.token` 与 AstrBot 配置中设置的 token 是否一致
- 查看后端日志中 `/api/astrbot/*` 相关错误

---

## 部署建议（生产环境）

1. **数据库**：使用 MySQL 8.0，启用 `utf8mb4`，将 `ddl-auto` 从 `update` 改为 `validate`，避免 JPA 意外修改表结构。
2. **JWT Secret**：务必通过环境变量 `JWT_SECRET` 覆盖默认值。
3. **HTTPS**：生产部署建议使用 Nginx 反向代理，启用 HTTPS。
4. **文件存储**：文件量较大时，建议使用 MinIO / OSS / COS 替换本地存储。
5. **归档任务**：`archive.cron: 0 0 2 * * ?` 每天凌晨 2 点执行归档，可按需调整。

---

## 贡献指南

欢迎提交 Issue 与 Pull Request！

---

## 作者信息

| 项目 | 内容 |
|------|------|
| 姓名 | 何彦珏 (He Yanjue) |
| 学校 | 东华理工大学 · 软件学院 · 软件工程 |
| 求职意向 | Java 开发工程师 / 后端开发工程师 |
| 邮箱 | 2488130337@qq.com |
| 电话 | 19079444215 |

详细简历请查看 [RESUME.md](RESUME.md)。

---

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

---

**注意**：本项目仅供学习和研究使用，请遵守相关法律法规与 QQ 用户协议。
