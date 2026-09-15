# 铃音QQ对话 - 跨端智能聊天辅助系统

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
| **积分系统** | 每日签到、新用户奖励、AI 对话按 Token 计费（模型分级费率 × 阶梯折扣 × tier 折扣），月卡每日登录奖励；管理员可配置积分规则、调整用户积分 |
| **订阅与月卡体系** | 直购积分套餐（Lite / Pro / ProPlus / Ultra / Mega）+ 月卡（小月卡 ¥30 / 大月卡 ¥68），月卡叠加升级（小+大=ALL 全功能版），订单购买、取消、退款、纠纷处理；管理员可补单、作废、退款、审批 |
| **群类型识别** | AI 自动识别群聊类型（游戏/学习/工作/兴趣/生活/社交），按群类型定制 AI 摘要策略 |
| **文本转语音（TTS）** | 集成 GPT-SoVITS，支持多音色切换，AI 回复一键生成语音（可选） |
| **系统管理后台** | 数据概览、用户管理、组件控制、媒体管理、配置管理、数据维护、系统日志、审计 |
| **删除群聊会话** | 右键群聊可彻底删除该群会话（消息 + 媒体文件 + 群记录 + 已读状态，不可恢复） |

---

## 技术架构

### 后端技术栈

- **Spring Boot 3.2.x** — 核心框架
- **Spring Security + JWT** — 登录鉴权
- **Spring Data JPA (Hibernate)** — 数据持久化
- **MySQL 8.0+** — 主数据库
- **RabbitMQ 3.12+** — 消息队列（异步媒体下载 / AI 分析 / 消息广播）
- **FFmpeg** — 语音格式转换（AMR → MP3）
- **WebSocket** — 实时消息推送
- **Maven** — 项目构建
- **Thumbnailator** — 图片缩略图处理
- **MinIO**（可选）— 对象存储
- **Groovy** — 后端插件脚本引擎（AI 回复后处理，热加载）
- **Caffeine** — JWT 黑名单本地缓存
- **SQLite** — 本地轻量存储

### 前端技术栈

- **Vue 3** — 前端框架
- **Vite** — 构建工具
- **原生 fetch 封装** — HTTP 客户端（`services/api.js` 统一请求 / 401 自动登出）
- **CSS Grid / Flexbox** — 响应式布局

### 第三方组件

- **AstrBot** — AI 聊天与消息总结
- **NapCat** — QQ 消息监听与 OneBot 协议实现
- **RabbitMQ** — 消息队列（Webhook 异步化 / 媒体下载 / AI 分析 / 广播解耦）
- **GPT-SoVITS** — 语音合成（可选）
- **FFmpeg** — 音视频处理

### RabbitMQ 消息队列架构

后端通过 RabbitMQ 实现异步解耦，包含 3 条 Direct Exchange 队列 + 1 条 Fanout Exchange 队列，每条业务队列配备独立死信队列（DLQ）：

| Exchange | 类型 | 队列 | Prefetch | 消费者 | DLQ | 说明 |
|----------|------|------|----------|--------|-----|------|
| `qqai.media` | Direct | `media.download.queue` | 5 | MediaDownloadConsumer | `media.download.dlq` | 图片/视频异步下载 |
| `qqai.voice` | Direct | `voice.transcode.queue` | 2 | VoiceTranscodeConsumer | `voice.transcode.dlq` | 语音 SILK→MP3 转码 |
| `qqai.ai` | Direct | `ai.analysis.queue` | 3 | AiAnalysisConsumer | `ai.analysis.dlq` | AstrBot AI 摘要生成 |
| `qqai.broadcast` | Fanout | `broadcast.queue` | 3 | BroadcastConsumer | — | WebSocket 消息广播 |

**重试策略**：所有业务消费者使用 RetryTemplate（3 次指数退避 1s→2s→4s），耗尽后 `RejectAndDontRequeueRecoverer` 触发死信路由到 DLQ。DLQ 消费者做兜底处理（回填失败占位符或日志告警）。

---

## 项目结构

