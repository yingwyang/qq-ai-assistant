# 用户删除消息 & 清理文件 - 产品需求文档 (PRD)

## Overview

- **Summary**: 为铃音QQ对话系统添加消息删除功能和文件清理功能，包括：文本消息与非文本消息（图片 / 视频 / 语音 / 文件）在前端通过"删除"按钮进行删除（在数据库中标记为已删除，从前端列表隐藏）；另外在侧边栏"系统管理"区域新增"清理媒体文件"按钮，可按用户选择的类型删除磁盘中的图片/视频/音频文件，删除后剩余指向该文件的消息会显示"图片已删除"、"视频已删除"等占位图或"音频已删除"提示。
- **Purpose**: 解决用户"消息越堆越多，想清理一部分"的需求；以及"磁盘占用大，但消息记录还想保留"的需求；并优雅处理因文件缺失导致的渲染异常。
- **Target Users**: 所有登录用户（每个用户仅能删除自己绑定QQ下的消息）。

## Goals

1. **G1 - 消息删除按钮**：用户在聊天界面选中若干条消息后，点击"删除"按钮（`btn-delete`），确认后，消息从前端列表消失；数据库保留该行但标记为"已删除"（软删除），且可选择同时删除对应的图片/视频/音频文件（可选）。
2. **G2 - 删除媒体文件入口（清理内存）**：在侧边栏"系统管理"下新增"清理媒体文件"按钮（以 span 文本显示），弹出确认框后，删除用户选定类型（图片 / 视频 / 音频 / 全部）在后端本地存储目录中的物理文件。
3. **G3 - 丢失文件的消息优雅回退**：图片类消息在图片无法加载时显示"图片已删除"占位图；视频类消息在视频无法加载时显示"视频已删除"占位图；音频/语音点击播放时弹出短时提示"音频已删除"。
4. **G4 - 权限与隔离**：用户只能删除自己绑定QQ下的消息/文件；跨用户消息（不同 selfQq）不会互相影响。
5. **G5 - 友好反馈**：删除成功后，Toast 提示成功条数；删除失败或无权限时，给出明确错误提示。

## Non-Goals (Out of Scope)

- 不做"批量恢复已删除消息"（删除后仅能通过管理员直接改数据库恢复）。
- 不做文件定时自动清理（可后续扩展，不属本次需求）。
- 不做远程对象存储（OSS/COS/MinIO）文件删除；本次仅实现"本地文件系统"的删除逻辑。
- 不做文本内容审核与二次确认以外的额外判断。

## Background & Context

当前实现：
1. 后端 `messages` 表有 `archived = false` 字段用于"归档"，但没有显式"用户删除"的语义字段（`deleted`）。
2. 前端 ChatInterface.vue 中已有 `btn-delete` 按钮，其实现调用 `messageApi.deleteMessages(ids)`，但后端 `MessageService.deleteMessagesByIds` 只是把 `archived=true`，没有做文件级别的清理。
3. 消息中图片URL、视频URL、语音URL通过 `message.content` / `message.fileId` 描述，但前端 MessageContent.vue 对 `404` 或损坏的文件没有占位图或降级逻辑。
4. 本地文件存储目录在 `backend/uploads/images|video|voice|files` 或 `backend/src/main/resources/static/images/*`（按 `NapCatService` 中的代码）。
5. 侧边栏 Sidebar.vue 中有"系统管理"导航项（Icon=settings, nav-text="系统管理"），我们在此区域扩展一个新的 span "清理媒体文件"入口。

方案核心：
- 软删除：在 `messages` 表新增 `deleted (BOOL, default false)` + `deletedAt (TIMESTAMP)` + `deletedBy (用户ID)`。
- 已有的 `archived` 字段语义保持不变（后台归档逻辑）。
- 所有消息列表 API 在查询时加过滤：`m.deleted = false`（或等价语义字段）。
- 清理媒体文件：递归遍历上传目录下选定类型的文件，将其物理删除；并可选将对应 `file_records` 表的记录标记 `active=false`。
- 前端 MessageContent.vue 对图片/视频/img `@error` + 视频 `@error` 触发"已删除"占位图；音频点击播放时，若 URL 不能加载则弹 "音频已删除" Toast。

## Functional Requirements

