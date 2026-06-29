# 用户删除消息 & 清理媒体文件 - 任务分解 (Tasks)

## [x] Task 1: 数据库 & JPA 实体层变更
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 在 `Message.java` 实体中新增字段：`boolean deleted = false`，`LocalDateTime deletedAt`，`Long deletedBy`（可选）。
  - 在 `FileRecord.java` 实体中新增字段：`boolean active = true`（便于标记文件记录状态，如清理后 active=false）。
  - 更新 `init-mysql.sql`，为 `messages` 表新增 3 列 + 索引；为 `file_records` 表新增 active 列 + 索引。
- **Acceptance Criteria Addressed**: FR-1, AC-1
- **Test Requirements**:
  - `programmatic`：JPA 启动后 `messages` 表存在 deleted 字段；手动 SQL `ALTER TABLE messages ADD deleted BOOLEAN NOT NULL DEFAULT false` 能执行成功。
- **Notes**: 生产环境建议使用 `ALTER TABLE ... ADD COLUMN ... AFTER archived`，避免 rebuild 整表。

## [x] Task 2: MessageRepository 增加 deleted=false 过滤
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 在所有查询消息列表/统计数量的 JPQL 方法中，额外加上 `AND m.deleted = false`。
  - 保留 `findById` 原样（不排除 deleted）用于管理员查看。
  - 新增 `countByMessageTypeInAndDeletedFalse` 等必要统计方法。
- **Acceptance Criteria Addressed**: FR-4, AC-1
- **Test Requirements**:
  - `programmatic`：执行 `POST /api/messages/delete-batch` 后再次 `GET /api/messages/group/{groupId}` 返回的结果不含已删除消息。

## [x] Task 3: MessageService 删除逻辑增强
- **Priority**: high
- **Depends On**: Task 1, Task 2
- **Description**:
  - 重构 `deleteMessage(id)`：改为 `setDeleted(true), setDeletedAt(NOW), setDeletedBy(userId)`，保留 `archived` 原值。
  - 重构 `deleteMessagesByIds(ids, selfQqList)`：增加权限校验与删除动作。
  - 新增 `deleteMessagesWithMedia(ids, selfQqList, userId, deleteMedia)`：当 `deleteMedia=true` 时，对每条 IMAGE / VIDEO / AUDIO / VOICE 消息调用本地文件删除逻辑。
  - 新增日志记录（`log.warn("user {} deleted {} messages", userId, n)`）。
- **Acceptance Criteria Addressed**: FR-2, NFR-4
- **Test Requirements**:
  - `programmatic`：调用后 `SELECT id, deleted FROM messages WHERE id IN (...)` 全部返回 `1/true`。

## [x] Task 4: 本地文件存储服务（删除 & 统计）
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新增 `com.qqai.service.FilePurgeService`（或在 `FileStorageService` 中新增方法）：
    - `deleteFileByUrl(url)`：从 `/images/...` 的 URL 解析物理路径并删除。
    - `deleteByMessageContent(contentOrFileId)`：对消息中包含的文件ID/URL 进行删除。
    - `purgeLocalMedia(types: List<FileRecord.FileType>)`：递归扫描 `backend/uploads/` 下对应子目录并删除文件，返回 { totalDeleted, freedSizeBytes, removedFiles: [] }。
  - 对 `file_records`：如果 fileId 匹配的文件被清理，把该记录置 `active=false`。
- **Acceptance Criteria Addressed**: FR-3, NFR-2, NFR-3
- **Test Requirements**:
  - `programmatic`：构造测试目录 + 测试文件后，调用 purge 后文件数递减。

## [x] Task 5: MessageController 删除/清理 API 增强
- **Priority**: high
- **Depends On**: Task 3, Task 4
- **Description**:
  - `POST /api/messages/delete-batch`：扩展请求体 `{ ids: [..], deleteMedia: bool }`，调用 Task 3 的增强方法。
  - `POST /api/messages/purge-media`：入参 `{ types: ["IMAGE", "VIDEO", "AUDIO"] }`；无登录 → 401。
  - `GET /api/messages/group/{groupId}` 与 `/recent-groups`：保持现有签名，但过滤 deleted。
- **Acceptance Criteria Addressed**: FR-2, FR-3, NFR-2
- **Test Requirements**:
  - `programmatic`：未登录时返回 401；传入非本人消息ID时返回 403。

## [x] Task 6: 前端 ChatInterface 删除按钮增强
- **Priority**: high
- **Depends On**: Task 5
- **Description**:
  - `ChatInterface.vue` 的 `btn-delete` 确认对话框增加 `是否同时删除关联媒体文件（图片 / 视频 / 音频）` 的 `<input type=checkbox>`。
  - 调用 `messageApi.deleteMessages(ids, deleteMedia)`；成功后从 `messages` 状态里移除对应项；失败时 Toast。
