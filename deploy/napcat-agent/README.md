# NapCat 远程 agent（每用户一个机器人）

一套 compose = 一个 QQ 机器人：NapCat（登录/消息）+ agent（注册/心跳/二维码代理）。

## 1. 准备

```bash
cp onebot11.example.json onebot11.json
# 编辑 onebot11.json：把 REPLACE_WITH_NAPCAT_TOKEN 换成随机 token（与 .env 的 NAPCAT_TOKEN 一致）
```

在 qq-ai-assistant 创建机器人并拿到 agentToken：

```bash
curl -X POST http://<后端>:8081/api/bots -H 'Content-Type: application/json' -H 'Cookie: qqai_token=<JWT>' \
  -d '{"name":"我的机器人","runtimeType":"REMOTE","onebotUrl":"http://<本机IP>:16100"}'
# 再轮换/获取 agent token：
curl -X POST http://<后端>:8081/api/bots/<BOT_ID>/agent-token/rotate -H 'Cookie: qqai_token=<JWT>'
```

## 2. 启动

在 `deploy/napcat-agent` 下创建 `.env`：

```env
BOT_ID=2
AGENT_TOKEN=<上一步的 token>
WEBUI_TOKEN=<自定义 WebUI 密码>
NAPCAT_TOKEN=<与 onebot11.json 一致>
BACKEND_URL=http://host.docker.internal:8081
AGENT_URL=http://<后端能访问到的本机地址>:3100
WEBUI_PORT=16099
ONEBOT_PORT=16100
AGENT_PORT=3100
```

```bash
docker compose up -d
docker compose logs -f agent
```

agent 会 POST `/api/bots/${BOT_ID}/agent/register`（带 X-Agent-Token），之后每 20s 心跳。

## 3. 扫码

- 直接打开 WebUI：`http://127.0.0.1:16099/webui`（WEBUI_TOKEN）
- 或由后端代理：`GET /api/bots/${BOT_ID}/qrcode-image`（owner/admin 登录态）
- 登录态：`GET /api/bots/${BOT_ID}/login-status`

## 4. 已知边界

- 目前只打通“远程运行时注册/心跳/二维码/登录态”；**入站消息路由（per-bot webhook）和出站发送还未接入**，属于 P3。
- 同宿主同公网 IP 多 QQ 仍有风控风险；正式多用户建议每个 bot 独立网络出口，或让用户自托管。
- QQ 会话保存在 `./data/qq`，不要提交；升级镜像前备份 `data/`。
