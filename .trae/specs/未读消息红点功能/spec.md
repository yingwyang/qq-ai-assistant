# 未读消息红点与数字标记功能 - 产品需求文档

## Overview
- **Summary**: 在铃音QQ对话系统中为侧边栏的群聊项添加未读消息红点/数字显示功能，并在用户点击某群聊项时将该群聊的未读消息标记为已读。
- **Purpose**: 解决当前未读计数依赖全局 `lastReadTime`（所有群聊共享一个时间戳），无法区分不同群聊的已读状态，且消息列表中红点显示逻辑不精确、刷新错乱等问题。
- **Target Users**: 所有系统用户（含管理员）。

## Goals
- **G1**: 为每个（用户-群聊）对维护已读状态：记住每个群聊最新已读时间，刷新消息列表时，右侧栏显示未读消息数。
- **G2**: 在侧边栏中展示未读红点（≤99 显示数字，>99 显示“99+”，为 0 不显示）。
- **G3**: 点击侧边栏群聊项时，该群聊标记为“已读至最新”，并刷新该群聊未读计数。
- **G4**: 保持现有权限模型不变，确保跨设备（同一个系统用户跨浏览器保持一致的已读状态）。
- **G5（可选）: 提供高并发写入场景（大量消息并发刷新、大量用户）稳定运行 —— 见下方非功能需求说明）。

## Non-Goals（Out of Scope）
- 不在 `messages` 表增加逐行 `read` 字段来标记。我们使用进度表而非改动 messages 单条。
- 不做已读回执（QQ 聊天里对方是否已读 —— 本需求指的是系统用户自己的未读消息标记）。
- 不做移动端适配（如 iOS/Android 原生 APP）。
- 不修改 NapCat / AstrBot 组件的 Webhook 上报逻辑。

## Background & Context
当前实现问题：
1. 前端 `Sidebar.vue` 使用单一 `localStorage.lastReadTime`（一个时间戳，所有群聊共享）
2. 后端 `MessageService.getRecentGroups(qq, sinceTime)` 将 `sinceTime` 用于 count unread，但这只是全局时间戳，无法精确到每个群聊
3. `messages` 表未保存任何已读状态字段，消息不分用户级别
4. 同一系统用户可以同时绑定多个 QQ，消息按群号区分

我们的解决方法：新增一张 `group_read_state`（群聊阅读进度表），以 `userId + groupId` 为唯一键，记录某群聊最后一次阅读时间；查询时用该时间之后的消息数作为未读计数。

## Functional Requirements
- **FR-1**: `group_read_state` 表：字段 `id / user_id / group_id / last_read_time / created_at / updated_at`。主键 `(user_id, group_id)` 唯一约束。
- **FR-2**: 新实体 `GroupReadState` + Repository，提供 upsert（存在则更新，不存在则插入）。
- **FR-3**: `POST /api/messages/read/{groupId}` ——标记某群聊为已读（更新 `last_read_time = 为当前时间）。
- **FR-4**: `POST /api/messages/read-all` ——将当前用户的所有群聊标为已读（可选按钮，但建议实现）。
- **FR-5**: 修改 `MessageService.getRecentGroups` 从 `group_read_state` 查询每个群聊的 `last_read_time`，用它计算未读数量，不再使用 sinceTime。保留兼容。
- **FR-6**: 前端 Sidebar 中，点击群聊 li 时，立刻调用 `read/{groupId}` 接口，成功后从本地刷新未读红点。
- **FR-7**: 未读红点 UI：数字 ≤99 显示数字，>99 显示 “99+”，0 不显示。

## Non-Functional Requirements
- **NFR-1 性能**: 查询未读数应在 ≤200ms内返回（单用户 50 个群聊，每群 ≤10万消息）。
- **NFR-2 并发性（可选）: 使用 `INSERT ... ON DUPLICATE KEY UPDATE` 或 JPA 的 `save` 配合唯一索引，确保并发点击不会插入重复行。若部署时若期望更高并发可将 upsert + index 覆盖（详见 tasks 章节）。
- **NFR-3 数据一致性**: 未读计数以 `messages.send_time > group_read_state.last_read_time` 为准。
- **NFR-4 安全**: 标记已读必须登录用户只能操作该用户绑定的 QQ 号所属群聊。

## Constraints
- 技术栈：Spring Boot 3.x（JPA / Hibernate / MySQL 8.x），Vue 3 + Vite。
- 数据库：MySQL（MySQL 8.x 需 utf8mb4）。
- 外部依赖：无新增第三方依赖。

## Assumptions
- 每个系统用户可通过 JWT 登录（@ Authentication.getName() 作为 username。
- groups 表为 `chat_groups`，owner_qq 标识归属的owner_qq = owner_qq = messages.self_qq。
- "用户点击的所有消息的" 消息已读"指" 不是以"

## Acceptance Criteria

### AC-1: 新增 group_read_state 表
- **Given**: 后端已启动，数据库已创建
- **When**: 执行 init-mysql.sql 或由 JPA 自动创建表结构
- **Then**: `group_read_state` 表以正确的字段与索引
- **Verification**: `programmatic`

### AC-2: 消息未读计数正确
- **Given**: 用户A 某群聊之前有最近一条消息 T1，用户最后一次在 T0 点击过
- **When**: 用户刷新最近对话列表
- **Then**: 未读数 = 该群内 T0 之后的消息数量
- **Verification**: `programmatic`

### AC-3: 点击群聊后未读清零
- **Given**: 群聊未读数为 5，用户点击该群聊项
- **When**: 调用 `POST /api/messages/read/{groupId}`
- **Then**: 下次刷新 / 页面立即将未读计数为 0（直到下一条新消息到来前）。
- **Verification**: `human-judgment`

### AC-4: 未读红点 UI
- **Given**: 未读数 = 3 / 150
- **When**: 渲染侧边栏
- **Then**: 3 → "3"；150 → "99+"
- **Verification**: `human-judgment`

### AC-5: 权限隔离
- **Given**: 用户A 与 用户B 绑定不同 QQ
- **When**: 用户A 点击某群聊
- **Then**: 用户B 同群聊未读数不变
- **Verification**: `programmatic`

### AC-6: 并发安全标记已读
- **Given**: 同一用户并行 100 次点击同一群聊
- **When**: 全部执行完毕
- **Then**: `group_read_state` 仅有一行，updated_at 最大那行被更新，未读数始终为 0
- **Verification**: `programmatic`（建议通过手动并发测试 / JUnit）

## Open Questions
- [x] 直接在 messages 表增加 read 字段 —— 决定：不加单条的 `read/ unread 逐行标记；使用进度表更适合高并发。
- [x] 是否要求支持 "全部标已读"的 API —— 实现作为可选 FR-4。