```
qq-ai-assistant/
├── backend/                            # Spring Boot 后端
│   ├── src/main/java/com/qqai/
│   │   ├── common/                    # 通用工具（头像解析、限流、安全助手）
│   │   ├── config/                    # 配置类（Security / WebSocket / JWT / CORS / RabbitMQ / GlobalExceptionHandler ...）
│   │   ├── consumer/                  # RabbitMQ 消费者（媒体下载 / 语音转码 / AI 分析 / 广播）
│   │   ├── controller/                # API 控制器（21 个）
│   │   ├── dto/                       # 数据传输对象（auth / message / user / webhook）
│   │   ├── entity/                    # JPA 实体类（16 张表）
│   │   ├── exception/                 # 自定义异常（业务/权限/未找到/未授权 + ErrorCode 体系）
│   │   ├── plugin/                    # Groovy 插件接口与插件管理器
│   │   ├── repository/                # 数据访问层（16 个 Repository）
│   │   ├── security/                  # 安全模块（JWT + TokenVersion 批量失效 + TraceId 链路追踪）
│   │   ├── service/                   # 业务逻辑层（29 个 Service）
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
│   │   ├── components/                # 组件（AstrBotChat / ChatInterface / Sidebar / PersonaManager / RichTextRenderer ...）
│   │   ├── composables/               # 组合式函数（20 个 use* 钩子）
│   │   ├── views/                     # 页面视图（Home / Admin / Login / UserCenter / Docs）
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
├── docker-compose.yml                 # Docker 一键部署（MySQL + RabbitMQ）
├── doc/                               # 项目文档（Wiki / README / 用户手册）
└── README.md
```

---

## 数据库设计

共 **16 张表**（其中 9 张由 `init-mysql.sql` 初始化，其余由 JPA `ddl-auto` 首次启动自动创建），完整 DDL 见 [`backend/src/main/resources/init-mysql.sql`](backend/src/main/resources/init-mysql.sql)。

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
| `credit_rule` | **积分规则配置表**（单行：新用户奖励 / 签到积分 / Token 计费 / 套餐价格 / 月卡折扣 / 阶梯折扣 / TTS 计费） |
| `credit_transaction` | **积分流水表**（类型 / 增减方向 / 金额 / 余额快照） |
| `user_credit` | **用户积分表**（余额 / 累计收入支出 / 消费封禁 / 订阅等级与到期时间） |
| `sign_in_record` | **签到记录表**（每日一次，连续签到天数） |
| `subscription_order` | **订阅订单表**（套餐 / 价格 / 支付状态 / 退款 / 纠纷） |
| `monthly_bonus_record` | **月卡每日奖励记录表**（月卡用户每日登录领取额外积分，幂等防重） |
| `audit_log` | 审计日志表（记录关键管理操作） |

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
| email | VARCHAR(255) | 邮箱 |
| token_version | INT DEFAULT 0 | Token 版本号（改密/登出后 +1 使旧 JWT 失效） |
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
| message_type | ENUM(10种) DEFAULT 'TEXT' | TEXT / IMAGE / VIDEO / AUDIO / FILE / VOICE / AT / REPLY / FORWARD / APP |
| content | TEXT | 文本内容或文件描述 |
| file_id | VARCHAR(100) | 关联 file_records.file_id |
| at_qq | VARCHAR(20) | @的用户QQ |
| reply_to_message_id | BIGINT | 回复的消息ID |
| reply_to_nickname | VARCHAR(100) | 被引用消息发送者昵称 |
| reply_to_content | TEXT | 被引用消息内容摘要 |
| forward_content | TEXT | 合并转发消息原始内容（JSON） |
| mini_app_content | TEXT | 小程序分享消息原始内容（JSON） |
| ai_summary | TEXT | AI总结内容 |
| send_time | TIMESTAMP NOT NULL | 发送时间 |
| raw_msg_time | BIGINT | OneBot 原始 Unix 时间戳（秒） |
| msg_seq | INT | OneBot message_seq（时间接近时二次排序） |
| server_recv_ms | BIGINT | 服务端收到消息时的毫秒级 epoch |
| created_at | TIMESTAMP | 入库时间 |
| archived / processed | BOOLEAN | 归档标记 / 处理标记 |
| media_pending | BOOLEAN DEFAULT FALSE | 媒体是否待异步下载/转码（true=前端显示加载占位符） |
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

