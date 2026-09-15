# 媒体（图片 / 语音 / 视频）入库故障排查手册

> 适用版本：`qq-ai-assistant` 后端（Spring Boot 3 + NapCat + RabbitMQ）
> 本文包含 **2026-09-15「视频无法存入服务器」故障的完整复盘**，以及可复用的分层排查步骤。
> 目的：下次出现同类问题，**5 分钟内定位到具体环节**，不再从零摸索。

---

## 0. TL;DR · 五分钟定位

按顺序执行这四步，基本能锁定问题在哪一层：

### ① 看数据库（入库层）

```sql
USE qq_chat;
SELECT id, message_type, LEFT(content, 90) AS content, media_pending, send_time
FROM messages
WHERE message_type IN ('VIDEO','IMAGE','VOICE','AUDIO')
ORDER BY id DESC LIMIT 10;
```

判读：

| content 形态 | 含义 | 说明 |
|---|---|---|
| `/images/video/.../xxx.mp4` | ✅ 成功落盘 | 正常 |
| `/images/video/.../xxx.png` | ✅ 降级成功 | 视频原片不可用，落盘的是 QQ 缩略图（见 §3 第 3 级兜底） |
| `[视频已过期]` / `[图片已过期]` / `[媒体已过期]` | ❌ 失败 | 重试 3 次后进死信，被 DLQ 消费者写入占位符 |
| 还是原始 CQ 码 / 为空 | ⏳ 未处理 | `media_pending=1` 说明消费者没跑或队列积压 |

`media_pending` 长期为 `1` = 消费者没消费（后端没订阅队列 / 队列积压 / 下载卡死）。

### ② 看后端日志（取源层）

后端日志文件：`backend/backend-run.log`（用下面命令启动时产生，见 §5）

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Select-String -Path backend-run.log -Pattern 'mediaType=|媒体下载完成|【DLQ】|get_file|缩略图|越界|可信|无法获取媒体' |
  Select-Object -Last 20 | ForEach-Object { $_.Line }
```

关键日志含义：

| 日志片段 | 含义 |
|---|---|
| `媒体下载完成: type=video, bytes=..., url=...` | 下载成功 |
| `CQ 直连获取失败,改用 NapCat get_file 兜底` | 第 1 级来源失败，走第 2 级 |
| `视频原片不可用,已落盘 QQ 缩略图作为预览` | 走了第 3 级兜底（降级成功） |
| `【安全】本地文件路径越界,拒绝复制` | 被来源校验拦下（未佐证来源只能读 uploads 目录内文件） |
| `SSRF防护：拒绝访问内网/敏感地址` | URL 指向内网且不在 NapCat 白名单 |
| `NapCat get_file 未能解析出可下载地址` | NapCat 侧也拿不到（通常是源文件不存在） |
| `【DLQ】媒体下载彻底失败（已重试 3 次）` | 三级来源全失败 → 写占位符 |

### ③ 看消息队列（异步层）

RabbitMQ 管理台：<http://127.0.0.1:15672>（guest / guest）

- `media.download.queue` 堆积 → 消费者没跟上或卡住；
- `media.download.dlq` 有积压 → 有任务反复失败，看 ② 的日志找原因；
- 相关队列：`voice.transcode.queue`、`ai.analysis.queue`、`broadcast.queue` 及各自的 `.dlq`。

### ④ 看磁盘与来源（NapCat / QQ 侧）

```powershell
# 落盘目录
Get-ChildItem D:\ai\Documents\qq-web\qq-ai-assistant\backend\uploads\images\video -Recurse -File |
  Sort-Object LastWriteTime -Descending | Select-Object -First 5 FullName,Length,LastWriteTime
```

```powershell
# 直接问 NapCat：这条消息段的 url 到底指向哪里
curl.exe -s -X POST "http://127.0.0.1:6100/get_msg" `
  -H "Authorization: Bearer <NAPCAT_TOKEN>" -H "Content-Type: application/json" `
  -d '{"message_id":"<消息ID字符串>"}'

# 拿文件信息（file 字段为文件 id）
curl.exe -s -X POST "http://127.0.0.1:6100/get_file" `
  -H "Authorization: Bearer <NAPCAT_TOKEN>" -H "Content-Type: application/json" `
  -d '{"file_id":"<file 字段值>"}'
