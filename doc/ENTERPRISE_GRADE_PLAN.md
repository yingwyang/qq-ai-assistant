# 全项目企业级化计划（Enterprise Grade Plan）

> 本文档是**受版本管理的唯一计划源**，每轮交付后更新状态。
> 需求背景：**支付链路只做一处功能改动——把"支付用的二维码"换成按钮；其余内容全部对齐企业项目水准。**
> 详细 spec / tasks / checklist（更细的场景与行号证据）另存于 `.trae/specs/enterprise-grade/`（该目录不入库，仅本地参考）。

## 一、支付链路约定（唯一功能改动）

排查结论：本项目**不存在任何支付二维码**（前端源码、`dist`、后端静态资源、`public/` 资源、git 历史全量检索，仅有 NapCat 登录二维码）。原支付链路为：

```
点击「立即购买」→ 浏览器原生 window.confirm → POST /api/subscriptions/purchase(MANUAL)
→ 订单 PENDING → 管理员后台「确认收款」→ PAID，发放积分与权益
```

本轮把"支付步骤"改为**按钮驱动**，其余业务语义不变（仍由管理员确认收款）：

| 步骤 | 实现 |
|------|------|
| 1 打开确认订单 | `PaymentDialog.vue`：套餐信息、金额明细（商品金额/优惠/应付）、支付方式按钮组、服务须知、协议勾选 |
| 2 提交订单 | 主按钮「确认支付 ¥金额」；未勾选协议时禁用；只提交一次 `paymentMethod=MANUAL` |
| 3 完成付款 | 弹窗第二步：订单号（可复制）、状态「待确认收款」、「查看订单详情」/「完成」 |

**硬约束**：支付界面不得出现二维码、收款码或扫码支付元素；未接入的在线支付通道置灰并标注「未开通」。

## 二、企业级差距（审计结论摘要，含证据）

后端只读审计（7 维度）+ 前端实测，评分：异常 3 / 校验 2 / 安全 3 / 事务并发 2 / 可观测 2 / 可维护 2 / 性能 2。

| 级别 | 问题 | 证据 |
|------|------|------|
| blocker | QQ 登录二维码图片接口公开（未登录可取，扫码=接管机器人账号），且 `qrcode-path` 泄漏服务器绝对路径 | `backend/.../config/SecurityConfig.java:161`、`security/JwtAuthenticationFilter.java:36`、`controller/SystemController.java:254,262` |
| high | JWT 明文随响应体回传，抵消 HttpOnly 防 XSS 价值 | `controller/AuthController.java:147-159` |
| high | 管理员降权不递增 `tokenVersion`，旧 token 最长 30 天仍可调调账/退款 | `controller/AdminController.java:155-188`、`common/SecurityHelper.java:102` |
| high | 先调 LLM 并落库回复、最后才扣费；0 余额可反复刷 | `controller/AstrBotController.java:1486-1503` |
| high | 15+ 处 `catch (Exception e) { return 400, e.getMessage() }`：500 降级为 400 且回显内部文本 | `controller/MessageController.java` 多处 |
| high | `grantPoints` 读-改-写无锁（`spendPoints` 有悲观锁）；`markPaid` 先查后改无幂等 | `service/CreditService.java:118-144`、`service/SubscriptionService.java:153-161` |
| high | 用户仪表盘 N+1（循环内两次查库） | `service/UserDashboardService.java:214-238` |
| medium | 33 个 `@RequestBody Map<String,Object>` 零校验（含可覆盖 `priceCents/pointsGranted` 的补单入口） | `controller/AdminOrdersController.java:132-165` 等 |
| medium | 分页无上限、钱包页一次 4 次查询、订单出参映射三处复制、套餐名硬编码 | `AdminController.java:64`、`CreditsController.java:127-149`、`SubscriptionsController.java:356-369` |
| medium | 无 Actuator、`/health` 不探 DB/MQ、初始管理员密码明文进日志、限流器非线程安全 | `pom.xml`、`SystemController.java:276`、`DataInitializer.java:57`、`RateLimiterService.java:15-38` |
| 前端 | 订单状态同一状态多种叫法（文档自己注明"文案略有差异"） | `docs/guide/subscription.md:98`（本轮已消除） |
| 前端 | 原生弹窗残留：`window.confirm`（消息删除、解绑 QQ）、`window.prompt`（复制回退） | `ChatInterface.vue:1557,1563`、`views/UserCenter.vue:926`、`SubscriptionDashboard.vue:965` |
| 工程 | 默认配置含开发者本机绝对路径与用户名；`index` chunk 1.16MB 未分割 | `backend/src/main/resources/application.yml`、`vite build` 警告 |

## 三、批次与状态