#### 10. audit_log — 审计日志表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| username | VARCHAR(50) | 操作人 |
| action | VARCHAR(50) | 操作类型（ROLE_CHANGE / COMPONENT_START / FILE_DELETE / USER_DELETE 等） |
| target | VARCHAR(255) | 操作目标 |
| result | VARCHAR(20) | 操作结果：SUCCESS / FAILURE |
| detail | VARCHAR(1000) | 详细信息 |
| timestamp | TIMESTAMP | 操作时间 |

#### 11. credit_rule — 积分规则配置表（单行）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | BIGINT PK | 1 | 固定单行 |
| new_user_bonus | INT | 500 | 新用户注册奖励积分 |
| sign_in_points | INT | 150 | 每日签到积分 |
| token_unit | INT | 1000 | 计费 Token 单位（每 1000 token 计费一次） |
| prompt_rate / completion_rate | INT | 2 / 4 | 输入 / 输出 Token 单价（积分） |
| min_cost | INT | 5 | 单次对话最低消耗积分 |
| default_cost_per_msg | INT | 10 | 消息默认消耗积分 |
| admin_free | BOOLEAN | true | 管理员是否免积分 |
| plan_lite_price / plan_lite_credit | DECIMAL / INT | 9.9 / 2000 | Lite 套餐价格（元）/ 赠送积分 |
| plan_pro_price / plan_pro_credit | DECIMAL / INT | 59 / 4000 | Pro 套餐价格 / 赠送积分 |
| plan_proplus_price / plan_proplus_credit | DECIMAL / INT | 219 / 12000 | ProPlus 套餐价格 / 赠送积分 |
| plan_ultra_price / plan_ultra_credit | DECIMAL / INT | 629 / 40000 | Ultra 套餐价格 / 赠送积分 |
| plan_duration_days | INT | 30 | 套餐有效期（天） |
| model_rates | VARCHAR(2000) | `{"default":1.0}` | 模型分级费率 JSON（不同模型不同倍率） |
| image_extra_cost | INT | 5 | 多模态每张图片额外消耗 |
| analyze_base_cost | INT | 10 | AI 群分析基础费用 |
| analyze_cost_per_msg | INT | 1 | 每条消息增量分析费 |
| analyze_type_rates | VARCHAR(2000) | `{"default":1.0}` | 分析类型倍率 JSON |
| tts_chars_per_credit | INT | 50 | TTS 每 50 字符扣 1 积分 |
| tts_min_cost | INT | 2 | TTS 最小消耗 |
| monthly_free_quota | INT | 0 | 每月免费 AI 对话次数（0=不限） |
| overtax_rate | DOUBLE | 1.5 | 超额费率倍数 |
| small_month_card_discount | DOUBLE | 0.9 | 小月卡折扣 |
| large_month_card_discount | DOUBLE | 0.8 | 大月卡折扣 |
| all_tier_discount | DOUBLE | 0.7 | ALL 状态折扣 |
| context_extra_cost_per_msg | INT | 1 | 上下文每条消息增量费 |
| context_free_msg_count | INT | 10 | 前 N 条上下文免费 |
| daily_cap_cost | INT | 0 | 单日消费上限（0=不限） |
| tiered_discount_thresholds | VARCHAR(2000) | `{"1000":0.95,"5000":0.9,"20000":0.85}` | 月度阶梯折扣 JSON |
| created_at / updated_at | TIMESTAMP | — | 创建 / 更新时间 |

**AI 聊天计费公式**：
```
baseCost = max(minCost, ceil((prompt×promptRate + completion×completionRate) / tokenUnit))
rawCost  = baseCost + imageExtra(图片数×imageExtraCost) + contextExtra(超免费条数×contextExtraCostPerMsg)
finalCost = ceil(rawCost × modelRate × overtaxRate × tierDiscount × tieredDiscount)
```
叠加免费优先级：管理员免费 > 月度免费配额 > 每日封顶免费 > 实际扣费。

