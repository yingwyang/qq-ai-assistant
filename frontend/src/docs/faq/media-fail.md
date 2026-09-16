---
title: 媒体存不下来
description: 图片/视频/语音下载失败与过期占位
updated: 2026-09-16
---

媒体不随消息同步下载：入库后图片/视频投递到 `media.download.queue`，语音投递到 `voice.transcode.queue`，失败重试 3 次后进死信队列并写占位符。
本页解决「媒体变成占位符」与「媒体一直转圈」两类问题。

## 一张表定位媒体失败

**现象**：消息文字正常，媒体位置显示 `[视频已过期]` / `[图片已过期]` / `[媒体已过期]` / `[语音转码失败]`；或一直显示加载中。

**原因**：先看数据库里的 `content` 与 `media_pending`，就能判断卡在哪一层。

```sql
USE qq_chat;
SELECT id, message_type, media_pending, LEFT(content, 90) AS content FROM messages
WHERE message_type IN ('VIDEO','IMAGE','VOICE','AUDIO') ORDER BY id DESC LIMIT 10;
```

| content 形态 | `media_pending` | 含义 |
|---|---|---|
| `/images/video/.../xxx.mp4` | 0 | ✅ 正常落盘 |
| `/images/video/.../xxx.png` | 0 | ✅ 降级成功：原片不可用，落的是 QQ 缩略图（第 3 级兜底） |
| `/images/voice/.../xxx.mp3` | 0 | ✅ 语音下载并转码成功 |
| `[视频已过期]` / `[图片已过期]` / `[媒体已过期]` | 0 | ❌ 三级取源全失败，重试 3 次后由死信队列写占位符 |
| `[语音转码失败]` | 0 | ❌ 语音下载或 SILK→MP3 转码失败 |
| 仍是原始 CQ 码 / 为空 | **1** | ⏳ 还没被消费：队列积压、消费者没跑或下载卡死 |

**处理步骤**：`media_pending = 1` → 看第 4 节；是占位符 → 看第 2、3 节；语音占位符 → 看第 5 节。

**验证**：`content` 变为 `/images/...` 开头、`media_pending = 0`，日志出现「媒体下载完成并回填」或「语音转码完成并回填」。

## 取源为什么会失败

**现象**：视频几乎全部变成 `[视频已过期]`，或只有视频失败而图片、语音都正常。

**原因**

- **QQ 的原视频链接会过期**：腾讯侧临时直链有有效期，NapCat 上报的 `url` 过一会儿就失效；
- **视频原片通常不在本地**：QQ（NT）默认只保留约 68KB 缩略图，原片只有用户在 QQ 里点开播放过才可能落盘；
- 因此后端采用**三级取源**逐级降级：

| 级别 | 来源 | 说明 |
|---|---|---|
| 1 | CQ 码里的 `url=` / `path=` 直连 | 图片多为 QQ CDN https 直链；视频往往是本地绝对路径 |
| 2 | NapCat `get_file(fileId)` | 第 1 级失败时调用，返回可下载 url 或本地路径 |
| 3 | **视频缩略图兜底** | 由 `...\Video\<yyyy-MM>\Ori\<uuid>.mp4` 推导 `...\Thumb\<uuid>_0.png`，落盘为预览图 |

另有四层安全约束会「正当拒绝」某些下载：来源可信校验（防伪造 CQ 码读任意文件）、SSRF 防护（拒内网地址）、体积上限（普通媒体 100MB / 视频 300MB）、鉴权头只发给 NapCat。

**处理步骤**

1. 按日志分类定位：

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Select-String -Path logs\application.log -Pattern 'CQ 直连获取失败|get_file|缩略图|越界|SSRF|本地媒体文件不存在|【DLQ】媒体' |
  Select-Object -Last 25 | ForEach-Object { $_.Line }
