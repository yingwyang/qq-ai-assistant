ALTER TABLE messages ADD COLUMN user_id BIGINT NULL COMMENT '系统用户ID（关联users.id）' AFTER group_name;

ALTER TABLE messages ADD INDEX idx_user_id (user_id);