**FR-1 - 数据库变更**
- `messages` 表新增字段：`deleted BOOLEAN NOT NULL DEFAULT FALSE`；`deletedAt TIMESTAMP NULL`；`deletedBy BIGINT NULL`（关联 users.id）。
- 建索引：`idx_deleted (deleted)` / `idx_self_deleted (self_qq, deleted)` 便于按登录用户维度过滤。

**FR-2 - 后端消息删除 API**
- `POST /api/messages/delete-batch`（已有）：入参 `{ ids: [messageId...] }`，仅当 `message.selfQq ∈ 当前用户绑定QQ列表` 时才能删除，否则返回 403。
- 删除动作：设置 `deleted=true`，`deletedAt=NOW()`，`deletedBy=当前用户ID`；保留 `archived` 原值。
- 返回：`{ success: true, deletedCount: N }`。
- 若用户希望"同时删除关联的文件"，额外参数 `{ deleteMedia: true }`：对选中消息里的 IMAGE / VIDEO / AUDIO / VOICE 调用文件删除逻辑。

**FR-3 - 清理媒体文件 API**
- `POST /api/messages/purge-media`：入参 `{ types: ["IMAGE", "VIDEO", "AUDIO"] }`（三者子集，也可传 `ALL`）。
- 行为：
  - 递归扫描本地 `uploads/images`, `uploads/video`, `uploads/voice`, `uploads/files` 等目录；
  - 对匹配到的扩展文件执行 `File.delete`；
  - 对 `file_records` 表对应记录置 `active=false`（如果表有 active 字段，或直接在查询中过滤不存在文件的 fileId）。
- 返回：`{ success: true, totalDeleted, freedSizeBytes, types: [] }`。

**FR-4 - 消息列表 API 过滤已删除**
- 所有查询消息列表的 `Repository` 方法：在原有 `archived=false` 外，再加上 `deleted=false` 过滤条件。
- 统计接口 `countActiveMessages*` 同样排除 `deleted=true`。

**FR-5 - 前端删除按钮（ChatInterface.vue 的 `btn-delete`）**
- 保持原有勾选 → 点击删除 → 二次确认逻辑；
- 新增"同时删除关联媒体文件"复选框（默认不勾选），用于触发 `deleteMedia=true`；
- 成功后，从本地列表移除已删除消息（或强制重刷），并 Toast 提示删除成功数。

**FR-6 - 侧边栏"清理媒体文件"入口**
- 在 Sidebar.vue 的"系统管理"区块下方，新添加一个展开后的"清理媒体文件"项（span 形式），点击后弹出"确认清理对话框"，让用户勾选图片 / 视频 / 音频，点击确认后调用 `POST /api/messages/purge-media`。
- 成功后 Toast 提示释放了多少磁盘空间。

**FR-7 - 消息内容占位图与降级**
- 图片：`<img @error="onImageError">` 错误时将 `imageUrl` 切换到 `/images/placeholders/image-deleted.svg`（一张 200x200 的灰色图，带"图片已删除"字样）。
- 视频：`<video @error="onVideoError">` 或在 DOM 中检测到 404 时，切换到 `video-deleted.svg`。
- 音频 / 语音：`voiceAudio.onerror` 时直接 showToast("音频已删除", "info")。
- 所有占位图打包在 `frontend/src/assets/placeholders/` 下；或者通过后端 `/images/placeholders/*` 路径访问静态资源。

**FR-8 - 管理员可选视图**
- 管理员在后台查看消息时，可以看到"已删除"状态的消息，便于审计（不影响普通用户视图）。

## Non-Functional Requirements

- **NFR-1 性能**：单次删除 ≤ 5000 条消息时响应时间 < 3 秒；清理媒体文件时按"先扫描 → 先计数 → 再执行"避免阻塞。
- **NFR-2 权限**：必须登录；删除消息必须属于当前用户绑定QQ；清理文件所有用户都可用（默认不影响他人文件，因为目录是全局的，可后续细化为 per-user）。
- **NFR-3 健壮性**：文件删除失败不影响消息删除；已不存在的文件可跳过。
- **NFR-4 可观测性**：后端对删除动作打日志 `WARN: user <id> deleted N messages`，对 purge-media 动作打日志 `INFO: user <id> purged X files (Y MB)`。

## Constraints

- 技术栈：Spring Boot 3 / JPA / MySQL 8；Vue 3 + Vite。
- 存储：仅本地文件系统（`backend/uploads/*`）。不强制要求 MinIO / OSS。
- 数据库：必须支持新增列（MySQL 的 `ALTER TABLE`）。

