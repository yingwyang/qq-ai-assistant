-- =====================================================================
-- QQ AI Assistant - MySQL 完整部署脚本
-- 数据库名: qq_chat
-- 字符集  : utf8mb4
-- 适用版本: MySQL 8.0+
-- 适用场景: 全新部署（含全部 16 张表 + 初始数据）
-- 日期    : 2026-08-21
-- =====================================================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS qq_chat
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE qq_chat;

SET NAMES utf8mb4;

-- =====================================================================
-- 2. 用户表 (users)
-- =====================================================================
CREATE TABLE IF NOT EXISTS users (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    username        VARCHAR(20)     NOT NULL UNIQUE     COMMENT '用户名',
    nickname        VARCHAR(100)                        COMMENT '昵称',
    avatar          VARCHAR(255)                        COMMENT '头像URL',
    token           VARCHAR(255)                        COMMENT '认证令牌',
    password        VARCHAR(255)    NOT NULL            COMMENT '密码（BCrypt加密）',
    role            VARCHAR(50)     DEFAULT 'USER'      COMMENT 'ADMIN / USER',
    last_login_time DATETIME        NULL                COMMENT '最后登录时间',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    active          BOOLEAN         DEFAULT TRUE        COMMENT '是否活跃',
    token_version   INT             DEFAULT 0           COMMENT 'Token 版本号（批量失效用）',
    INDEX idx_username (username),
    INDEX idx_nickname (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- =====================================================================
-- 3. 用户QQ绑定表 (user_qq_bindings)
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_qq_bindings (
    id          BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id     BIGINT          NOT NULL            COMMENT '用户ID',
    qq_number   VARCHAR(20)     NOT NULL            COMMENT '绑定的QQ号',
    nickname    VARCHAR(100)                        COMMENT 'QQ昵称',
    avatar      VARCHAR(255)                        COMMENT 'QQ头像URL',
    active      BOOLEAN         DEFAULT TRUE        COMMENT '是否激活',
    is_default  BOOLEAN         DEFAULT FALSE       COMMENT '是否为默认账号',
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_qq_number (qq_number),
    UNIQUE KEY uk_user_qq (user_id, qq_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户QQ绑定表';

-- =====================================================================
-- 4. 群聊表 (chat_groups)
-- =====================================================================
CREATE TABLE IF NOT EXISTS chat_groups (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    group_id        VARCHAR(50)     NOT NULL            COMMENT '群号',
    owner_qq        VARCHAR(20)     NOT NULL            COMMENT '登录者QQ号',
    group_name      VARCHAR(200)                        COMMENT '群名称',
    group_type      VARCHAR(32)     DEFAULT 'OTHER'     COMMENT 'GAME/STUDY/WORK/HOBBY/LIFE/SOCIAL/OTHER',
    avatar          VARCHAR(255)                        COMMENT '群头像URL',
    member_count    INT                                 COMMENT '成员数量',
    joined_time     DATETIME        NULL                COMMENT '加入时间',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    active          BOOLEAN         DEFAULT TRUE        COMMENT '是否活跃',
    INDEX idx_group_id (group_id),
    INDEX idx_group_name (group_name),
    INDEX idx_owner_qq (owner_qq),
    UNIQUE KEY uk_group_owner (group_id, owner_qq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊表';

-- =====================================================================
-- 5. 文件记录表 (file_records)
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
    thumbnail_url   VARCHAR(1000)                       COMMENT '缩略图URL',
    width           INT                                 COMMENT '图片宽度',
    height          INT                                 COMMENT '图片高度',
    duration        INT                                 COMMENT '音视频时长（秒）',
    active          BOOLEAN         DEFAULT TRUE        COMMENT '文件记录是否有效',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_file_id (file_id),
    INDEX idx_type_time (file_type, created_at),
    INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件记录表';

-- =====================================================================
-- 6. 消息表 (messages)
-- =====================================================================
CREATE TABLE IF NOT EXISTS messages (
    id                  BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    message_id          VARCHAR(100)                        COMMENT 'QQ消息唯一ID',
    group_id            VARCHAR(50)     NOT NULL            COMMENT '群号',
    group_name          VARCHAR(200)                        COMMENT '群名称',
    user_id             BIGINT                              COMMENT '系统用户ID',
    user_qq             VARCHAR(20)     NOT NULL            COMMENT '发送者QQ',
    user_nickname       VARCHAR(100)                        COMMENT '发送者昵称',
    message_type        VARCHAR(20)     NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/VIDEO/AUDIO/FILE/VOICE/AT/REPLY/FORWARD/APP',
    content             TEXT                                COMMENT '文本内容或文件描述',
    file_id             VARCHAR(100)                        COMMENT '关联的文件ID',
    at_qq               VARCHAR(20)                         COMMENT '@的用户QQ',
    reply_to_message_id BIGINT                              COMMENT '回复的消息ID',
    reply_to_nickname   VARCHAR(100)                        COMMENT '被引用消息发送者昵称',
    reply_to_content    TEXT                                COMMENT '被引用消息内容摘要',
    forward_content     TEXT                                COMMENT '合并转发消息的原始内容（JSON数组）',
    mini_app_content    TEXT                                COMMENT '小程序分享消息的原始内容（JSON）',
    ai_summary          TEXT                                COMMENT 'AI总结内容',
    send_time           DATETIME        NOT NULL            COMMENT '发送时间',
    raw_msg_time        BIGINT                              COMMENT 'OneBot原始Unix时间戳',
    msg_seq             INT                                 COMMENT 'OneBot message_seq',
    server_recv_ms      BIGINT                              COMMENT '服务端收到消息的毫秒级epoch',
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    archived            BOOLEAN         DEFAULT FALSE       COMMENT '是否已归档',
    deleted             BOOLEAN         DEFAULT FALSE       COMMENT '用户软删除',
    deleted_at          DATETIME        NULL                COMMENT '删除时间',
    deleted_by          BIGINT          NULL                COMMENT '删除的用户ID',
    processed           BOOLEAN         DEFAULT FALSE       COMMENT '是否已处理',
    media_pending       BOOLEAN         DEFAULT FALSE       COMMENT '媒体是否待异步下载',
    is_self_message     BOOLEAN         DEFAULT FALSE       COMMENT '是否是登录账号发送',
    self_qq             VARCHAR(20)                         COMMENT '登录账号的QQ号',
    INDEX idx_group_time (group_id, send_time),
    INDEX idx_user_time (user_qq, send_time),
    INDEX idx_msg_file_id (file_id),
    INDEX idx_send_time (send_time),
    INDEX idx_group_server_time (group_id, server_recv_ms),
    INDEX idx_user_id (user_id),
    INDEX idx_archived (archived),
    INDEX idx_deleted (deleted),
    INDEX idx_self_qq (self_qq),
    INDEX idx_self_deleted (self_qq, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';

-- =====================================================================
-- 7. AstrBot 对话会话表 (astrbot_conversations)
-- =====================================================================
CREATE TABLE IF NOT EXISTS astrbot_conversations (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    conversation_id VARCHAR(64)     NOT NULL UNIQUE     COMMENT '对话唯一ID',
    user_id         BIGINT                              COMMENT '系统用户ID',
    group_id        VARCHAR(50)                         COMMENT '关联的群号',
    user_qq         VARCHAR(20)                         COMMENT '用户QQ',
    user_nickname   VARCHAR(100)                        COMMENT '用户昵称',
    title           VARCHAR(255)                        COMMENT '对话标题',
    model           VARCHAR(50)                         COMMENT '使用的AI模型',
    message_count   INT             DEFAULT 0           COMMENT '消息数量',
    total_tokens    INT             DEFAULT 0           COMMENT '总Token数',
    context_json    TEXT                                COMMENT '上下文JSON数据',
    archived        BOOLEAN         DEFAULT FALSE       COMMENT '是否已归档',
    time_created    DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    time_updated    DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    time_archived   DATETIME        NULL                COMMENT '归档时间',
    INDEX idx_conv_group_user (group_id, user_qq),
    INDEX idx_conv_time (time_updated),
    INDEX idx_conv_archived (archived),
    INDEX idx_conv_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AstrBot对话会话表';

-- =====================================================================
-- 8. AstrBot 对话消息表 (astrbot_messages)
-- =====================================================================
CREATE TABLE IF NOT EXISTS astrbot_messages (
    id                  BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    message_id          VARCHAR(64)     NOT NULL UNIQUE     COMMENT '消息唯一ID',
    conversation_id     VARCHAR(64)     NOT NULL            COMMENT '所属对话ID',
    role                VARCHAR(20)     NOT NULL            COMMENT 'USER/ASSISTANT/SYSTEM',
    content             TEXT                                COMMENT '消息内容',
    data                TEXT                                COMMENT '扩展数据JSON',
    model               VARCHAR(50)                         COMMENT '使用的模型',
    tokens              INT                                 COMMENT 'Token数',
    prompt_tokens       INT                                 COMMENT '提示Token数',
    completion_tokens   INT                                 COMMENT '完成Token数',
    time_created        DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    time_updated        DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_msg_conversation (conversation_id, time_created),
    INDEX idx_msg_role (role),
    INDEX idx_msg_time (time_created),
    CONSTRAINT fk_astr_msg_conv
        FOREIGN KEY (conversation_id)
        REFERENCES astrbot_conversations (conversation_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AstrBot对话消息表';

-- =====================================================================
-- 9. 用户设置表 (user_settings)
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_settings (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id         VARCHAR(50)     NOT NULL UNIQUE     COMMENT '用户ID',
    bot_name        VARCHAR(100)    DEFAULT 'AstrBot 助手' COMMENT 'Bot名称',
    bot_avatar      VARCHAR(500)    DEFAULT 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100' COMMENT 'Bot头像',
    user_avatar     VARCHAR(500)    DEFAULT 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100' COMMENT '用户头像',
    api_key         VARCHAR(1000)                       COMMENT 'AstrBot API Key',
    llm_api_key     VARCHAR(1000)                       COMMENT '用户自定义大模型API Key',
    llm_base_url    VARCHAR(500)                        COMMENT '大模型API地址',
    llm_model       VARCHAR(200)                        COMMENT '当前选中的大模型',
    llm_models      TEXT                                COMMENT '模型列表JSON',
    providers       TEXT                                COMMENT '提供商列表JSON',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户设置表';

-- =====================================================================
-- 10. 群聊阅读进度表 (group_read_state)
-- =====================================================================
CREATE TABLE IF NOT EXISTS group_read_state (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id         BIGINT          NOT NULL            COMMENT '系统用户ID',
    group_id        VARCHAR(50)     NOT NULL            COMMENT '群号',
    last_read_time  DATETIME        NULL                COMMENT '最后阅读时间',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_group (user_id, group_id),
    INDEX idx_user (user_id),
    INDEX idx_user_group (user_id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊阅读进度表';

-- =====================================================================
-- 11. 用户积分账户表 (user_credit)
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_credit (
    id                  BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id             BIGINT          NOT NULL UNIQUE     COMMENT '用户ID',
    balance             INT             NOT NULL DEFAULT 0  COMMENT '积分余额',
    total_earned        INT             NOT NULL DEFAULT 0  COMMENT '累计获取积分',
    total_spent         INT             NOT NULL DEFAULT 0  COMMENT '累计消耗积分',
    consumption_banned  BOOLEAN         NOT NULL DEFAULT FALSE COMMENT '是否禁止消费',
    subscription_tier   VARCHAR(20)     NOT NULL DEFAULT 'FREE' COMMENT 'FREE/SMALL_MONTH_CARD/LARGE_MONTH_CARD/ALL',
    subscription_expires_at DATETIME   NULL                COMMENT '订阅到期时间',
    version             INT             NOT NULL DEFAULT 0  COMMENT '乐观锁版本号',
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX idx_user_credit_user_id (user_id),
    INDEX idx_user_credit_tier (subscription_tier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户积分账户表';

-- =====================================================================
-- 12. 积分流水表 (credit_transaction)
-- =====================================================================
CREATE TABLE IF NOT EXISTS credit_transaction (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id         BIGINT          NOT NULL            COMMENT '用户ID',
    type            VARCHAR(30)     NOT NULL            COMMENT 'NEW_USER_BONUS/SIGN_IN/AI_CONSUMPTION/AI_CHAT/AI_ANALYZE/TTS_SYNTHESIS/SUBSCRIPTION_PURCHASE/REFUND/ADMIN_GRANT/ADMIN_DEDUCT/EXPIRE/MONTHLY_CARD_DAILY/CASH_INCOME/CASH_EXPENSE',
    direction       VARCHAR(10)     NOT NULL            COMMENT 'IN/OUT',
    amount          INT             NOT NULL            COMMENT '变动积分数量',
    balance_after   INT             NOT NULL            COMMENT '变动后余额',
    remark          VARCHAR(500)                        COMMENT '备注',
    related_id      VARCHAR(100)                        COMMENT '关联订单/消息ID',
    admin_user_id   BIGINT                              COMMENT '操作管理员ID',
    cash_amount     DECIMAL(12,2)                       COMMENT '模拟现金变动(元,带符号:正=收入/负=支出,NULL=无现金影响)',
    cash_category   VARCHAR(30)                         COMMENT '现金类别:SUBSCRIPTION/REFUND/AI_COST/MANUAL',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_tx_user_id (user_id),
    INDEX idx_tx_user_type (user_id, type),
    INDEX idx_tx_created_at (created_at),
    INDEX idx_tx_related_id (related_id),
    INDEX idx_tx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分流水表';

-- =====================================================================
-- 13. 积分规则表 (credit_rule) - 单行全局配置
-- =====================================================================
CREATE TABLE IF NOT EXISTS credit_rule (
    id                      BIGINT          PRIMARY KEY           COMMENT '固定为1的单行配置',
    new_user_bonus          INT             NOT NULL DEFAULT 500  COMMENT '新用户注册奖励积分',
    sign_in_points          INT             NOT NULL DEFAULT 150  COMMENT '每日签到积分',
    token_unit              INT             NOT NULL DEFAULT 1000 COMMENT '每1000 token为1积分计算单位',
    prompt_rate             INT             NOT NULL DEFAULT 2    COMMENT '提示词积分费率',
    completion_rate         INT             NOT NULL DEFAULT 4    COMMENT '完成词积分费率',
    min_cost                INT             NOT NULL DEFAULT 5    COMMENT '单次最小扣费',
    default_cost_per_msg    INT             NOT NULL DEFAULT 10   COMMENT 'AI对话单条默认扣费',
    admin_free              BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '管理员免费',
    plan_lite_price         DECIMAL(10,2)   NOT NULL DEFAULT 9.90  COMMENT '轻量版月卡价格',
    plan_lite_credit        INT             NOT NULL DEFAULT 2000 COMMENT '轻量版月卡积分',
    plan_pro_price          DECIMAL(10,2)   NOT NULL DEFAULT 59.00 COMMENT '标准版月卡价格',
    plan_pro_credit         INT             NOT NULL DEFAULT 4000 COMMENT '标准版月卡积分',
    plan_pro_plus_price     DECIMAL(10,2)   NOT NULL DEFAULT 219.00 COMMENT '高级版月卡价格',
    plan_pro_plus_credit    INT             NOT NULL DEFAULT 12000 COMMENT '高级版月卡积分',
    plan_ultra_price        DECIMAL(10,2)   NOT NULL DEFAULT 629.00 COMMENT '旗舰版月卡价格',
    plan_ultra_credit       INT             NOT NULL DEFAULT 40000 COMMENT '旗舰版月卡积分',
    plan_duration_days      INT             NOT NULL DEFAULT 30   COMMENT '月卡有效期天数',
    model_rates             VARCHAR(2000)    DEFAULT '{"default":1.0}' COMMENT '模型分级费率JSON',
    image_extra_cost        INT             NOT NULL DEFAULT 5    COMMENT '每张图片额外消耗积分',
    analyze_base_cost       INT             NOT NULL DEFAULT 10   COMMENT 'AI分析基础费用',
    analyze_cost_per_msg    INT             NOT NULL DEFAULT 1    COMMENT 'AI分析每条消息增量',
    analyze_type_rates      VARCHAR(2000)    DEFAULT '{"default":1.0}' COMMENT '分析类型倍率JSON',
    tts_chars_per_credit    INT             NOT NULL DEFAULT 50   COMMENT 'TTS每多少字符扣1积分',
    tts_min_cost            INT             NOT NULL DEFAULT 2    COMMENT 'TTS每次最小消耗积分',
    monthly_free_quota      INT             NOT NULL DEFAULT 0    COMMENT '每月免费配额(0=不免费)',
    overtax_rate            DOUBLE          NOT NULL DEFAULT 1.5  COMMENT '超额消费倍率',
    small_month_card_discount DOUBLE        NOT NULL DEFAULT 0.9  COMMENT '小月卡折扣',
    large_month_card_discount DOUBLE        NOT NULL DEFAULT 0.8  COMMENT '大月卡折扣',
    all_tier_discount       DOUBLE          NOT NULL DEFAULT 0.7  COMMENT 'ALL状态折扣',
    context_extra_cost_per_msg INT         NOT NULL DEFAULT 1    COMMENT '上下文每条历史消息额外扣费',
    context_free_msg_count  INT             NOT NULL DEFAULT 10   COMMENT '前N条上下文消息免费',
    daily_cap_cost          INT             NOT NULL DEFAULT 0    COMMENT '每日封顶消费(0=不限)',
    tiered_discount_thresholds VARCHAR(2000) DEFAULT '{"1000":0.95,"5000":0.9,"20000":0.85}' COMMENT '阶梯折扣阈值JSON',
    created_at              DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分规则表（全局单行配置）';

-- =====================================================================
-- 14. 月卡每日奖励记录表 (monthly_bonus_record)
-- =====================================================================
CREATE TABLE IF NOT EXISTS monthly_bonus_record (
    id          BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id     BIGINT          NOT NULL            COMMENT '用户ID',
    bonus_date  DATE            NOT NULL            COMMENT '奖励日期',
    created_at  DATETIME(6)     NULL                COMMENT '创建时间',
    UNIQUE KEY uk_bonus_user_date (user_id, bonus_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月卡每日奖励记录表';

-- =====================================================================
-- 15. 签到记录表 (sign_in_record)
-- =====================================================================
CREATE TABLE IF NOT EXISTS sign_in_record (
    id              BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    user_id         BIGINT          NOT NULL            COMMENT '用户ID',
    sign_in_date    DATE            NOT NULL            COMMENT '签到日期',
    points          INT             NOT NULL            COMMENT '签到获得积分',
    streak_days     INT             NOT NULL            COMMENT '连续签到天数',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_signin_user_date (user_id, sign_in_date),
    INDEX idx_signin_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签到记录表';

-- =====================================================================
-- 16. 订阅订单表 (subscription_order)
-- =====================================================================
CREATE TABLE IF NOT EXISTS subscription_order (
    id                  BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    order_no            VARCHAR(50)     NOT NULL UNIQUE     COMMENT '订单号',
    user_id             BIGINT          NOT NULL            COMMENT '用户ID',
    plan_tier           VARCHAR(20)     NOT NULL            COMMENT '订阅档位: SMALL_MONTH_CARD/LARGE_MONTH_CARD',
    price               DECIMAL(10,2)   NOT NULL            COMMENT '订单金额',
    credit_amount       INT             NOT NULL            COMMENT '发放积分',
    duration_days       INT             NOT NULL            COMMENT '有效期天数',
    status              VARCHAR(20)     NOT NULL            COMMENT 'PENDING/PAID/DISPUTED/PENDING_REFUND/REFUNDED/CANCELLED/EXPIRED',
    payment_method      VARCHAR(50)                        COMMENT '支付方式',
    payment_transaction_id VARCHAR(100)                    COMMENT '支付交易ID',
    paid_at             DATETIME        NULL                COMMENT '支付时间',
    expires_at          DATETIME        NULL                COMMENT '到期时间',
    refunded_at         DATETIME        NULL                COMMENT '退款时间',
    refund_amount       DECIMAL(10,2)   NULL                COMMENT '退款金额',
    refund_reason       VARCHAR(500)                       COMMENT '退款原因',
    dispute_reason      VARCHAR(500)                       COMMENT '纠纷原因',
    metadata            VARCHAR(2000)                      COMMENT '附加元数据JSON',
    client_ip           VARCHAR(50)                        COMMENT '客户端IP',
    user_agent          VARCHAR(200)                       COMMENT '用户UA',
    refund_admin_user_id BIGINT                             COMMENT '退款审批管理员ID',
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX idx_order_no (order_no),
    INDEX idx_order_user_id (user_id),
    INDEX idx_order_user_status (user_id, status),
    INDEX idx_order_status (status),
    INDEX idx_order_created_at (created_at),
    INDEX idx_order_expires_at (expires_at),
    INDEX idx_order_user_plan_created (user_id, plan_tier, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅订单表';

-- =====================================================================
-- 17. 审计日志表 (audit_log)
-- =====================================================================
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT          AUTO_INCREMENT      PRIMARY KEY,
    username    VARCHAR(50)                        COMMENT '操作人',
    action      VARCHAR(50)                        COMMENT '操作类型',
    target      VARCHAR(255)                       COMMENT '操作目标',
    result      VARCHAR(20)                        COMMENT 'SUCCESS/FAILURE',
    detail      VARCHAR(1000)                      COMMENT '详细信息',
    timestamp   DATETIME        NULL                COMMENT '操作时间',
    INDEX idx_audit_username (username),
    INDEX idx_audit_action (action),
    INDEX idx_audit_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

-- =====================================================================
-- 初始数据
-- =====================================================================

-- 积分规则默认配置（id=1，单行全局配置）
INSERT INTO credit_rule (id) VALUES (1)
ON DUPLICATE KEY UPDATE id = 1;

-- 默认管理员账号
-- 用户名: admin
-- 密码: admin123 (BCrypt 加密)
-- 注意: Spring Boot 启动时 DataInitializer 会自动创建此账号
--       此处 SQL 初始化用于 standalone MySQL 部署场景
INSERT INTO users (username, nickname, password, role, active, token_version)
SELECT 'admin', '系统管理员',
       '$2a$10$EixZaYVK1fsbw1ZfbX3OXePaWxn96p36LaGeRn5D3r3hJ3Mn5BMTy',
       'ADMIN', TRUE, 0
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin');

-- 为管理员创建积分账户
INSERT INTO user_credit (user_id, balance, total_earned, total_spent, subscription_tier, version)
SELECT u.id, 0, 0, 0, 'FREE', 0
FROM users u
WHERE u.username = 'admin' AND NOT EXISTS (SELECT 1 FROM user_credit WHERE user_id = u.id);

-- 为管理员创建用户设置
INSERT INTO user_settings (user_id, bot_name)
SELECT u.id, 'AstrBot 助手'
FROM users u
WHERE u.username = 'admin' AND NOT EXISTS (SELECT 1 FROM user_settings WHERE user_id = u.id);

-- 验证输出
SELECT '=== 部署完成 ===' AS '状态';
SELECT TABLE_NAME AS '表名', TABLE_COMMENT AS '说明'
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'qq_chat'
ORDER BY TABLE_NAME;
