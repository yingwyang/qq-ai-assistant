-- 修改 message_type 列长度以支持小程序消息类型
ALTER TABLE messages MODIFY COLUMN message_type VARCHAR(20) NOT NULL;
