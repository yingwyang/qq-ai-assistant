# 完善系统管理中心 Spec

## Why
当前系统管理中心存在两套重复组件（AdminView + AdminDashboard）、AdminView 为 1000+ 行巨型单文件、缺少运维必需的系统日志/配置/审计/备份功能、图表用纯 SVG 手写难以维护、用户管理无分页。本 spec 统一并增强管理中心，使其具备生产级运维能力。

## What Changes
- 消除 AdminDashboard.vue 和 AdminView.vue 的功能重复，统一为 AdminView.vue 单一入口
- 将 AdminView.vue 的巨型 setup() 按功能拆分为 composable：`useDashboardData`、`useComponentControl`、`useUserManagement`、`useMediaManager`、`useSystemLog`
- 新增"系统日志"标签页：查看后端日志、操作审计记录
- 新增"配置管理"标签页：在线查看和编辑 application.yml 关键配置项
- 新增"数据维护"标签页：数据库备份触发与下载、消息归档触发
- 用户管理增加分页与搜索
- 侧边栏导航改为配置式数组驱动，减少重复代码

## Impact
- Affected specs: 系统管理中心所有功能
- Affected code: `AdminView.vue`、`AdminDashboard.vue`（将被删除或大幅精简）、`useAdminDashboard.js`、`Sidebar.vue`、`api.js`、后端新增 `LogController`、`ConfigController`、`BackupController`

## ADDED Requirements

### Requirement: 管理中心统一入口
The system SHALL 将 AdminDashboard.vue 的功能合并到 AdminView.vue，删除 AdminDashboard.vue 作为独立面板的用法。HomeView 中的"系统管理"按钮改为路由跳转到 `/admin`，不再弹窗。

#### Scenario: 从首页进入管理中心
- **WHEN** 用户点击侧边栏"系统管理"
- **THEN** 路由跳转到 `/admin`（全页面管理中心，非弹窗）

### Requirement: Composable 按功能拆分
The system SHALL 将 AdminView.vue 的 setup() 逻辑按职责拆分为以下 composable：
- `useDashboardData()`：统计卡片、消息趋势、群/QQ 排行、消息类型分布、磁盘使用
- `useComponentControl()`：组件启停、状态轮询、NapCat 登录/二维码
- `useUserManagement()`：用户列表（分页+搜索）、角色切换、启用/禁用、删除
- `useMediaManager()`：媒体文件列表、预览、批量删除、按类型清理
- `useSystemLog()`：系统日志查看、操作审计

#### Scenario: AdminView.vue 代码量
- **THEN** AdminView.vue script 部分不超过 200 行，仅负责组合 composable 和模板渲染

### Requirement: 系统日志标签页
The system SHALL 提供"系统日志"管理标签页，展示后端应用日志和操作审计记录。

#### Scenario: 查看最近日志
- **WHEN** 管理员切换到"系统日志"标签页
- **THEN** 显示最近的应用日志条目（时间、级别、消息），支持按级别过滤（INFO/WARN/ERROR）

#### Scenario: 查看操作审计
- **WHEN** 管理员切换到审计子标签
- **THEN** 显示操作审计记录（谁、何时、做了什么、结果），如：用户角色变更、组件启停、文件删除

### Requirement: 配置管理标签页
The system SHALL 提供"配置管理"标签页，允许管理员查看和编辑后端关键配置项。

#### Scenario: 查看当前配置
- **WHEN** 管理员打开"配置管理"标签页
- **THEN** 以分组形式展示当前运行时配置（AstrBot URL/Token、NapCat URL/Token、GPT-SoVITS URL、MinIO 配置等），secret 字段以 `***` 掩码显示

#### Scenario: 修改配置
- **WHEN** 管理员修改某配置项并提交
- **THEN** 后端热更新该配置（如果支持），或提示需重启生效

### Requirement: 数据维护标签页
The system SHALL 提供"数据维护"标签页，包含数据库备份和消息归档功能。

#### Scenario: 触发数据库备份
- **WHEN** 管理员点击"立即备份"
- **THEN** 后端执行数据库 dump，完成后提供下载链接

#### Scenario: 触发消息归档
- **WHEN** 管理员点击"归档旧消息"并指定天数
- **THEN** 后端将超过指定天数的消息移至归档表，释放主表空间

### Requirement: 用户管理分页与搜索
The system SHALL 为用户管理列表增加分页和搜索功能。

#### Scenario: 搜索用户
- **WHEN** 管理员在搜索框输入关键词
- **THEN** 用户列表实时过滤，仅显示匹配的用户名/昵称

#### Scenario: 分页浏览
- **WHEN** 用户数量超过每页条数（默认 20）
- **THEN** 显示分页控件，支持翻页

## MODIFIED Requirements

### Requirement: 管理中心侧边栏导航
- AdminView.vue 的侧边栏导航改为配置式数组驱动
- 新增标签页：日志（log）、配置（config）、维护（maintenance）
- 导航项定义：`[{ key: 'dashboard', icon: 'dashboard', label: '数据概览' }, { key: 'users', icon: 'group', label: '用户管理' }, { key: 'components', icon: 'settings', label: '组件控制' }, { key: 'media', icon: 'file', label: '媒体管理' }, { key: 'distribution', icon: 'chart', label: '消息分布' }, { key: 'log', icon: 'log', label: '系统日志' }, { key: 'config', icon: 'config', label: '配置管理' }, { key: 'maintenance', icon: 'backup', label: '数据维护' }]`

## REMOVED Requirements

### Requirement: AdminDashboard.vue 作为独立弹窗面板
**Reason**: 与 AdminView.vue 功能重复，维护两套代码增加负担
**Migration**: HomeView 中的 `open-admin-dashboard` 事件改为路由跳转 `/admin`；AdminDashboard.vue 删除或标记 @deprecated
