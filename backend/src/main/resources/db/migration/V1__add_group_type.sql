-- 群聊类型字段 - 用于按群类型定制 LLM Prompt 策略
-- 类型: GAME(游戏) / STUDY(学习) / WORK(工作) / HOBBY(兴趣) / LIFE(生活) / SOCIAL(社交) / OTHER(其他)
ALTER TABLE chat_groups ADD COLUMN group_type VARCHAR(32) DEFAULT 'OTHER';
