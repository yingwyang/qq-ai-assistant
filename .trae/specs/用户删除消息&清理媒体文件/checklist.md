# 用户删除消息 & 清理媒体文件 - 验证清单 (Checklist)

## 数据库层
- [ ] `messages` 表新增列 `deleted (BOOL NOT NULL DEFAULT false)`, `deletedAt (TIMESTAMP NULL)`, `deletedBy (BIGINT NULL)`
- [ ] `messages.deleted` / `(self_qq, deleted)` 两个索引已建立
- [ ] `file_records` 表新增 `active BOOLEAN NOT NULL DEFAULT true`

## 后端
- [ ] `Message.java` 新增字段 `deleted / deletedAt / deletedBy`
- [ ] `FileRecord.java` 新增字段 `active`
- [ ] `MessageRepository` 中所有消息列表/统计方法加了 `AND m.deleted = false`
- [ ] `MessageService.deleteMessage` / `deleteMessagesByIds` 用新字段替换原先只改 `archived`
- [ ] `MessageService` 新增带 `deleteMedia` 的删除方法（调用文件删除）
- [ ] 新增 `FilePurgeService` 或 `FileStorageService` 中的 `purgeLocalMedia(types)` 方法
- [ ] `MessageController` 新增 `POST /api/messages/purge-media`
- [ ] `POST /api/messages/delete-batch` 支持 `deleteMedia` 参数
- [ ] 删除/清理接口对未登录返回 401，对非本人消息返回 403
- [ ] 删除/清理动作有日志输出

## 前端
- [ ] `ChatInterface.vue` 确认删除对话框带"同时删除关联媒体文件"选项
- [ ] 成功删除后，前端消息列表立即刷新移除
- [ ] Sidebar.vue 有"清理媒体文件"入口（span，带垃圾桶图标）
- [ ] 点击清理入口能弹出类型选择对话框，确认后调用 API
- [ ] `MessageContent.vue` 的 `<img>` 有 `@error` 替换为 `/images/placeholders/image-deleted.svg`
- [ ] `MessageContent.vue` 的 `<video>` 有 `@error` 替换为 `video-deleted.svg`
- [ ] 语音消息点击播放若文件不存在时，Toast "音频已删除"
- [ ] `api.js` 新增 `deleteMessages(ids, deleteMedia)` 与 `purgeMedia(types)`
- [ ] 两张 SVG 占位图放置在 `frontend/public/images/placeholders/`

## 冒烟测试
- [ ] `mvn clean compile` 无错误
- [ ] 后端启动成功（JPA 不会因列缺失报错；否则手动执行 ALTER TABLE …）
- [ ] 前端 `npm run build` 成功
- [ ] 登录后手动选中消息 → 删除按钮 → 消息消失；再次刷新也不再出现
- [ ] 删除同时勾选媒体 → 对应本地文件实际被删除
- [ ] 侧边栏"清理媒体文件" → 确认 → 后端日志显示 purge X files Y MB；磁盘占用减少
- [ ] 图片/视频消息对应的文件不存在时，渲染为占位图
- [ ] 语音消息文件不存在时，点击播放 → Toast "音频已删除"
- [ ] 未登录/无权限用户调用 delete-batch / purge-media 返回 401/403

## 文档
- [ ] README.md 中描述删除/清理功能入口、权限、行为（软删除 vs 物理文件删除）
- [ ] init-mysql.sql 中包含新列的 ALTER 语句