## Assumptions

- 图片 URL 以 `/images/images/<sub>/xxx.jpg` 或 `/images/...` 形式映射到 `backend/uploads/images`。
- `message.content` 里的 CQ 码格式 `[CQ:image,file=xxx,url=http://...]` 或 `[CQ:video,...]` 已经在前端 `MessageContent.vue` 的 computed 中解析为 URL。
- 当前系统没有"回收站"需求，删除即永久不可恢复（数据库仍保留行，但前端不可见）。

## Acceptance Criteria

### AC-1 消息软删除
- **Given**：用户已登录并选中若干条消息；这些消息的 `selfQq` 属于用户绑定的QQ号。
- **When**：用户点击"删除"按钮并确认。
- **Then**：
  - 后端消息行 `deleted=true, deletedAt=NOW, deletedBy=当前用户`。
  - 前端消息从列表中消失；再次刷新页面也不会再出现。
  - Toast 提示 `成功删除 N 条消息`。
- **Verification**: `programmatic`（HTTP 单元测试 + SQL 验证）。

### AC-2 非文本消息可选删除关联文件
- **Given**：用户选中若干条含图片/视频/语音的消息，勾选"同时删除关联媒体文件"。
- **When**：确认删除。
- **Then**：对应本地文件被删除；前端刷新后该消息显示"图片/视频已删除"或"音频已删除"。
- **Verification**: `programmatic`（检查文件系统是否还有该文件）+ `human-judgment`（UI 展示是否正确）。

### AC-3 权限拒绝
- **Given**：用户尝试删除不属于自己绑定QQ的消息。
- **When**：调用删除 API。
- **Then**：返回 403；返回体 `{ error: "无权删除这些消息" }`。
- **Verification**: `programmatic`。

### AC-4 清理媒体文件入口
- **Given**：用户已登录，侧边栏展开。
- **When**：用户点击"系统管理"区块下的"清理媒体文件"（span）。
- **Then**：弹出确认框选择清理的类型。
- **Verification**: `human-judgment`。

### AC-5 清理媒体文件成功
- **Given**：用户在确认框中选择"图片+视频"。
- **When**：点击"确认"。
- **Then**：
  - 后端递归扫描对应上传目录并删除匹配文件。
  - 前端 Toast 提示 `成功释放 XX MB 空间 (共 YY 个文件)`。
- **Verification**: `programmatic`（检查文件系统）+ `human-judgment`（Toast）。

### AC-6 图片文件丢失
- **Given**：图片消息对应的本地图片已被清理。
- **When**：打开该群聊消息列表。
- **Then**：图片位置显示"图片已删除"占位图；不再是破碎图标。
- **Verification**: `human-judgment`。

### AC-7 视频文件丢失
- **Given**：视频消息对应的本地视频文件已被清理。
- **When**：渲染视频消息。
- **Then**：显示"视频已删除"占位图（或可替代的自定义 UI）。
- **Verification**: `human-judgment`。

### AC-8 音频文件丢失
- **Given**：音频消息对应的本地音频文件已被清理。
- **When**：用户点击语音气泡播放。
- **Then**：弹出短时 Toast "音频已删除"，不报错。
- **Verification**: `human-judgment`。

### AC-9 未登录/无权限拒绝
- **Given**：用户未登录。
- **When**：直接 HTTP 调用 delete-batch / purge-media。
- **Then**：返回 401。
- **Verification**: `programmatic`。

### AC-10 文件不存在时忽略
- **Given**：某些消息对应的文件在删除前已被手动从磁盘移除。
- **When**：执行"删除媒体文件"。
- **Then**：不报错；跳过不存在的文件，继续处理其他文件。
- **Verification**: `programmatic`。

## Open Questions

- [x] Q: 清理媒体文件是否需要区分不同用户（每个用户只清理自己的上传）？
  - A: 本项目消息文件目前以全局目录（`uploads/images|video|voice|files`）组织，所以当前不做用户隔离；后续可根据需求为每条消息打上 `uploaderId` 再细化权限。
- [x] Q: 删除按钮是否要支持"单条消息右键删除"？
  - A: 本次实现复用已有批量删除，不单独实现右键删除。
- [x] Q: 前端占位图是否允许自定义？
  - A: 本项目以一张 `image-deleted.svg` / `video-deleted.svg` 作为默认样式。
