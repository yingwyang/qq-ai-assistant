---
title: 收不到 QQ 消息
description: NapCat 掉线是最常见原因
updated: 2026-09-16
---

消息链路是：**QQ → NapCat（OneBot）→ 后端 8081 → MySQL / RabbitMQ → 前端**。任何一环断掉，现象都是「群里在聊，页面上没动静」。
本页按链路顺序排查，**第一条永远先确认 NapCat 是否真的登录着**。

## 先分清「哪一层断了」

**现象**：群里新消息在网页端完全不出现；或者历史消息都在，只有新消息不进来。

**原因**

| 现象 | 断点 | 先查 |
|---|---|---|
| 网页一条新消息都没有 | NapCat 未登录 / 掉线（最常见） | 本文第 2 节 |
| NapCat 已登录，后端日志无「收到NapCat消息」 | 上报地址或 token 不一致 | 本文第 3 节 |
| 日志有收到消息，页面却不显示 | 群未订阅 / 前端筛选 | 回到页面核对 |
| 消息在但媒体位置一直转圈 | 媒体异步下载卡住 | 见《媒体存不下来》 |

**处理步骤**

1. 先在后端日志里找分割线——**有没有收到上报**：

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Select-String -Path logs\application.log -Pattern '【根路径】收到NapCat消息|Webhook Token 验证失败|napcat.webhook-token 未配置' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }
```

2. 日志里**没有**「收到NapCat消息」→ 问题在 NapCat 侧，继续第 2 节；
3. 日志**有**「收到NapCat消息」→ 消息已进后端，查页面订阅与筛选；
4. 出现 `Webhook Token 验证失败` → 直接跳到第 3 节。

**验证**

```sql
USE qq_chat;
SELECT id, group_id, user_nickname, message_type, LEFT(content, 40) AS content, send_time
FROM messages ORDER BY id DESC LIMIT 5;
```

在群里发一条测试消息后，日志多出一行「收到NapCat消息」，且上面这条 SQL 能查到它。

## NapCat 未登录 / 掉线（最常见的根因）

**现象**：网页收不到任何新消息，但「组件控制」里 NapCat 显示**运行中**，群里机器人也没有反应。

**原因**：后台判断 NapCat 是否运行靠的是**端口 6099 是否在监听**（进程活着就算运行）；而端口活着**不代表 QQ 已登录**。NapCat 被踢下线或重连失败后，6099 照样监听，消息却断了。**判断登录状态必须用 OneBot 的 `get_login_info`，不能看端口。**

**处理步骤**

1. 用 OneBot 接口确认登录状态（`retcode:0` 且带 `user_id` 才算已登录）：

```powershell
curl.exe -s -X POST "http://127.0.0.1:6100/get_login_info" `
  -H "Authorization: Bearer <NAPCAT_TOKEN>" `
  -H "Content-Type: application/json" -d '{}'
```

2. 判读结果：

| 返回 | 含义 | 下一步 |
|---|---|---|
| `{"status":"ok","retcode":0,"data":{"user_id":<机器人QQ号>,…}}` | ✅ 已登录 | 跳到第 3 节查上报配置 |
| `retcode` 非 0 / 没有 `user_id` | ❌ 未登录或掉线 | 执行第 3 步重新扫码 |
| 连接被拒绝 / 超时 | NapCat 进程没起来 | 见《组件启动失败》 |

3. 重新扫码登录（两种入口任选）：
   - 管理后台 →「组件控制」→ NapCat 卡片下方的登录二维码（可点「刷新二维码」重新获取）；
   - 或直接打开 `http://127.0.0.1:6099/webui?token=<NAPCAT_TOKEN>`（后台的「打开 NapCat WebUI」会自动带上 token）。
4. 手机 QQ 扫码并确认登录，等待 NapCat 提示成功；
5. 登录后确认 OneBot 上报项（httpClients）处于启用状态。

**验证**

1. 再次执行 `get_login_info`，确认 `retcode:0` 且 `user_id` 是机器人 QQ 号；
2. 群里发消息，后端日志出现「【根路径】收到NapCat消息」；
3. 页面刷新后能看到这条消息；二维码文件位置：

```powershell
Test-Path "D:\ai\Documents\qq-web\napcat\NapCat.Shell\cache\qrcode.png"
```

