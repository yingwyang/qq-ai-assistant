-- =====================================================================
-- 铃音QQ对话系统 - MySQL 数据库完整初始化脚本
-- 数据库名: qq_chat
-- 字符集  : utf8mb4
-- 适用版本: MySQL 8.0+
-- =====================================================================

-- =====================================================================
-- 1. 用户表 (users) - 存储系统登录账号
-- =====================================================================
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    username        VARCHAR(20)     NOT NULL UNIQUE     COMMENT '用户名/账号（唯一）',
    nickname        VARCHAR(100)                        COMMENT '昵称',
    avatar          VARCHAR(255)                        COMMENT '头像URL',
    token           VARCHAR(255)                        COMMENT '认证令牌',
    password        VARCHAR(255)    NOT NULL            COMMENT '密码（BCrypt加密）',
    role            VARCHAR(50)     DEFAULT 'USER'      COMMENT '用户角色：ADMIN / USER',
    last_login_time TIMESTAMP       NULL                COMMENT '最后登录时间',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    active          BOOLEAN         DEFAULT TRUE        COMMENT '是否活跃',
    INDEX idx_username (username),
    INDEX idx_nickname (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- =====================================================================
-- 2. 用户QQ账号绑定表 (user_qq_bindings) - 支持一个用户绑定多个QQ
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_qq_bindings (
    id          BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id     BIGINT          NOT NULL            COMMENT '用户ID（关联 users.id）',
    qq_number   VARCHAR(20)     NOT NULL            COMMENT '绑定的QQ号',
    nickname    VARCHAR(100)                        COMMENT 'QQ昵称',
    avatar      VARCHAR(255)                        COMMENT 'QQ头像URL',
    active      BOOLEAN         DEFAULT TRUE        COMMENT '是否激活',
    is_default  BOOLEAN         DEFAULT FALSE       COMMENT '是否为默认账号',
    created_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_qq_number (qq_number),
    UNIQUE KEY idx_user_qq (user_id, qq_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户QQ绑定表';

-- =====================================================================
-- 3. 群聊表 (chat_groups) - 记录群聊基本信息
-- =====================================================================
CREATE TABLE IF NOT EXISTS chat_groups (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    group_id        VARCHAR(50)     NOT NULL            COMMENT '群号',
    owner_qq        VARCHAR(20)     NOT NULL            COMMENT '登录者QQ号（哪个账号加入的群）',
    group_name      VARCHAR(200)                        COMMENT '群名称',
    avatar          VARCHAR(255)                        COMMENT '群头像URL',
    member_count    INT                                 COMMENT '成员数量',
    joined_time     TIMESTAMP       NULL                COMMENT '加入时间',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    active          BOOLEAN         DEFAULT TRUE        COMMENT '是否活跃',
    INDEX idx_group_id (group_id),
    INDEX idx_group_name (group_name),
    INDEX idx_owner_qq (owner_qq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊表';

-- =====================================================================
-- 4. 文件记录表 (file_records) - 存储文件元数据（图片/语音/视频/文件）
-- =====================================================================
CREATE TABLE IF NOT EXISTS file_records (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    file_id         VARCHAR(100)    NOT NULL UNIQUE     COMMENT '文件唯一ID',
    file_name       VARCHAR(500)                        COMMENT '原始文件名',
    file_type       ENUM('IMAGE','VIDEO','AUDIO','FILE') NOT NULL COMMENT '文件类型',
    file_size       BIGINT                              COMMENT '文件大小（字节）',
    mime_type       VARCHAR(100)                        COMMENT 'MIME类型',
    storage_type    ENUM('LOCAL','MINIO','OSS','COS') DEFAULT 'MINIO' COMMENT '存储类型',
    bucket_name     VARCHAR(100)                        COMMENT '存储桶名',
    object_key      VARCHAR(1000)                       COMMENT '对象存储路径',
    url             VARCHAR(1000)                       COMMENT '访问URL',
    thumbnail_url   VARCHAR(1000)                       COMMENT '缩略图URL（图片/视频）',
    width           INT                                 COMMENT '图片宽度',
    height          INT                                 COMMENT '图片高度',
    duration        INT                                 COMMENT '音视频时长（秒）',
    active          BOOLEAN         DEFAULT TRUE        COMMENT '文件记录是否有效(清理后=FALSE)',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_file_id (file_id),
    INDEX idx_type_time (file_type, created_at),
    INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件记录表';

-- =====================================================================
-- 5. 消息表 (messages) - 核心数据表，存储QQ群聊消息
-- =====================================================================
CREATE TABLE IF NOT EXISTS messages (
    id                  BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    message_id          VARCHAR(100)                        COMMENT 'QQ消息唯一ID',
    group_id            VARCHAR(50)     NOT NULL            COMMENT '群号',
    group_name          VARCHAR(200)                        COMMENT '群名称',
    user_qq             VARCHAR(20)     NOT NULL            COMMENT '发送者QQ',
    user_nickname       VARCHAR(100)                        COMMENT '发送者昵称',
    message_type        ENUM('TEXT','IMAGE','VIDEO','AUDIO','FILE','VOICE','AT','REPLY','FORWARD')
                            DEFAULT 'TEXT'                  COMMENT '消息类型',
    content             TEXT                                COMMENT '文本内容或文件描述',
    file_id             VARCHAR(100)                        COMMENT '关联的文件ID（NULL表示纯文本）',
    at_qq               VARCHAR(20)                         COMMENT '@的用户QQ',
    reply_to_message_id BIGINT                              COMMENT '回复的消息ID',
    reply_to_nickname   VARCHAR(100)                        COMMENT '被引用消息发送者昵称',
    reply_to_content    TEXT                                COMMENT '被引用消息内容摘要',
    ai_summary          TEXT                                COMMENT 'AI总结内容',
    send_time           TIMESTAMP       NOT NULL            COMMENT '发送时间',
    created_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    archived            BOOLEAN         DEFAULT FALSE       COMMENT '是否已归档',
    deleted             BOOLEAN         DEFAULT FALSE       COMMENT '用户软删除标记',
    deleted_at          TIMESTAMP       NULL                COMMENT '删除时间',
    deleted_by          BIGINT          NULL                COMMENT '删除的用户ID(users.id)',
    processed           BOOLEAN         DEFAULT FALSE       COMMENT '是否已处理',
    is_self_message     BOOLEAN         DEFAULT FALSE       COMMENT '是否是登录账号发送的消息',
    self_qq             VARCHAR(20)                         COMMENT '登录账号的QQ号（接收这条消息的机器人QQ号）',
    INDEX idx_group_time (group_id, send_time),
    INDEX idx_user_time (user_qq, send_time),
    INDEX idx_msg_file_id (file_id),
    INDEX idx_send_time (send_time),
    INDEX idx_archived (archived),
    INDEX idx_deleted (deleted),
    INDEX idx_self_qq (self_qq),
    INDEX idx_self_deleted (self_qq, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';

-- =====================================================================
-- 6. AstrBot 对话会话表 (astrbot_conversations)
-- =====================================================================
CREATE TABLE IF NOT EXISTS astrbot_conversations (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    conversation_id VARCHAR(64)     NOT NULL UNIQUE     COMMENT '对话唯一ID',
    group_id        VARCHAR(50)                         COMMENT '关联的群号',
    user_qq         VARCHAR(20)                         COMMENT '用户QQ',
    user_nickname   VARCHAR(100)                        COMMENT '用户昵称',
    title           VARCHAR(255)                        COMMENT '对话标题',
    model           VARCHAR(50)                         COMMENT '使用的AI模型',
    message_count   INT             DEFAULT 0           COMMENT '消息数量',
    total_tokens    INT             DEFAULT 0           COMMENT '总Token数',
    context_json    TEXT                                COMMENT '上下文JSON数据',
    archived        BOOLEAN         DEFAULT FALSE       COMMENT '是否已归档',
    time_created    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    time_updated    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    time_archived   TIMESTAMP       NULL                COMMENT '归档时间',
    INDEX idx_conv_group_user (group_id, user_qq),
    INDEX idx_conv_time (time_updated),
    INDEX idx_conv_archived (archived)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AstrBot对话会话表';

-- =====================================================================
-- 7. AstrBot 对话消息表 (astrbot_messages)
-- =====================================================================
CREATE TABLE IF NOT EXISTS astrbot_messages (
    id                  BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    message_id          VARCHAR(64)     NOT NULL UNIQUE     COMMENT '消息唯一ID',
    conversation_id     VARCHAR(64)     NOT NULL            COMMENT '所属对话ID',
    role                ENUM('USER','ASSISTANT','SYSTEM') NOT NULL COMMENT '消息角色',
    content             TEXT                                COMMENT '消息内容',
    data                TEXT                                COMMENT '扩展数据JSON',
    model               VARCHAR(50)                         COMMENT '使用的模型',
    tokens              INT                                 COMMENT 'Token数',
    prompt_tokens       INT                                 COMMENT '提示Token数',
    completion_tokens   INT                                 COMMENT '完成Token数',
    time_created        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    time_updated        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_msg_conversation (conversation_id, time_created),
    INDEX idx_msg_role (role),
    INDEX idx_msg_time (time_created),
    CONSTRAINT fk_astr_msg_conv
        FOREIGN KEY (conversation_id)
        REFERENCES astrbot_conversations (conversation_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AstrBot对话消息表';

-- =====================================================================
-- 8. 用户设置表 (user_settings) - 存储用户的个性化设置
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_settings (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id         VARCHAR(50)     NOT NULL UNIQUE     COMMENT '用户ID',
    bot_name        VARCHAR(100)    DEFAULT 'AstrBot 助手' COMMENT 'Bot名称',
    bot_avatar      VARCHAR(500)    DEFAULT 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100' COMMENT 'Bot头像',
    user_avatar     VARCHAR(500)    DEFAULT 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100' COMMENT '用户头像',
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户设置表';

-- =====================================================================
-- 9. 群聊阅读进度表 (group_read_state) - 记录每个 (用户, 群聊) 的 last_read_time
-- =====================================================================
CREATE TABLE IF NOT EXISTS group_read_state (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id         BIGINT          NOT NULL            COMMENT '系统用户ID（users.id）',
    group_id        VARCHAR(50)     NOT NULL            COMMENT '群号（chat_groups.group_id）',
    last_read_time  TIMESTAMP       NULL                COMMENT '最后阅读时间',
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_group (user_id, group_id),
    INDEX idx_user (user_id),
    INDEX idx_user_group (user_id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊阅读进度表';

-- =====================================================================
-- 10. 初始化数据（可选）
-- =====================================================================
-- 默认管理员账号密码为 admin123 （BCrypt 加密值）
INSERT IGNORE INTO users (username, nickname, password, role, active, created_at)
VALUES (
    'admin',
    '系统管理员',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'ADMIN',
    TRUE,
    NOW()
);