| 批次 | 范围 | 状态 |
|------|------|------|
| 0 | 支付步骤按钮化 + 订单状态文案单一事实来源（`frontend/src/config/orderStatus.js`）+ 订阅文档同步 + 验收脚本 | ✅ 已完成（`87bf3df`） |
| A | 安全收口：二维码接口收权、响应体去 token、Cookie `secure` 配置化、降权递增 `tokenVersion`、`User` 敏感 getter 加 `@JsonIgnore` | ✅ 已完成（本轮，见下） |
| B | 资金链路幂等与扣费顺序：`markPaid` 行锁、`grantPoints` 行锁、下单幂等键、AI/TTS 先扣后调 + 失败退费、签到冲突 `REQUIRES_NEW`、流水唯一约束 | ✅ 已完成（本轮，见下） |
| C | 异常与校验规范化：删宽 catch、Map 入参换 DTO + `@Valid`、分页上限统一、归属校验收口 Service | 🔄 进行中（C.1 异常回显收口、C.3 分页上限统一已完成；C.2 DTO 校验、C.4 归属校验收口待办） |
| D | 性能与静默异常：去 N+1、合并钱包页查询、消除 `catch { return null; }`、前端空 catch 补日志 | ⬜ 待办 |
| E | 可观测与去重：Actuator + `/health` 探 DB/MQ、初始密码不落日志、限流器换 Caffeine、抽订单映射器、套餐名动态生成 | ⬜ 待办 |
| F | 前端一致性：清除原生弹窗、空/加载/错误态统一、大 chunk 代码分割、无障碍、暗色主题覆盖 | ⬜ 待办 |
| G | 文档与技能：`doc/` 与 `frontend/src/docs/` 全量对齐、运维坑写回技能 | ⬜ 待办 |

### 批次 A 交付明细（安全收口）

| 项 | 改动 | 证据 |
|----|------|------|
| A.1 | 三个二维码入口（`/api/system/napcat/qrcode`、`qrcode-path`、`qrcode-image`）从 `permitAll` 收为 `ROLE_ADMIN`；`qrcode-image` 从 `JwtAuthenticationFilter.PUBLIC_PATHS` 移除 | `config/SecurityConfig.java`、`security/JwtAuthenticationFilter.java`；实测匿名 401 / 普通用户 403 / ADMIN 200 |
| A.2 | `AuthResponse` 移除 `token` 字段，JWT 只走 HttpOnly Cookie | `dto/auth/AuthResponse.java`、`controller/AuthController.java`；单测断言记录组件与 JSON 均无 token |
| A.3 | Cookie `secure` 由 `app.cookie.secure`（`APP_COOKIE_SECURE`）控制，生产置 true | `config/AppCookieProperties.java`、`application.yml` |
| A.4 | 角色变更递增 `tokenVersion`，被降权者的旧令牌立即失效 | `service/UserService.updateUserRole`；单测断言 0→1→2 |
| A.5 | `User.getPassword()/getToken()` 加 `@JsonIgnore`，防实体出参泄漏 | `entity/User.java`；单测断言序列化不含密钥 |
| A.6 | 前端同步收口：用户菜单二维码仅管理员可见、`LoginModal` 非管理员只给指路、用户中心不再为普通用户请求二维码 | `components/UserMenuPopover.vue`、`components/LoginModal.vue`、`views/UserCenter.vue` |
| A.7 | 新增可复用安全回归脚本 `frontend/scripts/check-security-hardening.mjs`（10 项） | 本轮实测 10/10 |

**为什么 `qrcode` 与 `qrcode-path` 也要收权**：原先它们只要求「已登录」，任何普通用户都能读到机器人二维码与服务器绝对路径；二维码本身即账号接管凭据，因此三者必须同一权限。
**为什么状态接口保持公开**：`login-status`、`component-status` 只返回布尔量，且登录页在 Cookie 过期时仍需显示组件状态灯（见 `docs/development/architecture.md` 的白名单说明）。

### 批次 B 交付明细（资金链路幂等与扣费顺序）