> 提示：后台的登录状态接口在某些版本会误报（已登录却显示未登录）。**以 `get_login_info` 的返回为准**，这是唯一可信的判断。

## 上报地址或 access_token 与后端不一致

**现象**：NapCat 已登录、群里也有消息，后端日志却完全没有「收到NapCat消息」；或日志出现 `【安全】Webhook Token 验证失败`（401）／`napcat.webhook-token 未配置`（503）。

**原因**：后端接收上报的地址是 **`POST http://localhost:8081/`**（根路径），校验用的 token 是 `napcat.webhook-token`（环境变量 **`NAPCAT_WEBHOOK_TOKEN`**）。NapCat 侧地址、端口、路径或 token 任一处不对，上报就会被拒或发到别处；token 未配置时后端会**拒绝一切上报**（fail closed）。

| 后端日志 | 含义 |
|---|---|
| `Webhook Token 验证失败`（401） | NapCat 侧 token ≠ 后端 `NAPCAT_WEBHOOK_TOKEN` |
| `napcat.webhook-token 未配置`（503） | 后端没配 token，拒绝全部上报 |
| 一条日志都没有 | 地址不通：端口/路径错，或上报项被禁用 |

**处理步骤**

1. 打开 NapCat WebUI →「网络配置」，找到名为 **`铃音QQ对话后端`** 的 HTTP 客户端项；
2. 核对四项：

| 配置项 | 正确值 |
|---|---|
| 是否启用 | 启用 |
| 上报地址 URL | `http://localhost:8081/?access_token=<NAPCAT_WEBHOOK_TOKEN>` |
| Token | `<NAPCAT_WEBHOOK_TOKEN>`（与后端完全一致） |
| 消息格式 | `array`（消息段数组） |

3. 也可直接看配置文件 `D:\ai\Documents\qq-web\napcat\NapCat.Shell\config\onebot11_<QQ号>.json` 里 `network.httpClients` 的 `url` / `token` / `enable`；
4. 改完**重启 NapCat**（或重新加载网络配置）让配置生效；
5. 配置丢了就让后端按当前配置重写一份：后台「组件控制」→ NapCat「自动配置」，之后重启 NapCat。

**验证**

```powershell
# 只发一次探测请求：200 = 后端接受；401 = token 不一致；503 = 后端未配 token
curl.exe -s -o NUL -w "%{http_code}`n" -X POST "http://127.0.0.1:8081/?access_token=<NAPCAT_WEBHOOK_TOKEN>" `
  -H "Content-Type: application/json" -d '{"post_type":"meta_event","meta_event_type":"heartbeat"}'
```

再在群里发消息，日志应出现「收到NapCat消息」且**没有**任何 Token 相关错误，数据库 `messages` 表新增记录。

> 提示：后端只处理 `post_type` 为 `message` / `message_sent` 且 `message_type` 为 **`group`** 的事件；私聊消息与其它事件会被正常忽略，这不是故障。

## 消息入库了，但一直显示「加载中」（media_pending = 1）

**现象**：消息文字已经出现，图片/视频/语音位置一直转圈；SQL 里 `media_pending = 1` 长时间不变。

**原因**：这与「收不到消息」是**两件不同的事**——消息已成功入库，卡住的是**媒体异步下载**：RabbitMQ 未运行、后端消费者未订阅、`media.download.queue` 积压或下载卡死。

**处理步骤**

1. 查队列是否积压（管理台 <http://127.0.0.1:15672>，或见《媒体存不下来》）；
2. 看后端是否在消费：

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Select-String -Path logs\application.log -Pattern '消费媒体下载任务|媒体下载完成|【DLQ】' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }
```

3. 完全没有「消费媒体下载任务」→ 检查 RabbitMQ（5672）是否在跑、后端启动是否报 MQ 连接失败；
4. 按《组件启动失败》修好后回到本节验证。

**验证**

```sql
USE qq_chat;
SELECT id, message_type, media_pending, LEFT(content, 60) AS content
FROM messages WHERE media_pending = 1 ORDER BY id DESC LIMIT 10;
```

队列深度回落、日志出现「媒体下载完成并回填」，对应消息 `media_pending = 0` 且 `content` 变为 `/images/...` 本地路径。