```

若 NapCat 返回的是 **本地绝对路径**（如 `C:\Users\...\nt_qq\nt_data\Video\2026-09\Ori\xxx.mp4`），**先用 `Test-Path` 确认该文件是否真的存在**——这一步是本次故障的分水岭：

```powershell
Test-Path "C:\Users\<用户>\Documents\Tencent Files\<QQ>\nt_qq\nt_data\Video\2026-09\Ori\xxx.mp4"
# 不存在时，找同 uuid 的缩略图
Get-ChildItem "C:\Users\<用户>\Documents\Tencent Files" -Recurse -Filter "xxx*"
```

---

## 1. 媒体链路全貌

```
QQ 客户端
  └─ NapCat（OneBot 11）
        └─ HTTP 上报 POST /            ← 校验 X-Token / access_token（napcat.webhook-token）
              └─ RootWebhookController  ← 解析载荷 → 消息入库 media_pending=true
                    └─ RabbitMQ  media.download.queue（图片/视频）/ voice.transcode.queue（语音）
                          └─ MediaDownloadConsumer
                                └─ MediaDownloadService.downloadMediaToLocal（三级取源，见 §3）
                                      ├─ 成功：回填 message.content、media_pending=false、WebSocket 推送 message_update
                                      └─ 失败：重试 3 次 → media.download.dlq
                                            └─ DLQ 消费者：media_pending=false + 占位符（[视频已过期] 等）