```

| 日志片段 | 含义 | 处置 |
|---|---|---|
| `CQ 直连获取失败,改用 NapCat get_file 兜底` | 第 1 级失败，已自动降级 | 看下一条日志 |
| `NapCat get_file 未能解析出可下载地址` | NapCat 侧也拿不到 | 升级 NapCat；确认源文件真在磁盘上 |
| `本地媒体文件不存在: …` | 上报的本地路径已失效 | 靠第 3 级缩略图兜底 |
| `视频原片不可用,已落盘 QQ 缩略图作为预览` | ✅ 降级成功 | 前端显示预览图 |
| `视频原片缺失且未找到缩略图` / `【DLQ】媒体下载彻底失败（已重试 3 次）` | ❌ 三级全失败 | 已写占位符，按根因修 |
| `【安全】本地文件路径越界,拒绝复制` | 被来源校验拦下 | 确认可信来源逻辑未被回退 |
| `SSRF防护：拒绝访问内网/敏感地址` | 目标是内网且不在白名单 | 核对 `NAPCAT_API_URL` 主机名 |

2. 直接用 NapCat 看它到底返回什么（URL 还是本地路径）：

```powershell
# 取这条消息的原始消息段
curl.exe -s -X POST "http://127.0.0.1:6100/get_msg" -H "Authorization: Bearer <NAPCAT_TOKEN>" `
  -H "Content-Type: application/json" -d '{"message_id":"<消息ID字符串>"}'
# 用 file 字段值取文件信息
curl.exe -s -X POST "http://127.0.0.1:6100/get_file" -H "Authorization: Bearer <NAPCAT_TOKEN>" `
  -H "Content-Type: application/json" -d '{"file_id":"<file 字段值>"}'
