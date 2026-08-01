-- =====================================================================
-- 修复 chat_groups 表的唯一约束
-- 问题: 旧版 Hibernate ddl-auto 在 group_id 单列上自动生成了唯一约束
--       (UK_8ieskxubxdg6s7dmt7jcfb7rw)，导致同一群聊无法为不同登录 QQ
--       分别插入记录（Duplicate entry 'xxx' for key 'chat_groups.UK_...'）
-- 修复: 删除 group_id 单列唯一约束，改为 (group_id, owner_qq) 复合唯一约束
--       同一个 QQ 在同一个群里只允许有一条记录，但不同 QQ 可以各有一条
-- 注意: 使用 EXECUTE IMMEDIATE（MySQL 8.0.13+）以兼容 Flyway 默认分隔符
-- =====================================================================

-- 1. 查找并删除 group_id 单列上的唯一约束（按列定位，兼容不同约束名）
SET @drop_idx_name = (
    SELECT INDEX_NAME
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_groups'
      AND COLUMN_NAME = 'group_id'
      AND NON_UNIQUE = 0
      AND INDEX_NAME != 'PRIMARY'
      AND INDEX_NAME != 'uk_group_owner'
    LIMIT 1
);

SET @drop_idx_sql = IF(@drop_idx_name IS NOT NULL,
    CONCAT('ALTER TABLE chat_groups DROP INDEX `', @drop_idx_name, '`'),
    'DO 0');

PREPARE drop_idx_stmt FROM @drop_idx_sql;
EXECUTE drop_idx_stmt;
DEALLOCATE PREPARE drop_idx_stmt;

-- 2. 添加 (group_id, owner_qq) 复合唯一约束（若不存在）
SET @has_composite = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_groups'
      AND INDEX_NAME = 'uk_group_owner'
);

SET @add_composite_sql = IF(@has_composite = 0,
    'ALTER TABLE chat_groups ADD UNIQUE KEY uk_group_owner (group_id, owner_qq)',
    'DO 0');

PREPARE add_composite_stmt FROM @add_composite_sql;
EXECUTE add_composite_stmt;
DEALLOCATE PREPARE add_composite_stmt;