```

核心字段（`MediaTaskPayload`）：`messageId`、`rawMessage`（完整 CQ 码）、`url`、`groupId`、`mediaType`（`images`/`video`/`voice`）、`extension`、`fileId`、`trustedSource`。

---

## 2. 安全约束（改代码前务必了解，避免误伤）

媒体下载同时受四层保护，**排查时先确认不是被它们拦下**：

1. **来源可信校验**：`trustedSource=true`（消息段已由 `hasMediaSegment` 佐证存在）时，允许读取 `uploads/`、`nt_qq/nt_data`、`Tencent Files`、`NapCat` 等已知媒体目录；未佐证的 CQ 本地路径只允许 `uploads/` 内文件（防群成员伪造 `[CQ:image,path=C:\敏感文件]`）。
2. **SSRF 防护**：仅允许 http/https；屏蔽回环/私网/链路本地/组播；**NapCat 主机走白名单**（`napcat.onebot-api-url` 的主机名）。
3. **体积上限**：`file.download.max-size-mb`（默认 100）、`file.download.max-video-size-mb`（默认 300）。
4. **鉴权头只发给 NapCat**：仅当目标主机是 NapCat 时才附带 `Authorization: Bearer <napcat.token>`，避免 QQ CDN 因陌生认证头拒绝。

---

## 3. 三级取源逻辑（当前实现）

`MediaDownloadService.downloadMediaToLocal` 依次尝试：

| 级别 | 来源 | 说明 |
|---|---|---|
| 1 | CQ 码中的 `url=` / `path=` | 图片通常是 QQ CDN（https）；视频往往是 QQ 本地缓存绝对路径 |
| 2 | NapCat `get_file(fileId)` | 第 1 级失败时调用，返回可下载 `url` 或本地路径 |
| 3 | **视频缩略图兜底** | 原片不可用时，由 `...\Video\<yyyy-MM>\Ori\<uuid>.<ext>` 推导 `...\Video\<yyyy-MM>\Thumb\<uuid>_0.png`，落盘为预览图 |

前端（`MessageContent.vue`）在视频消息里若发现内容是图片（`.png/.jpg/...`），直接渲染预览图并显示「原视频未缓存，仅预览」，可点击放大。

---

## 4. 本次故障复盘（2026-09-15）

### 4.1 现象

- 群里的视频消息入库后 `content` 变成 `[视频已过期]`，前端只显示占位符；
- **图片、语音正常**，只有视频全军覆没；
- 时间线：2026-09-14 22:33–22:42 还有 3 条视频成功，23:10 之后全部失败。

### 4.2 根因（三层叠加，缺一不可）

1. **QQ（NT）本身不落盘视频原片**：默认只在本地保留 68KB 缩略图（`Thumb\<uuid>_0.png`），原片 `Ori\<uuid>.mp4` 只有用户在 QQ 里点开播放过才可能出现。因此 NapCat 上报的本地路径多数已失效。
2. **NapCat 侧只给本地路径**：视频消息段的 `url` 字段填的是本地绝对路径，`get_file` / `get_msg` 返回同一个路径；把 `enableLocalFile2Url` 打开重启实验，视频段行为不变（该开关对图片/语音等有效）。→ 属于适配层能力边界。
3. **本项目自身的两个缺陷（本次修复重点）**：
   - **越界保护误杀**：为防伪造 CQ 码读取任意本地文件，此前只允许复制 `uploads/` 内的本地文件，导致**即使 QQ 缓存里真实存在原片也会被拒**；
   - **缺少降级兜底**：设计上假设"媒体总能从 URL 下载"，一旦来源不可用就只能写占位符。

### 4.3 诊断路径（可复用）

1. SQL 看 `content` / `media_pending` → 确认是"失败占位符"还是"没被消费"；
2. 日志找失败原因分类：越界 / 不存在 / get_file 失败 / SSRF / HTTP 状态码；
3. 用 NapCat `get_msg` + `get_file` 看到底返回什么（URL 还是本地路径）；
4. `Test-Path` 验证返回路径是否真实存在；不存在则全盘搜同 uuid（通常只剩缩略图）；
5. 打开媒体抓包日志（`app.debug.log-media-payload=true`）抓一次实时事件，确认消息段结构；
6. 对照时间线找回归点（哪次改动 / 哪个组件升级后开始失败）。

### 4.4 修复内容

| 提交 | 内容 |
|---|---|
| `1bf45cf` | 可信来源标记（`trustedSource`）+ 两级兜底（CQ 直连 → `get_file`）+ NapCat token 只发 NapCat 主机 + 视频体积上限可配（300MB）+ 日志明确到具体失败原因 |
| `edf25ac` | 视频**缩略图兜底**（第 3 级）+ 前端预览图渲染 + 媒体抓包日志开关（默认关闭） |
| 运维侧 | **升级 NapCat**（新版可正确提供视频可下载地址/落盘）→ 视频真正可下载、可播放 |

### 4.5 验证证据

- 修复前：消息 `content = [视频已过期]`、`media_pending=0`、磁盘无文件；
- 修复后（实机）：`messages.id=84852` → `content=/images/video/1082243522/2026-09-15/1fc2ffb2-….mp4`，磁盘落盘 0.74MB ✅；
- 缩略图兜底：用失效原片路径复现 → `content=.../<uuid>.png`（68KB，与 QQ 缩略图一致）✅；
- 图片链路回归正常 ✅；`mvn test` 全量通过 ✅。

---

## 5. 常用命令与配置速查

### 启动后端（带日志文件，便于排查）

方式一（推荐，稳定常驻）：打成 jar 后以独立进程运行，日志重定向到文件

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
mvn -q -DskipTests package
Start-Process -FilePath "java" -ArgumentList "-jar","target\qq-ai-assistant-1.0-SNAPSHOT.jar" `
  -WorkingDirectory "D:\ai\Documents\qq-web\qq-ai-assistant\backend" `
  -RedirectStandardOutput "app-run.log" -RedirectStandardError "app-run.err.log" -WindowStyle Hidden
```

方式二（开发调试）：`mvn spring-boot:run`，日志可重定向到 `backend-run.log`

```powershell
cd D:\ai\Documents\qq-web\qq-ai-assistant\backend
Start-Process -FilePath "cmd.exe" -ArgumentList "/c","mvn -q spring-boot:run 1> backend-run.log 2> backend-run.err.log" `
  -WorkingDirectory "D:\ai\Documents\qq-web\qq-ai-assistant\backend" -WindowStyle Hidden