```

3. 返回本地绝对路径时，用 `Test-Path` 确认文件真在磁盘上（这一步是分水岭）：

```powershell
Test-Path "C:\Users\<用户>\Documents\Tencent Files\<QQ>\nt_qq\nt_data\Video\2026-09\Ori\<uuid>.mp4"
```

4. 按结论处置：只剩缩略图属**正常降级**（要原片需先在 QQ 里完整播放一次，或升级 NapCat 后重发）；图片也失败则核对 `.env` 的 `NAPCAT_TOKEN`、白名单与 `uploads` 可写；体积超限就调大 `FILE_DOWNLOAD_MAX_VIDEO_MB`（默认 300，注意磁盘）。

**验证**：重发一条测试媒体，日志出现「媒体下载完成: type=…, bytes=…」，SQL 中 `content` 为本地路径且 `media_pending = 0`，页面上能看图/播视频。

## 怎么确认文件真的落盘了

**现象**：数据库里 `content` 已是 `/images/...`，页面上却打不开或显示破图。

**原因**：`content` 只代表**回填成功**，还要确认磁盘上文件真的存在，且前端有权限访问——`/images/**` 需要登录 Cookie。

**处理步骤**

1. 查出这条消息的落盘路径：`SELECT id, content FROM messages WHERE id = <消息ID>;`
2. 媒体根目录是**后端运行目录**下的 `backend\uploads\images\{images|video|voice}\{groupId}\{yyyy-MM-dd}\{uuid}.{ext}`：

```powershell
Get-ChildItem D:\ai\Documents\qq-web\qq-ai-assistant\backend\uploads\images\video -Recurse -File |
  Sort-Object LastWriteTime -Descending | Select-Object -First 5 FullName,Length,LastWriteTime
```

3. 文件存在但页面打不开 → 在**已登录**的浏览器里直接访问
   `http://localhost:8081/images/video/<groupId>/<日期>/<文件名>`；这里能打开就是前端缓存或筛选问题，刷新即可。

**验证**：磁盘文件大小不为 0；已登录浏览器直接访问 `content` 路径能下载或播放。

> 提示：落盘目录由 `file.storage.local-path`（默认 `./uploads/images`）决定，是**相对后端工作目录**的路径。用 jar 启动时确认工作目录就是 `backend`，否则文件会落到别处。

## 队列积压怎么看

**现象**：新消息的媒体一直转圈、`media_pending` 恒为 1；或积压越来越多，媒体要等很久才出现。

**原因**：媒体任务是异步消费的，生产者比消费者快就会积压。常见原因是 RabbitMQ 未运行、消费者被长耗时下载占满、某任务反复失败重试、DLQ 持续增长。

**处理步骤**

1. 打开 RabbitMQ 管理台 <http://127.0.0.1:15672>（账号密码见 `.env` 的 `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD`），重点看 `media.download.queue`（图片/视频下载）与 `media.download.dlq`（重试 3 次后彻底失败）、`voice.transcode.queue` / `.dlq`（语音转码）、`ai.analysis.queue` / `.dlq`（AI 摘要）；
2. 也可用 API 查深度：

```powershell
curl.exe -s -u <MQ用户名>:<MQ密码> "http://127.0.0.1:15672/api/queues/%2F/media.download.queue" |
  ConvertFrom-Json | Select-Object messages,messages_ready,consumers | Format-List
```

3. 判读：`consumers = 0` → 后端没在消费（见《组件启动失败》）；`consumers ≥ 1` 但 `messages_ready` 持续增长 → 消费跟不上或任务卡死；`.dlq` 增长 → 有任务反复失败，回到第 2 节定位；
4. 确需丢弃积压任务时（消息本身不受影响，只是媒体不再下载）：
   `curl.exe -s -u <MQ用户名>:<MQ密码> -X DELETE "http://127.0.0.1:15672/api/queues/%2F/media.download.queue/contents"`

**验证**：队列深度回落或不再增长，日志持续出现「媒体下载完成并回填」，对应消息 `media_pending = 0`。

## 语音 SILK→MP3 转码失败怎么办

**现象**：语音消息显示 `[语音转码失败]`，或语音的 `media_pending` 一直为 1。

**原因**：QQ 语音原始格式是 **SILK**（部分场景为 AMR），浏览器不能直接播放，必须转成 MP3。后端转码分两级：先用 `ffmpeg` 直转（对标准 AMR 有效），失败再调用 **Python + pysilk** 脚本处理 SILK。任一级依赖缺失都会失败：`ffmpeg` 或 Python 解释器路径不对、脚本不存在、没装 `pysilk`。

**处理步骤**

1. 看日志确认卡在哪一级：

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Select-String -Path logs\application.log -Pattern 'ffmpeg 直接转换语音失败|SILK 转换脚本不存在|SILK 转 MP3 脚本退出码|Python SILK 转 MP3 失败|SILK 语音已转换为 MP3|【DLQ】语音转码' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }
```

| 日志 | 原因 | 处置 |
|---|---|---|
| `SILK 转换脚本不存在: …` | 脚本路径不对 | 确认 `backend/scripts/convert_silk_to_mp3.py` 存在，核对 `PYTHON_SILK_SCRIPT` |
| `ffmpeg 直接转换语音失败` + `Python SILK 转 MP3 失败` | 两级都失败 | 修 `FFMPEG_PATH`，并确认 Python 环境装了 `pysilk` |
| `SILK 转 MP3 脚本退出码: N, 输出: …` | 脚本执行报错 | 按输出内容修（多为缺依赖或输入不是 SILK） |
| `Python SILK 转 MP3 失败` | 解释器 / 模块问题 | 核对 `PYTHON_EXECUTABLE`，在该解释器下装 `pysilk` |
| `SILK 语音已转换为 MP3: …` | ✅ 成功 | — |

2. 手工验证依赖是否存在：

```powershell
& "D:\ai\Documents\qq-web\GPT-SoVITS-v2pro-20250604-nvidia50\runtime\ffmpeg.exe" -version | Select-Object -First 1
Test-Path "D:\ai\Documents\qq-web\qq-ai-assistant\backend\scripts\convert_silk_to_mp3.py"
```

3. 修好依赖后**重启后端**（这些配置在启动时注入），再让对方重发一条语音。

**验证**：日志出现「SILK 语音已转换为 MP3」与「语音转码完成并回填」；`uploads\images\voice\<群号>\<日期>\` 下出现 `.mp3`（转换成功后原始 `.silk/.amr` 会被删除）。

> 提示：语音落盘后 `content` 指向的应是 **`.mp3`**。若 `content` 里仍是 `.silk`，说明转码未生效，检查 `PYTHON_EXECUTABLE` / `FFMPEG_PATH` 是否指向真实存在的文件。
