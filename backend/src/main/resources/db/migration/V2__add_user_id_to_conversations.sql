ALTER TABLE astrbot_conversations ADD COLUMN user_id BIGINT NULL COMMENT '系统用户ID（关联users.id）' AFTER conversation_id;

ALTER TABLE astrbot_conversations ADD INDEX idx_conv_user (user_id);

ALTER TABLE astrbot_conversations DROP INDEX idx_conv_group_user;
ALTER TABLE astrbot_conversations ADD INDEX idx_conv_group_user (group_id, user_id);