```

> 排查时以实际在用的日志文件为准（`app-run.log` 或 `backend-run.log`）。
> 相关：**AI 摘要链路**的排查见 `doc/AI_SUMMARY_TROUBLESHOOTING.md`。

### 关键配置项

| 配置 | 位置 | 默认 | 说明 |
|---|---|---|---|
| `napcat.onebot-api-url` | `application.yml` / `.env` | `http://localhost:6100` | NapCat OneBot API，同时用于 SSRF 白名单 |
| `napcat.token` | `.env` `NAPCAT_TOKEN` | — | 调用 NapCat API 的令牌 |
| `napcat.webhook-token` | `.env` `NAPCAT_WEBHOOK_TOKEN` | — | 校验 NapCat 上报（同时用于 NapCat WS 握手） |
| `file.download.max-size-mb` | `FILE_DOWNLOAD_MAX_MB` | 100 | 普通媒体下载上限 |
| `file.download.max-video-size-mb` | `FILE_DOWNLOAD_MAX_VIDEO_MB` | 300 | 视频下载上限 |
| `app.debug.log-media-payload` | `application.yml` | `false` | 打开后打印图片/视频事件的完整载荷（含聊天内容，仅排查时临时开启） |
| `enableLocalFile2Url` | NapCat `config/onebot11_<qq>.json` | — | NapCat 侧"本地文件转 URL"开关 |

### 路径与端口

| 项 | 值 |
|---|---|
| 媒体落盘 | `backend/uploads/images/{images\|video\|voice}/{groupId}/{yyyy-MM-dd}/` |
| 后端 | 8081 · 前端 5173 · AstrBot 6185 · NapCat OneBot 6100 / WebUI 6099 |
| MQ 管理台 | <http://127.0.0.1:15672>（guest/guest） |
| NapCat 账户配置 | `napcat/NapCat.Shell/config/onebot11_<qq>.json`、`webui.json` |

---

## 6. 症状 → 处置速查表

| 症状 | 最可能的原因 | 处置 |
|---|---|---|
| 视频全部 `[视频已过期]` | NapCat 版本旧 / 原片不在本地 / 来源被拒 | 先看日志属于哪一类；升级 NapCat；确认 `trustedSource` 逻辑未被回退 |
| 视频只显示预览图 | QQ 未缓存原片（第 3 级兜底生效） | 属正常降级；要原片需先在 QQ 中播放一次，或升级 NapCat 后重发 |
| 图片也失败 | NapCat token/API、SSRF 白名单、磁盘权限 | 核对 `.env` 的 `NAPCAT_TOKEN`；确认 NapCat 主机在白名单；检查 `uploads` 可写 |
| `media_pending` 一直为 1 | 消费者未运行 / 队列积压 | 查 MQ 队列深度与消费者日志；重启后端；必要时把积压消息重投 |
| 有文件但前端不显示 | `content` 未回填 / 静态资源鉴权 | 检查 DB `content`；`/images/**` 需登录 Cookie（不再公开） |
| DLQ 持续增长 | 来源长期不可用 | 看 DLQ 消费者日志里的 `MediaTaskPayload`，按 ② 分类定位 |
| 视频体积过大被拒 | 超出 `max-video-size-mb` | 调大 `FILE_DOWNLOAD_MAX_VIDEO_MB`（注意磁盘占用） |

---

## 7. 防复发要点（写代码/改配置时注意）

1. **安全加固必须配套"合法来源白名单"**：NapCat 上报的本地路径是合法输入，一刀切限制目录会误杀正常业务（本次就是如此）。
2. **媒体链路必须有降级兜底**：不要假设"总能拿到原片"，视频尤其如此（QQ 只留缩略图）。
3. **分层可观测**：入库 → 队列 → 取源 → 落盘 → 前端，每层都要有能一眼看懂的日志（`媒体下载完成` / `越界` / `【DLQ】` 等）。
4. **保留可开关的抓包日志**：`app.debug.log-media-payload` 平时关闭避免泄漏聊天内容，排查时一键打开。
5. **回归点对照**：同类问题先对时间线，找出"哪次改动/升级之后开始失败"，比盲读代码快得多（本次 22:42 正常、23:10 失败，直接指向当时的改动）。
6. **依赖组件版本**：NapCat 等适配层的版本差异会直接改变消息段结构，升级后建议用本文 §0 的四步做一次冒烟。

---

*最后更新：2026-09-15（本次故障修复后记录）*
