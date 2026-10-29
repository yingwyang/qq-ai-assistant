---
title: 多机器人（每人一个自己的机器人）
description: 多机器人数据模型、远程 agent、接入流程与当前边界
updated: 2026-10-30
---

# 多机器人

目标：每个用户可以拥有自己的 QQ 机器人（独立 QQ 号/运行环境），数据按 `bot_id` 隔离；默认机器人（id=1）保持原单 bot 行为。

## 运行模型

- **LOCAL**：本机 NapCat.Shell（当前只支持 id=1 默认机器人）。
- **REMOTE**：独立机器/Windows 用户/VM/容器上的 NapCat，通过 OneBot URL + WebUI URL + agent 接入。
- agent 参考实现：`deploy/napcat-agent/`（NapCat + agent compose，注册/心跳/二维码/登录态）。

## 关键接口

| 接口 | 说明 |
|---|---|
| GET/POST `/api/bots` | 我的机器人 / 创建 |
| PATCH `/api/bots/{id}/runtime` | 切换 LOCAL/REMOTE、更新 URL |
| POST `/api/bots/{id}/agent/register` | agent 上报地址（X-Agent-Token） |
| POST `/api/bots/{id}/heartbeat` | agent 心跳（X-Agent-Token） |
| POST `/api/bots/{id}/agent-token/rotate` | 轮换 token |
| GET `/api/bots/{id}/qrcode-image` | 二维码（LOCAL 读本机缓存，REMOTE 代理 agent） |
| GET `/api/bots/{id}/login-status` | 登录态 |

## 数据隔离（当前进度）

- `bots`、`messages.bot_id`、`chat_groups.bot_id` 已落库；老数据默认 1。
- webhook：全局 token → 默认 bot；其他 token 按 `agentToken` 反查 bot。
- 读接口已支持 `botId`：主消息列表、paged、since、recent-groups；非默认 bot 按 owner/admin 授权。
- 出站：`BotImRouter`；默认 bot 走本机 NapCat，REMOTE 走 bot 的 OneBot HTTP。
- 前端：Sidebar 机器人切换器 + 管理面板；消息接口自动带当前 `botId`。

## 已知边界（下一阶段）

1. 媒体（图片/视频/语音）任务与本地目录尚未按 bot 隔离；
2. AstrBot 会话仍按用户/会话，不按 bot 前缀；
3. 媒体发送、群成员等接口仍走默认 NapCat；
4. per-bot webhook token 与 agentToken 仍复用（后续拆分）；
5. 删除 bot 只删登记，不清理消息/媒体；
6. 同宿主同公网 IP 多 QQ 仍有风控，正式部署建议每 bot 独立出口；
7. 生产需显式 DDL 迁移（当前依赖 ddl-auto=update，Flyway 未启用）。