#### 12. credit_transaction — 积分流水表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| user_id | BIGINT NOT NULL | 用户ID |
| type | VARCHAR(30) | 流水类型（SIGN_IN / PURCHASE / CONSUME / ADMIN_ADJUST 等） |
| direction | VARCHAR(10) | 增减方向：INCOME / EXPENSE |
| amount | INT NOT NULL | 变动积分 |
| balance_after | INT NOT NULL | 变动后余额快照 |
| remark | VARCHAR(500) | 备注 |
| related_id | VARCHAR(100) | 关联业务ID（如订单号） |
| admin_user_id | BIGINT | 操作管理员ID |
| created_at | TIMESTAMP | 创建时间 |

#### 13. user_credit — 用户积分表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| user_id | BIGINT UNIQUE NOT NULL | 用户ID（唯一） |
| balance | INT DEFAULT 0 | 当前积分余额 |
| total_earned / total_spent | INT | 累计收入 / 支出 |
| consumption_banned | BOOLEAN | 是否禁止消费 |
| subscription_tier | VARCHAR(20) DEFAULT 'FREE' | 订阅等级：FREE / LITE / PRO / PROPLUS / ULTRA / MEGA / SMALL_MONTH_CARD / LARGE_MONTH_CARD / ALL |
| subscription_expires_at | TIMESTAMP | 订阅到期时间 |
| version | INT | 乐观锁版本号 |
| created_at / updated_at | TIMESTAMP | 创建 / 更新时间 |

#### 14. sign_in_record — 签到记录表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| user_id | BIGINT NOT NULL | 用户ID |
| sign_in_date | DATE NOT NULL | 签到日期（UNIQUE(user_id, sign_in_date)） |
| points | INT NOT NULL | 获得积分 |
| streak_days | INT NOT NULL | 连续签到天数 |
| created_at | TIMESTAMP | 创建时间 |

#### 15. subscription_order — 订阅订单表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| order_no | VARCHAR(50) UNIQUE NOT NULL | 订单号 |
| user_id | BIGINT NOT NULL | 用户ID |
| plan_tier | VARCHAR(20) NOT NULL | 套餐等级：LITE / PRO / PROPLUS / ULTRA / MEGA / SMALL_MONTH_CARD / LARGE_MONTH_CARD |
| price | DECIMAL(10,2) NOT NULL | 订单金额（元） |
| credit_amount | INT NOT NULL | 赠送积分 |
| duration_days | INT NOT NULL | 有效天数 |
| status | VARCHAR(20) | PENDING / PAID / DISPUTED / PENDING_REFUND / REFUNDED / CANCELLED / EXPIRED |
| payment_method / payment_transaction_id | VARCHAR | 支付方式 / 支付流水号 |
| paid_at / expires_at / refunded_at | TIMESTAMP | 支付 / 到期 / 退款时间 |
| refund_amount / refund_reason | DECIMAL / VARCHAR(500) | 退款金额 / 原因 |
| client_ip / user_agent | VARCHAR | 下单客户端信息 |
| created_at / updated_at | TIMESTAMP | 创建 / 更新时间 |

#### 16. monthly_bonus_record — 月卡每日奖励记录表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键ID |
| user_id | BIGINT NOT NULL | 用户ID |
| bonus_date | DATE NOT NULL | 奖励日期（UNIQUE(user_id, bonus_date) 幂等防重） |
| created_at | TIMESTAMP | 创建时间 |

> 月卡用户每日首次登录时系统自动发放额外积分，本表记录已发放记录避免重复。

---

## 快速开始

### 环境要求

- **Java 17+**
- **Node.js 18+**
- **Maven 3.8+**
- **MySQL 8.0+**（推荐使用 docker / 本地安装）
- **RabbitMQ 3.12+**（推荐使用 docker / 本地安装）
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

    脚本会自动创建数据库 `qq_chat` 并创建 **9 张基础表**（users / user_qq_bindings / chat_groups / file_records / messages / astrbot_conversations / astrbot_messages / user_settings / group_read_state），其余 **7 张表**（audit_log / credit_rule / credit_transaction / user_credit / sign_in_record / subscription_order / monthly_bonus_record）由 JPA 在首次启动时自动创建。SQL 中不再预置任何默认账号密码。

    > 如果你选择不手动初始化数据库，也可以让 JPA 的 `ddl-auto: update` 自动建表；首次启动时 `DataInitializer` 会自动创建初始管理员（密码来自环境变量 `ADMIN_INIT_PASSWORD`，未设置时生成随机密码并打印到启动日志，请登录后立即修改）。