| 项 | 改动 | 证据 |
|----|------|------|
| B.1 | 新增 `SubscriptionOrderRepository.findByOrderNoWithLock`（`SELECT … FOR UPDATE`）；`markPaid`、`refundOrderWithRatio`、`cancelOrder` 改为行锁取单 | 两线程并发确认同一订单：只成功 1 次、只有 1 条购买流水、积分只发一次 |
| B.2 | `CreditService.grantPoints` 先锁账户行（新增私有 `lockOrCreateAccount`：存在则锁，不存在则先初始化再锁） | 6 线程并发各发 10 分：0 失败、余额精确 +60（原先抛 `OptimisticLockingFailureException` 丢更新） |
| B.3 | `createOrder` 开头对该用户账户行加锁，把 60 秒幂等窗口的查询与写入收进同一把锁 | 3 线程并发下单：同一订单号、库里只有 1 单（原注释自认"极端并发可能仍生成两单"） |
| B.4a | 新增 `CreditService.assertChatAffordable`：调用大模型**之前**做余额门禁（管理员免费 / 月度配额 / 每日封顶任一命中即放行，只有确定要付费且余额 < 1 才拒） | `AstrBotController` 在 `postForEntity` 前调用；单测覆盖 0 余额被拒、有余额放行、管理员免费 |
| B.4b | TTS 扣费写入 `requestKey` 作为 `relatedId`，合成失败按该键精确退费（原实现 relatedId=null，`refundForTts` 永远找不到原流水） | `SystemController` 合成异常时调用 `refundForTts`；单测：扣费→退费余额回补、重复退费幂等 |
| B.5 | `signInToday` 改为「先锁账户行 → 再判断今日是否已签到」；唯一约束冲突兜底不再在已中毒事务里查询（旧实现会抛 `UnexpectedRollbackException`） | 2 线程并发签到：1 成功 1 抛 `ALREADY_SIGNED_IN`，签到记录 1 条 |
| B.6 | **驳回审计建议**：`credit_transaction(user_id,type,related_id)` 唯一约束在本项目**不可行** —— 真实数据已有 16 组重复（`AI_CHAT`/`AI_ANALYZE` 的 relatedId 是会话号/群号，天然可重复）。改用「订单行锁 + `refund:{orderNo}` 幂等键」保证退款幂等 | MySQL 实测 `GROUP BY user_id,type,related_id HAVING COUNT(*)>1` = 16 组 |

**反向验证（证明测试能抓到缺陷）**：临时把 `markPaid` 换回无锁查询、`grantPoints` 换回 `ensureAccount` 后，两个并发用例**双双失败**（`Tests run: 2, Failures: 2`）；恢复行锁后全绿。新增 `MoneyPathConcurrencyTest` 8 项（并发确认收款 / 并发发放 / 并发签到 / 并发下单 / TTS 退费 / 余额门禁 / 退款幂等 / 取消与确认互斥）。
**踩坑记录**：用 `Copy-Item` 从备份还原源码会保留旧时间戳，Maven 增量编译会因此**跳过重编**（全量测试实际跑的是变异后的字节码，出现 2 个假失败）→ 还原后必须 touch 源文件时间戳或 `mvn clean`。

### 批次 C 交付明细（进行中）

| 项 | 改动 | 证据 |
|----|------|------|
| C.1a | `MessageController` 16 处「catch → 400/503 + `e.getMessage()`」全部改为：**业务异常（`BizException`）原样上抛** + 未预期异常 `log.error` 后抛安全文案的 `BizException`（不再把内部异常文本回显给客户端、不再把 500 伪装成 400） | `log.error("上传失败", e)` + `BizException(500, "FILE_UPLOAD_FAILED", "文件上传失败，请稍后重试")` |
| C.1b | `PersonaController` 7 处 `return badRequest().body(Map.of("error", e.getMessage()))` 同样改造（并统一回 `ApiResponse` 信封）；AstrBot 上游异常改 502 | 残留 `Map.of("error", e.getMessage())` = 0 |
| C.1c | `BackupController` 删除备份的 `FileNotFoundException/IllegalArgumentException/Exception` 三分支改为 404/400/500 的 `BizException`；`GroupController` 发送被拒改为「审计留痕 + 原样上抛」；`SystemController` 切换角色改为「日志 + 安全文案」 | 守卫测试全绿 |
| C.1d | 新增**架构守卫测试** `ErrorLeakGuardTest`：源码扫描禁止（a）控制器回显 `e.getMessage()`（b）控制器把用户可控 size 直接交给 `PageRequest.of(page, size)`；并断言 `PageLimits` 边界 | 该测试首跑即抓出 `BackupController`/`GroupController`/`SystemController` 三个漏网文件，修完 3/3 通过 |
| C.3 | 新增 `common/PageLimits`（`clampSize` 上界 200、`clampPage` 下界 0）并替换 5 处用户可控分页：`AdminController`（用户列表）、`LogController`（审计日志）、`MessageController`（媒体列表）、`AstrBotConversationService.getConversations/getConversationMessages` | 守卫测试规则二 |

## 四、验收基线（每轮必须全绿）

| 命令 | 基线 |
|------|------|
| `cd frontend && npx vite build` | exit 0 |
| `node scripts/check-security-hardening.mjs <ADMIN_JWT> <baseUrl> <USER_JWT>` | 10/10（批次 A 起） |
| `node scripts/check-payment-flow.mjs <JWT>` | 18/18 |
| `node scripts/check-admin-shell.mjs <JWT>` | 18/18 |
| `node scripts/check-admin-dark-theme.mjs <JWT>` | 14 页无问题 |
| `node scripts/e2e-admin-export.mjs <JWT>` | 26/26 |
| `node scripts/check-admin-last-admin.mjs <JWT>` | 14/14 |
| `cd backend && mvn -B -ntp test` | **179** 项（批次 B 起；批次 C 起会继续增长） |

> 环境：`CHROME_PATH='C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'`；ADMIN JWT 见技能 `qqai-verify`。
