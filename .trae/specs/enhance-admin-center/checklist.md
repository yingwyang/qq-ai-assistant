# 完善系统管理中心 - 验证检查清单

## 统一入口
- [x] AdminDashboard.vue 已废弃或删除
- [x] 从首页/侧边栏进入系统管理使用路由跳转 /admin
- [x] AdminView.vue 包含所有原 AdminDashboard 功能（媒体管理、组件控制等）

## Composable 拆分
- [x] useDashboardData.js 独立存在，负责统计/趋势/排行/分布
- [x] useComponentControl.js 独立存在，负责组件启停/NapCat 登录
- [x] useUserManagement.js 独立存在，负责用户列表/角色/启禁/删除
- [x] useMediaManager.js 独立存在，负责媒体文件管理
- [x] useSystemLog.js 独立存在，负责日志/审计
- [x] AdminView.vue script 行数 < 200
- [x] useAdminDashboard.js 已删除

## 用户管理增强
- [x] 用户列表支持分页（后端 page/size 参数）
- [x] 用户列表支持搜索关键词
- [x] 前端有搜索框和分页控件

## 导航配置化
- [x] AdminView 侧边栏导航由配置数组驱动
- [x] 包含所有 8 个标签页：概览、用户、组件、媒体、分布、日志、配置、维护

## 系统日志
- [x] 后端 GET /api/admin/logs 返回应用日志
- [x] 后端 AuditLog 实体和写入逻辑存在
- [x] 关键操作（角色变更、启停、删除）写入审计记录
- [x] 前端日志标签页可查看日志和审计记录

## 配置管理
- [x] 后端 GET /api/admin/config 返回运行时配置（secret 掩码）
- [x] 后端 PUT /api/admin/config 支持更新
- [x] 前端配置标签页可查看和编辑配置项

## 数据维护
- [x] 后端 POST /api/admin/backup 触发数据库备份
- [x] 后端 GET /api/admin/backup/{id}/download 提供备份下载
- [x] 后端 POST /api/admin/archive 触发消息归档
- [x] 前端维护标签页有备份和归档操作按钮

## 安全
- [x] /api/admin/** 路径统一限制 ADMIN 角色
- [x] 新 Controller 路径均在 /api/admin/ 下