### 步骤二：部署 RabbitMQ 消息队列

RabbitMQ 用于异步处理媒体下载、语音转码、AI 分析和消息广播，是后端启动的必要依赖。

**方式一：Docker 部署（推荐）**

```bash
docker run -d --name rabbitmq \
  -p 5672:5672 \
  -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=guest \
  -e RABBITMQ_DEFAULT_PASS=guest \
  --restart unless-stopped \
  rabbitmq:3.12-management
```

**方式二：本地安装**

- Windows：从 [rabbitmq.com](https://www.rabbitmq.com/install-windows.html) 下载安装包，先安装 Erlang/OTP，再安装 RabbitMQ
- macOS：`brew install rabbitmq && brew services start rabbitmq`
- Linux：`sudo apt install rabbitmq-server && sudo systemctl enable --now rabbitmq-server`

**验证**：打开浏览器访问管理界面 **http://localhost:15672**，默认账号 `guest / guest`。

> 也可使用项目根目录的 `docker-compose.yml` 一键启动 MySQL + RabbitMQ：`docker compose up -d`

### 步骤三：配置后端

> **推荐做法**：复制根目录 `.env.example` 为 `backend/.env` 并填入真实值 —— 应用启动时用 `spring-dotenv` 读取该文件。仓库内**不再保存任何真实密钥**，且 `SecurityPropertiesValidator` 会在启动时强校验 `jwt.secret`、`napcat.token`、`napcat.webhook-token`、`minio.*`，缺失或过短将**直接拒绝启动**。
>
> 必填：`JWT_SECRET`（≥32 字符）、`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`NAPCAT_TOKEN`、`NAPCAT_WEBHOOK_TOKEN`、`ASTRBOT_TOKEN`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`
> 常用可选：`ADMIN_INIT_PASSWORD`（初始管理员密码）、`CORS_ALLOWED_ORIGINS`、`ASTRBOT_SUMMARY_MODEL`、`FILE_DOWNLOAD_MAX_MB` / `FILE_DOWNLOAD_MAX_VIDEO_MB`

`backend/src/main/resources/application.yml` 中的配置项如下（值均来自环境变量）：

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
  rabbitmq:
    host:           ${RABBITMQ_HOST:localhost}
    port:           ${RABBITMQ_PORT:5672}
    username:       ${RABBITMQ_USERNAME:guest}
    password:       ${RABBITMQ_PASSWORD:guest}
    virtual-host:   ${RABBITMQ_VHOST:/}
    # 连接失败时启动失败（不静默降级）
    listener:
      simple:
        retry:
          enabled:           true
          max-attempts:      3
          initial-interval:  1000ms

server:
  port: ${SERVER_PORT:8081}

# AstrBot
astrbot:
  api-url:  ${ASTRBOT_API_URL:http://localhost:6185}
  token:    ${ASTRBOT_TOKEN:xxx}
  data-path: ${ASTRBOT_DATA_PATH:./Astrbot/data}

# NapCat
napcat:
  api-url:       ${NAPCAT_API_URL:http://localhost:6100}
  token:         ${NAPCAT_TOKEN:xxx}
  webhook-token: ${NAPCAT_WEBHOOK_TOKEN:xxx}
  self-qq:       ${NAPCAT_SELF_QQ:你的机器人QQ号}

# GPT-SoVITS (可选)
gpt-sovits:
  api-url: ${GPT_SOVITS_API_URL:http://localhost:8000}
  token:   ${GPT_SOVITS_TOKEN:xxx}

# MinIO (可选)
minio:
  endpoint:    ${MINIO_ENDPOINT:http://localhost:9000}
  access-key:  ${MINIO_ACCESS_KEY:}
  secret-key:  ${MINIO_SECRET_KEY:}
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

### 步骤四：启动后端

```bash
cd backend

# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：先打包，后运行 jar
mvn clean package -DskipTests
java -jar target/qq-ai-assistant-1.0-SNAPSHOT.jar
```

后端服务运行在：**http://localhost:8081**

首次启动时，如果数据库中不存在任何 `ADMIN` 角色的用户，控制台会输出初始管理员账号与**随机生成的密码**（或你在 `.env` 中通过 `ADMIN_INIT_PASSWORD` 指定的密码）：

```
===============================================================
  【安全】已创建初始管理员账号 admin,初始密码: <随机密码>
  【安全】请立即登录并在个人中心修改该密码。
===============================================================
```

### 步骤六：启动前端

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

### 步骤七：登录系统

1. 打开浏览器，访问前端页面 `http://localhost:5173`。
2. 使用初始管理员账号登录（账号 `admin`，密码见首次启动日志；或注册普通账号使用）。
3. 管理员登录后：
    - 可进入 **管理员页面** — 查看系统统计、管理用户角色 / 启停账号。
    - 进入 **用户主页** — 绑定自己的QQ号，查看 NapCat 推送过来的消息。
4. 首次绑定QQ号后，该QQ号的群聊与消息才会显示在首页。

### 步骤八：配置 NapCat Webhook（关键）

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

### 步骤九：配置 AstrBot（可选）

```bash
# 在 AstrBot 目录
python main.py
```

并将 `application.yml` 中的 `astrbot.api-url`、`astrbot.token` 指向对应服务。

### 步骤十：配置 GPT-SoVITS（可选）

```bash
cd GPT-SoVITS-v2pro-20250604-nvidia50
runtime\python.exe api_v2.py -a 127.0.0.1 -p 8000
```

---

## 常用服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 后端 | 8081 | 主 API 服务 |
| Vue 前端开发 | 5173 | Vite 开发服务器 |
| MySQL | 3306 | 主数据库 |
| RabbitMQ AMQP | 5672 | 消息队列（后端连接） |
| RabbitMQ 管理界面 | 15672 | Web 管理控制台（guest/guest） |
| AstrBot | 6185 | AI 对话服务 |
| NapCat API | 6100 | OneBot API（后端连接） |
| NapCat WebUI | 6099 | 扫码登录 / 配置界面 |
| GPT-SoVITS API | 8000 | 语音合成 API |
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

### 积分与签到

- 新用户注册自动获得奖励积分（默认 500，可在后台积分规则中调整）
- 每日可在用户中心 **签到** 领取积分（默认 150，连续签到有记录）
- AI 对话按 Token 计费（输入 / 输出单价、最低消费均可配置），余额不足时无法发起对话
- 积分明细可在「用量管理」查看，管理员可在后台调整积分 / 配置规则

### 订阅与订单

- 提供 Lite / Pro / ProPlus / Ultra 四档订阅套餐，购买后获得对应积分并升级权益
- 在「订阅管理」中查看套餐、发起购买、查看订单、申请退款
- 管理员可在后台进行 **补单 / 作废 / 退款** 操作

### 文本转语音（TTS）

- 在 AI 回复下方点击 **语音生成**，通过 GPT-SoVITS 将文本合成为语音
- 可在设置中切换 TTS 音色（默认 Kisaki / 加藤惠，可扩展）
- 需要已启动 GPT-SoVITS 服务

### 系统管理后台（管理员）

后台提供以下板块：

| 板块 | 说明 |
|------|------|
| 数据概览 | 系统统计、消息趋势、AI 对话趋势 |
| 用户管理 | 查看用户、修改角色（ADMIN/USER）、启用/禁用、删除 |
| 组件状态与控制 | 启动 / 停止 AstrBot、NapCat、GPT-SoVITS |
| 媒体文件管理 | 按类型浏览、预览、批量删除本地媒体文件 |
| 配置管理 | 系统级配置项查看与修改 |
| 数据维护 | 数据库备份 / 恢复等维护操作 |
| 系统日志 | 后端日志在线查看 |
| 积分规则配置 | 调整签到积分、Token 计费、套餐价格等 |
| 用户积分 | 查看用户积分、调整积分（增加 / 扣除） |
| 积分流水 | 全部积分流水查询与导出 |
| 订单管理 | 订单查询、补单、作废、退款 |

### 删除群聊会话

- 在左侧群聊列表右键 → **删除群聊会话**，可彻底删除该 QQ 下该群的全部消息、媒体文件、群记录与已读状态
- 属于硬删除，操作不可恢复，适用于已退出群聊的清理

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
- **消息实时推送**：WebSocket 推送新消息，断线时每 10 秒兜底轮询；群聊列表每 5 秒自动刷新
- **智能滚动**：仅当你在消息底部时自动滚动；阅读历史消息时不打扰
- **JWT 登录**：Token 存储于 localStorage，过期后自动跳转登录页
- **未读计数**：按用户 / 群记录最后阅读时间，侧边栏实时展示未读消息数（>99 显示 99+）
- **右键菜单**：群聊右键可删除指定类型消息或整个群聊会话
- **深色主题**：支持浅色 / 深色模式切换，自动保存

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

### 7. 媒体（图片/语音/视频）没有入库

完整排查手册：[doc/MEDIA_TROUBLESHOOTING.md](MEDIA_TROUBLESHOOTING.md)（含 2026-09-15 视频故障复盘）

- 快速判断：查 `messages.content` —— `/images/...` 为成功，`[视频已过期]` 之类为失败占位符
- 后端日志关键词：`媒体下载完成` / `CQ 直连获取失败` / `视频原片不可用,已落盘 QQ 缩略图` / `【DLQ】媒体下载彻底失败`
- 常见原因：
  - **QQ 本地没有视频原片**（默认只保留缩略图）→ 系统会落盘缩略图作预览；需要可播放原片请先在 QQ 中播放一次，或升级 NapCat 后重发
  - NapCat 未运行（6100）或 `NAPCAT_TOKEN` / `NAPCAT_WEBHOOK_TOKEN` 与 NapCat 配置不一致
  - 媒体体积超过 `file.download.max-size-mb`（默认 100）/ `max-video-size-mb`（默认 300）
- 需要抓取 NapCat 原始事件载荷时，临时开启 `app.debug.log-media-payload=true`（含聊天内容，排查完请关闭）

### 8. 消息没有 AI 摘要 / 日志刷 `【DLQ】AI分析彻底失败`

完整排查手册：[doc/AI_SUMMARY_TROUBLESHOOTING.md](AI_SUMMARY_TROUBLESHOOTING.md)（含 2026-09-15 故障复盘）

- 快速判断：`SELECT COUNT(*) FROM messages WHERE processed = 0;` 长期不减少即为异常
- 后端日志关键词：`AI分析完成` / `AI摘要响应为空` / `AstrBot 返回错误` / `HttpHostConnectException` / `【DLQ】AI分析`
- 常见原因：
  - **AstrBot 未运行**（6185 端口未监听）→ 启动 AstrBot
  - 请求缺少必填字段 `username` 或返回格式（SSE）解析失败 → 见手册 §2
  - 摘要用于 `summary.structured` 模板，模型可用 `ASTRBOT_SUMMARY_MODEL` 指定（留空用 AstrBot 默认模型）
- 批量补历史摘要：管理员调用 `POST /api/messages/process`（会把 `processed=false` 的消息重新投递，注意会真实调用模型、耗时与额度）

### 7. AI 总结不工作

- 检查 AstrBot 是否正常启动
- 检查 `astrbot.token` 与 AstrBot 配置中设置的 token 是否一致
- 查看后端日志中 `/api/astrbot/*` 相关错误

### 8. TTS 语音生成失败

- 检查 GPT-SoVITS 是否启动，`gpt-sovits.api-url` 配置是否正确
- 检查参考音频与模型路径是否有效
- 查看后端日志中 `/api/system/tts` 相关错误

### 9. 积分余额不足 / 签到失败

- AI 对话会按 Token 消耗积分，余额不足时无法发起对话
- 签到每天一次，重复签到会提示"今日已签到"
- 可在用户中心「用量管理」查看积分流水；异常请联系管理员

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

## 许可证

MIT License

---

**注意**：本项目仅供学习和研究使用，请遵守相关法律法规与 QQ 用户协议。