- **Acceptance Criteria Addressed**: FR-5, G1
- **Test Requirements**:
  - `human-judgement`：UI 点击删除后，列表立即少掉 N 项。

## [x] Task 7: 前端 Sidebar 清理媒体文件入口
- **Priority**: high
- **Depends On**: Task 5
- **Description**:
  - 在 Sidebar.vue "系统管理"区域下方新增一个 span "清理媒体文件"（带垃圾桶图标）。
  - 点击后弹出"清理媒体对话框"：列出可选类型（图片 / 视频 / 音频 / 全部），默认仅选图片。
  - 用户点击确认后调用 `messageApi.purgeMedia(types)`，成功后 Toast 提示释放空间大小。
- **Acceptance Criteria Addressed**: FR-6, G2
- **Test Requirements**:
  - `human-judgement`：UI 流程顺畅；确认后能触发后端清理。

## [x] Task 8: 前端 MessageContent 占位图与降级
- **Priority**: high
- **Depends On**: None
- **Description**:
  - `MessageContent.vue` 中的 `<img>` 绑定 `@error="onImageError"`：将 `imageUrl` 换成占位图 `/images/placeholders/image-deleted.svg`，并在图片外层包一层容器（禁用点击放大）。
  - `<video>` 绑定 `@error="onVideoError"`：video 元素出错时显示 `video-deleted.svg` 替代；同时禁用原生控制条。
  - 语音消息点击播放时，如果 `voiceUrl` 为空或 onerror，showToast("音频已删除", "info")。
  - 在 `frontend/src/assets/placeholders/` 新增两张 SVG 占位图（见 Task 9）。
- **Acceptance Criteria Addressed**: FR-7, AC-6, AC-7, AC-8
- **Test Requirements**:
  - `human-judgement`：构造一个不存在的图片 URL 时，占位图正确显示。

## [x] Task 9: 前端静态资源 - 占位图（SVG）
- **Priority**: medium
- **Depends On**: None
- **Description**:
  - 创建 `image-deleted.svg`：简洁 "Image Deleted" 风格图；宽 200px，高 200px，灰色。
  - 创建 `video-deleted.svg`：类似的视频占位图。
  - 将两张 SVG 放到 `frontend/public/images/placeholders/`，前端以 `/images/placeholders/image-deleted.svg` 引用。
- **Acceptance Criteria Addressed**: FR-7
- **Notes**: SVG 比 PNG/JPG 更省空间；可以内嵌 base64 到 html，避免额外请求。

## [x] Task 10: 更新 API 服务层（api.js）
- **Priority**: high
- **Depends On**: Task 5
- **Description**:
  - 为 `messageApi` 增加方法：
    - `deleteMessages(ids, deleteMedia)` → `POST /api/messages/delete-batch`，body `{ ids, deleteMedia }`。
    - `purgeMedia(types)` → `POST /api/messages/purge-media`，body `{ types }`。
- **Acceptance Criteria Addressed**: FR-5, FR-6
- **Test Requirements**:
  - `programmatic`：前端构建通过（无 undefined 方法）。

## [x] Task 11: 更新 README / SQL 脚本
- **Priority**: medium
- **Depends On**: Task 1, Task 4
- **Description**:
  - `README.md` 中描述删除/清理功能入口与权限要求。
  - `init-mysql.sql` 增加相应列的 ALTER 语句（Task 1 已做）。
- **Acceptance Criteria Addressed**: 文档一致性

## [x] Task 12: 编译验证 & 冒烟测试
- **Priority**: high
- **Depends On**: Task 2, Task 3, Task 5, Task 6, Task 7, Task 8, Task 9, Task 10
- **Description**:
  - `mvn clean compile` 后端；
  - `npm run build` 前端；
  - 重启后端 + 前端，手动验证：
    1. 登录后打开一个群；
    2. 选中一条消息 → 点击"删除"并勾选删除媒体 → 消息消失 + 对应文件实际从磁盘删除；
    3. 刷新页面，之前删掉的消息不在出现；
    4. 清理媒体文件：侧边栏 → 清理媒体文件 → 选择"图片" → 确认；日志中出现 purge X files Y MB。
    5. 图片/视频/语音对应的 URL 是一个 404 时，渲染为占位图或 Toast 音频已删除。
- **Test Requirements**:
  - `programmatic` + `human-judgement`：确保无 500/404。
- **Notes**: 如后端报 SQL 列缺失，需要 `ALTER TABLE messages ADD deleted BOOLEAN NOT NULL DEFAULT false AFTER archived;` 手动同步。
