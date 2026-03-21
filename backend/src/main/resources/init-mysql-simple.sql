-- 铃音QQ对话 - MySQL 数据库初始化脚本 (简化版)
-- 用户名: root, 密码: ***REMOVED***

-- 创建数据库
CREATE DATABASE IF NOT EXISTS qq_chat;

USE qq_chat;

-- 文件记录表
CREATE TABLE IF NOT EXISTS file_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_id VARCHAR(100) UNIQUE NOT NULL,
    file_name VARCHAR(500),
    file_type VARCHAR(20) NOT NULL,
    file_size BIGINT,
    mime_type VARCHAR(100),
    storage_type VARCHAR(20) DEFAULT 'MINIO',
    bucket_name VARCHAR(100),
    object_key VARCHAR(1000),
    url VARCHAR(1000),
    thumbnail_url VARCHAR(1000),
    width INT,
    height INT,
    duration INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_file_id (file_id),
    INDEX idx_type_time (file_type, created_at)
);

-- 消息表
CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(100),
    group_id VARCHAR(50) NOT NULL,
    group_name VARCHAR(200),
    user_qq VARCHAR(20) NOT NULL,
    user_nickname VARCHAR(100),
    message_type VARCHAR(20) DEFAULT 'TEXT',
    content TEXT,
    file_id VARCHAR(100),
    at_qq VARCHAR(20),
    reply_to_message_id BIGINT,
    ai_summary TEXT,
    send_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    archived BOOLEAN DEFAULT FALSE,
    processed BOOLEAN DEFAULT FALSE,
    INDEX idx_group_time (group_id, send_time),
    INDEX idx_user_time (user_qq, send_time),
    INDEX idx_file_id (file_id),
    INDEX idx_send_time (send_time),
    INDEX idx_archived (archived)
);

-- 查看创建的表
SHOW TABLES;
