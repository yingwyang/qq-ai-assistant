# 完善系统管理中心 - 实现计划

## Task 1: 合并 AdminDashboard 到 AdminView + 路由跳转
- [x] 1.1 将 AdminDashboard.vue 中的媒体管理功能（表格、预览、分页、批量删除）迁移到 AdminView.vue 的"媒体管理"标签页
- [x] 1.2 将 useAdminDashboard.js 中的媒体管理逻辑提取到新 composable `useMediaManager.js`
- [x] 1.3 修改 HomeView.vue / Sidebar.vue 中 `open-admin-dashboard` 事件处理，改为 `router.push('/admin')`
- [x] 1.4 删除或废弃 AdminDashboard.vue

## Task 2: Composable 按功能拆分
- [x] 2.1 创建 `useDashboardData.js`：统计卡片、消息趋势、群/QQ 排行、消息类型分布、磁盘使用
- [x] 2.2 创建 `useComponentControl.js`：组件启停、状态轮询、NapCat 登录/二维码
- [x] 2.3 创建 `useUserManagement.js`：用户列表、角色切换、启用/禁用、删除（含分页+搜索）
- [x] 2.4 重构 AdminView.vue：仅保留模板 + 组合各 composable，script < 200 行
- [x] 2.5 删除 `useAdminDashboard.js`（已被拆分的 composables 替代）

## Task 3: 用户管理增加分页与搜索
- [x] 3.1 后端 `AdminController.getUsers()` 增加分页参数（page, size, keyword）
- [x] 3.2 前端 `useUserManagement.js` 增加搜索框和分页控件
- [x] 3.3 api.js 中 `adminApi.getUsers()` 支持传入分页和搜索参数

## Task 4: 侧边栏导航配置式驱动
- [x] 4.1 将 AdminView.vue 硬编码的导航项改为 `navItems` 配置数组
- [x] 4.2 新增导航项：日志（log）、配置（config）、维护（maintenance）
- [x] 4.3 模板中用 `v-for` 渲染导航项

## Task 5: 系统日志标签页
- [x] 5.1 后端创建 `LogController`：`GET /api/admin/logs` 返回最近应用日志，支持 level 过滤和分页
- [x] 5.2 后端创建 `AuditLog` 实体和 Repository，记录操作审计（谁、何时、操作类型、目标、结果）
- [x] 5.3 后端创建 `AuditLogService`，在关键操作（角色变更、启停组件、删除文件/用户）中写入审计记录
- [x] 5.4 前端新增"系统日志"标签页，包含日志查看和审计记录两个子标签
- [x] 5.5 api.js 新增 `adminApi.getLogs()` 和 `adminApi.getAuditLogs()`

## Task 6: 配置管理标签页
- [x] 6.1 后端创建 `ConfigController`：`GET /api/admin/config` 返回当前运行时配置（secret 掩码）、`PUT /api/admin/config` 更新配置
- [x] 6.2 后端创建 `ConfigService`，读取 application.yml 当前值，支持动态更新（通过刷新 Spring Environment 或标记需重启）
- [x] 6.3 前端新增"配置管理"标签页，分组展示配置项，secret 显示 `***`，支持编辑和提交
- [x] 6.4 api.js 新增 `adminApi.getConfig()` 和 `adminApi.updateConfig()`

## Task 7: 数据维护标签页
- [x] 7.1 后端创建 `BackupController`：`POST /api/admin/backup` 触发数据库备份、`GET /api/admin/backup/{id}/download` 下载备份文件
- [x] 7.2 后端 `BackupService` 执行 H2/MySQL dump，备份文件存入配置目录
- [x] 7.3 后端在 `MessageArchiveService` 上增加 HTTP 入口：`POST /api/admin/archive?days={n}` 触发归档
- [x] 7.4 前端新增"数据维护"标签页，包含备份触发/下载和归档触发
- [x] 7.5 api.js 新增 `adminApi.triggerBackup()`、`adminApi.downloadBackup()`、`adminApi.triggerArchive()`

## Task 8: 后端 SecurityConfig 放行新接口
- [x] 8.1 在 SecurityConfig 中将 `/api/admin/**` 统一限制为 `hasRole("ADMIN")`
- [x] 8.2 确保新创建的 LogController、ConfigController、BackupController 路径在 `/api/admin/` 下

# Task Dependencies
- Task 2 depends on Task 1（先合并再拆分）
- Task 3 可与 Task 4 并行
- Task 5、6、7 可互相并行
- Task 8 depends on Task 5、6、7（所有新 Controller 路径确定后统一配置）
