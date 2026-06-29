# 未读消息红点与数字标记功能 - 验证清单

- [ ] **Checkpoint 1: 数据库/实体层**
  - [ ] `GroupReadState.java` 已创建，字段完整。
  - [ ] `GroupReadStateRepository.java` 已创建，含 upsert 逻辑。
  - [ ] `init-mysql.sql` 中包含新表定义与唯一索引。

- [ ] **Checkpoint 2: 未读计数查询**
  - [ ] `MessageService.getRecentGroups` 改用 `group_read_state` 查询每个群聊的 lastReadTime。
  - [ ] `MessageRepository` 新增/修改对应查询方法。
  - [ ] 未读计数以 `messages.send_time > group_read_state.last_read_time` 为基准。

- [ ] **Checkpoint 3: 标记已读 API**
  - [ ] `POST /api/messages/read/{groupId}` 返回 JSON 并更新 lastReadTime。
  - [ ] `POST /api/messages/read-all` 能批量更新。
  - [ ] 未登录访问返回 401，非绑定群访问返回 403。

- [ ] **Checkpoint 4: 前端交互**
  - [ ] 点击侧边栏群聊 li 后，调用 read API 并刷新未读数。
  - [ ] 数字 ≤ 99 显示实际数；> 99 显示 `99+`；为 0 不显示。
  - [ ] 本地不再依赖全局 `localStorage.lastReadTime`。

- [ ] **Checkpoint 5: 编译与冒烟测试**
  - [ ] `mvn compile` / `mvn spring-boot:run` 后端正常启动。
  - [ ] `npm run dev` 前端正常启动。
  - [ ] 手动登录后访问主页，查看侧边栏未读数显示正确。
  - [ ] 手动在数据库插入测试消息，点击群聊后验证未读清零。

- [ ] **Checkpoint 6: 并发/性能（可选高级）**
  - [ ] 100 次并行点击同一群聊不产生重复行。
  - [ ] 单次未读查询响应时间 ≤ 200ms。
