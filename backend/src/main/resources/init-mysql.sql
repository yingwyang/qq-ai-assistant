-- 铃音QQ对话 - MySQL 数据库初始化脚本
-- 用户名: root, 密码: ***REMOVED***

-- 创建数据库
CREATE DATABASE IF NOT EXISTS qq_chat 
CHARACTER SET utf8mb4 
COLLATE utf8mb4_unicode_ci;

USE qq_chat;

-- 文件记录表
CREATE TABLE IF NOT EXISTS file_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id VARCHAR(100) UNIQUE NOT NULL COMMENT '文件唯一ID',
    file_name VARCHAR(500) COMMENT '原始文件名',
    file_type ENUM('IMAGE', 'VIDEO', 'AUDIO', 'FILE') NOT NULL COMMENT '文件类型',
    file_size BIGINT COMMENT '文件大小(字节)',
    mime_type VARCHAR(100) COMMENT 'MIME类型',
    storage_type ENUM('LOCAL', 'MINIO', 'OSS', 'COS') DEFAULT 'MINIO' COMMENT '存储类型',
    bucket_name VARCHAR(100) COMMENT '存储桶名',
    object_key VARCHAR(1000) COMMENT '对象存储路径',
    url VARCHAR(1000) COMMENT '访问URL',
    thumbnail_url VARCHAR(1000) COMMENT '缩略图URL(图片/视频)',
    width INT COMMENT '图片宽度',
    height INT COMMENT '图片高度',
    duration INT COMMENT '音视频时长(秒)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_id (file_id),
    INDEX idx_type_time (file_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件记录表';

-- 消息表
CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(100) COMMENT 'QQ消息唯一ID',
    group_id VARCHAR(50) NOT NULL COMMENT '群号',
    group_name VARCHAR(200) COMMENT '群名称',
    user_qq VARCHAR(20) NOT NULL COMMENT '发送者QQ',
    user_nickname VARCHAR(100) COMMENT '发送者昵称',
    message_type ENUM('TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'FILE', 'VOICE', 'AT', 'REPLY') DEFAULT 'TEXT' COMMENT '消息类型',
    content TEXT COMMENT '文本内容或文件描述',
    file_id VARCHAR(100) COMMENT '关联的文件ID（NULL表示纯文本）',
    at_qq VARCHAR(20) COMMENT '@的用户QQ',
    reply_to_message_id BIGINT COMMENT '回复的消息ID',
    ai_summary TEXT COMMENT 'AI总结内容',
    send_time TIMESTAMP NOT NULL COMMENT '发送时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    archived BOOLEAN DEFAULT FALSE COMMENT '是否已归档',
    processed BOOLEAN DEFAULT FALSE COMMENT '是否已处理',
    INDEX idx_group_time (group_id, send_time),
    INDEX idx_user_time (user_qq, send_time),
    INDEX idx_file_id (file_id),
    INDEX idx_send_time (send_time),
    INDEX idx_archived (archived)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';

-- 创建用户（可选，如果使用root可以跳过）
-- CREATE USER IF NOT EXISTS 'qq_chat_user'@'localhost' IDENTIFIED BY 'qq_chat_password';
-- GRANT ALL PRIVILEGES ON qq_chat.* TO 'qq_chat_user'@'localhost';
-- FLUSH PRIVILEGES;

-- 查看创建的表
SHOW TABLES;

-- 查看表结构
DESCRIBE file_records;
DESCRIBE messages;
