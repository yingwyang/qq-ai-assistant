---
title: 组件插件化架构（Phase 1–4）
description: 能力契约、组件编排、状态机与故障注入
updated: 2026-10-29
---

# 组件插件化架构

三件外部组件（AstrBot / NapCat / GPT-SoVITS）从「controller 直连 + 三份硬编码启停」改为「能力契约 + 适配器 + 组件描述符 + 统一编排」。

## 分层

- capability.chat：ChatProvider / ChatRequest / ChatReply / ChatPurpose（CHAT 严格、ANALYSIS 宽松）
- capability.im：ImGateway / ImSendResult / ImUploadResult（出站 + 登录；webhook 事件处理仍在原 controller）
- capability.tts：TtsProvider（合成 / 音色 / 切换）
- adapter.chat：AstrBotChatAdapter、AstrBotMediaSupport
- adapter.im：NapCatImAdapter
- adapter.tts：GptSovitsTtsAdapter
- component：ComponentDescriptor / ComponentRegistry / ComponentStateStore / SupervisedRuntime /
  DetachedLauncherRuntime / ComponentOrchestrator / ComponentHealthScheduler / ComponentController

依赖方向：controller → capability → adapter → 原 service/HTTP；component 层只依赖自身模型与 HealthStatus。

## 组件描述符

application.yml 的 component.components 描述 id、legacy-key、启动命令、工作目录、端口、探测、启动超时、停止策略与顺序。默认值与改造前逐项一致；AstrBot/GSV 为 SUPERVISED（日志 logs/components/<id>.log，停止只杀自己的进程树），NapCat 为 DETACHED_LAUNCHER（launcher 自提权，停止按进程名+端口）。

## 状态与 API

状态：STOPPED → STARTING → RUNNING；RUNNING ↔ DEGRADED（连续 3 次探测失败）；STOPPING → STOPPED；失败 → FAILED（带原因）。
状态落 backend/data/components-state.json；后端重启后按探测 adopt。

- GET /api/system/components（登录）
- POST /api/system/components/{id}/start|stop（ADMIN）
- 旧端点（start-astrbot 等、component-status）保留并委托。

## 故障注入清单（Phase 1 验收）

1. 连续点击启动 5 次 → 只产生一个进程
2. 命令路径写错 → FAILED + 原因
3. 就绪超时 → FAILED，不出现 RUNNING
4. 运行中 kill → 3 次探测后 DEGRADED；可再启动恢复
5. 端口被其他程序占用 → start 409，不误杀（2026-10-29 已实测：占位 6185 启动返回 409，占位进程存活，组件保持 STOPPED、不显示停止按钮）
6. 停止 → 只结束自己拉起的进程树（2026-10-29 已实测：停止 AstrBot 后 cmd/astrbot.exe/python/监听 PID 全部消失、端口释放；对「脱离进程树」的监听进程会按端口兜底清理）
7. 外部启动（owned=false）→ stop 需 force
8. 健康巡检不把 STOPPED 自动认领为 RUNNING；start 只在已跟踪 RUNNING/DEGRADED 时幂等返回

> 已知边界：后端启动时的组件对账仍会“探测可达即认领”（沿用改造前的 adopt 语义）。若启动瞬间端口被无关程序占用，会显示 RUNNING；此时请勿点停止，先手动确认占用进程。显式 start 已改为 409，不会误认领。

## 回滚

每个阶段独立提交，可单提交 revert；组件状态文件为运行时数据，删除即可重置。旧组件端点保留一个发布周期。
