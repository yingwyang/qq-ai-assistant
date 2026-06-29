# 未读消息红点与数字标记功能 - 实施计划

## [x] Task 1: 新增 GroupReadState 实体与 Repository
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 创建 `com.qqai.entity.GroupReadState` JPA 实体，对应表 `group_read_state`。
  - 字段：`id / userId / groupId / lastReadTime / created_at / updated_at`。
  - `(userId, groupId)` 组合唯一索引。
  - `lastReadTime` 默认值为启动前极早的时间，或允许 NULL（NULL 时视为从未阅读，全部未读）。
  - Repository：`GroupReadStateRepository`，提供 `findByUserIdAndGroupId`、`save`、`findByUserIdInGroups(List<groupId>, userId)`，以及通过 `@Modifying @Query` 的 upsert 操作。
- **Acceptance Criteria Addressed**: AC-1, AC-2
- **Test Requirements**:
  - `programmatic` TR-1.1: 通过 Repository 对同一 (userId, groupId) 重复 save 不会产生重复行，只更新 lastReadTime。
  - `programmatic` TR-1.2: `init-mysql.sql` 新建表定义包含唯一索引。
- **Notes**: 高并发场景可选 `@Lock(PESSIMISTIC_WRITE)`或直接使用 MySQL `INSERT ... ON DUPLICATE KEY UPDATE`（若需要可在 Repository 用 `@Query` + native 实现）。本任务默认以 unique 索引 + 捕获异常重 save 方式实现。

## [x] Task 2: 修改 MessageService / MessageRepository 计算未读
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 在 `MessageService.getRecentGroups` 中，不再使用前端传入的 `sinceTime` 全局时间戳。而是为每个群聊：
    1. 从 `GroupReadState` 查询该 user + group 的 `lastReadTime`
    2. 以 `messages.send_time > lastRead_time`（若 lastReadTime 为空取全部未归档）做未读统计。
    3. 返回每个群聊的 `unreadCount`。
  - 保持 `sinceTime` 参数的兼容（若前端仍传也可使用，但不推荐）。
  - 修改 `MessageRepository`：将原先的 `countUnreadMessages` / `countUnreadMessagesSince` 方法保留，并新增 `countUnreadMessagesAfter(Long userId, String groupId, LocalDateTime lastReadTime)`。
  - 注意：权限控制（用户只能读取绑定的 QQ 的群聊消息）维持现有逻辑不变。
- **Acceptance Criteria Addressed**: AC-2, AC-5
- **Test Requirements**:
  - `programmatic` TR-2.1: 对一个存在未读消息的群，标记已读后，再次查询未读数 = 0。
  - `programmatic` TR-2.2: 同一 `groupId` 归属两个不同用户时，一个用户的已读操作不影响另一个用户的未读。

## [x] Task 3: 新增标记已读 / 全部标已读 API
- **Priority**: high
- **Depends On**: Task 1, Task 2
- **Description**:
  - `POST /api/messages/read/{groupId}` —— 校验群是否属当前用户绑定的 QQ，然后 upsert `group_read_state` 的 lastReadTime 到当前时间。
  - `POST /api/messages/read-all` —— 批量更新当前用户所有群的 lastReadTime。
  - API 均要求 JWT 登录（@Authentication）。
  - 返回值：`{ success: true, message: "..." }`
- **Acceptance Criteria Addressed**: AC-3, AC-5, AC-6
- **Test Requirements**:
  - `programmatic` TR-3.1: 未登录调用报 401。
  - `programmatic` TR-3.2: 调用 `read/{groupId}` 后再次查询未读 = 0。
  - `programmatic` TR-3.3: 并发 100 次调用，只产生 1 行 `group_read_state`。

## [x] Task 4: 前端 Sidebar 点击 li 调 read API + 刷新未读
- **Priority**: high
- **Depends On**: Task 3
- **Description**:
  - 在 `frontend/src/components/Sidebar.vue` 中，`selectGroup(groupId)` 在触发 `emit('select-group', groupId)` 之前，调用 `messageApi.markGroupAsRead(groupId)`，成功后本地将该群的 `unreadCount = 0`。
  - 不再写入/读取 `localStorage.lastReadTime`（保留但不推荐，可删除）。
  - 刷新定时 60s 刷新一次群聊列表保持未读数最新。
  - 数字显示： `group.unreadCount && group.unreadCount > 0 && !collapsed` 时显示，> 99 显示 `99+`。
- **Acceptance Criteria Addressed**: AC-3, AC-4, AC-6
- **Test Requirements**:
  - `programmatic` TR-4.1: 点击 li 后，页面立即显示未读数为 0。
  - `human-judgement` TR-4.2: 界面上 99+、普通数字显示正常。

## [x] Task 5: 更新 SQL 初始化脚本 + README
- **Priority**: medium
- **Depends On**: Task 1, Task 2, Task 3, Task 4
- **Description**:
  - 在 `backend/src/main/resources/init-mysql.sql` 中新增 `group_read_state` 表定义。
  - 在 `README.md` 的数据库设计章节补充该表说明。
- **Test Requirements**:
  - `programmatic` TR-5.1: 执行 `init-mysql.sql` 后 `SHOW TABLES` 可看到 `group_read_state`。
