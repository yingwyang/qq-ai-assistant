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
| C | 异常与校验规范化：删宽 catch、Map 入参换 DTO + `@Valid`、分页上限统一、归属校验收口 Service | ✅ 已完成（C.1/C.2/C.3/C.4，见下） |
| D | 性能与静默异常：去 N+1、合并钱包页查询、消除 `catch { return null; }`、前端空 catch 补日志 | ✅ 已完成（见下） |
| E | 可观测与去重：Actuator + `/health` 探 DB/MQ、初始密码不落日志、限流器换 Caffeine、抽订单映射器、套餐名动态生成 | ✅ 已完成（见下） |
| F | 前端一致性：清除原生弹窗、空/加载/错误态统一、大 chunk 代码分割、无障碍、暗色主题覆盖 | ✅ 已完成（F.1~F.4，见下） |
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
| C.2 | 新增三个管理端 DTO + `@Valid`：`ManualCreateOrderRequest`（补单：`pointsGranted` ≤ 1000 万、`durationDays` 1~3650、`priceCents` 0~100 万、字段长度上限）、`CreditAdjustRequest`（调账：`amount` ±1000 万）、`CashEntryRequest`（记账：金额 0.01~9999999、两位小数、direction 白名单）；三个端点不再吃 `Map<String,Object>`，历史 `*Override` 别名字段一并去掉（前端发的一直是规范字段名）；删除手工 `parseCashAmount`（旧实现会把原始输入回显到错误信息） | `dto/admin/*.java`；新增 `AdminInputValidationTest` 5 项（缺字段/超上限/负数/非法方向/统一信封） |
| C.4 | 归属校验收口：新增 `SubscriptionService.getOrderForUser`（未登录或非本人一律 403 `FORBIDDEN_ORDER`，不区分"不存在/不是你的"以防探测订单号）、`cancelOrderAsUser`（行锁 + 严格归属 + 状态校验三合一，管理员路径 `cancelOrder` 复用同一内部方法）、私有 `requireOrderOwner` 统一 `requestRefund`/`disputeOrder` 的归属判定；`SubscriptionsController` 的详情/取消端点不再自行查找与比对 | 新增 `OrderOwnershipTest` 5 项（详情/取消/退款/申诉越权 + 未登录 + 订单不存在的 404 语义 + 越权后状态不变） |

### 批次 D 交付明细（性能与静默异常）

| 项 | 改动 | 证据 |
|----|------|------|
| D.1a | `UserDashboardService.getGroupRanking` 去掉 N+1：原来「每群一次 COUNT + 一次查群名」= 2×群数 次 SQL，改为一次 `GROUP BY` 聚合 + 一次 `IN` 批量取群名，内存合并排序；失败从静默返回空改为 `log.warn` | 新增 `MessageRepository.countActiveMessagesByGroupIds`、`GroupRepository.findByGroupIdIn` |
| D.1b | `MessageService.getRecentGroupsForUser` 去掉「逐绑定 QQ 查询」的 N+1（新增 `GroupRepository.findByOwnerQqInAndActiveTrue`，并按 groupId 去重） | 一次 IN 查询替代 N 次 |
| D.1c | `DashboardService` 管理端排行在非区间口径下对 Top-10 群逐个 COUNT → 改为一次 `countActiveMessagesByGroupIdsOnly` 批量聚合 | 10 次 SQL → 1 次 |
| D.2 | 钱包页收入/支出合计合并为**一条**聚合 SQL（新增 `CreditTransactionRepository.sumIncomeAndSpendByFilters` 与 `CreditService.sumIncomeAndSpend`，`@Transactional(readOnly=true)`）；分页统一走 `PageLimits` | 新增断言：合并结果与旧 `sumIncome`/`sumSpend` 完全一致（`MoneyPathConcurrencyTest.combinedIncomeSpendSumMatchesLegacyQueries`） |
| D.3a | 后端静默 catch 清零：`AdminOrdersController`/`AdminCreditsController` 的日期与枚举解析、`SubscriptionService.parseStatuses`、`CreditsController` 日期解析、`MessageController`（群类型/数字/日期解析）、`BackupService`、`FilePurgeService`、`MediaDownloadService`（含安全判定 fail-closed 改 WARN）、`AstrBotController` SSE 行解析、`SystemController` 端口探测（debug 级） | 全部补 `log.warn/debug` 带上下文；`MediaDownloadService` 安全判定失败按阻断处理并留 WARN |
| D.3b | 守卫测试新增规则三：`controller`/`service` 包内禁止「catch 体里直接 return 或空 catch」 | `ErrorLeakGuardTest.noSilentCatchInControllerAndService`——该规则首跑即抓出 5 个文件 6 处漏网点 |
| D.4 | 前端空 catch 清零：`services/api.js`（登出通知、错误体解析）、`UserCenter.vue`、`SubscriptionDashboard.vue`（头像解析/详情加载/用户信息刷新）改为 `console.warn`／`logger.warn`／`logger.debug` | 前端扫描 0 处残留 |

### 批次 E 交付明细（可观测与去重）

| 项 | 改动 | 证据 |
|----|------|------|
| E.1 | 引入 `spring-boot-starter-actuator`：`/actuator/health` 现在会探测 **MySQL（db）与 RabbitMQ**（原先 `/api/system/health` 只探 NapCat 端口，数据库挂了仍返回 ok）；`show-details=always` 便于排障；**该端点收权为 `ROLE_ADMIN`**（依赖详情不对外） | `pom.xml`、`application.yml`（含测试 yml）、`SecurityConfig`；`ActuatorHealthSecurityTest` 3 项：匿名 401 / 普通用户 403 / ADMIN 可见 `db` 组件明细 |
| E.2 | 初始管理员密码**不再写入日志**：随机密码落一次性文件 `backend/data/initial-admin-password.txt`（该目录已在 .gitignore），日志只提示文件路径与"立即改密并删除"；由 `ADMIN_INIT_PASSWORD` 提供时无需落盘 | `config/DataInitializer.java` |
| E.3 | 限流器重写为 Caffeine 固定窗口：旧实现 `ConcurrentHashMap<String, ArrayDeque<Instant>>` 的 `ArrayDeque` 非线程安全（并发清理/写入竞态可绕过限流）且空 key 清理不可靠会内存泄漏；新实现用原子计数 + `expireAfterWrite` 自动过期，不同窗口长度分桶 | `common/RateLimiterService.java`；`RateLimiterServiceTest` 4 项（含 8 线程 ×80 次请求恰好放行 50 次的精确性断言） |
| E.4 | 新增 `SubscriptionOrderMapper`：把原先在 `SubscriptionsController` 与 `AdminOrdersController` 各写一份的 `orderToMap`/`planTierToName`/`refundStatusName`/`extractMeta` 收敛为单一实现，用户端 `toUserView`、管理端 `toAdminView`（多 clientIp/userAgent/disputeReason/metadata 派生字段） | `service/SubscriptionOrderMapper.java`；`SubscriptionOrderMapperTest` 4 项（用户端不泄漏管理员字段、管理端解析 metadata、退款状态文案） |
| E.5 | 套餐名动态化：`直购积分·N` 不再硬编码，改读「规则配置」的 `plan*Credit`（与订阅页 `/subscriptions/plans` 的口径一致），规则读取失败时退回默认值 | 断言"改规则 → 展示名同步变化"（`直购积分·7777`） |

> 说明：`txToMap` 仍在 3 个控制器各有一份（字段略有差异），本轮未合并，列入批次 F 的收尾项。

**踩坑记录（已固化为守卫测试）**：`application.yml` 后半段有一个 `---` 分隔的 **dev profile 文档**，写在分隔符之后的配置只在 `--spring.profiles.active=dev` 时生效。本次把 `management:` 段误写在分隔符之后，默认 profile 下 `/actuator` 只暴露了 health 且没有依赖详情（启动日志显示 "Exposing 1 endpoint(s)"），排查花了几轮。修法：把 `management:` 移到第一个文档，并新增 `ApplicationYamlStructureTest` 断言「management 段与安全相关配置必须位于第一个 YAML 文档」。

**E 批次最终验收**：`mvn -B -ntp test` **207/207**；实测 `/actuator/health` 匿名 401、普通用户 403、ADMIN 返回 `db`(MySQL) + `rabbit`(4.2.4) + `diskSpace` + 探针状态；`/actuator/metrics` 200。

### 批次 F 交付明细（前端一致性，进行中）

| 项 | 改动 | 证据 |
|----|------|------|
| F.1 | 原生弹窗清零：`ChatInterface` 删除消息的两步确认改 `ConfirmDialog`（第一步"下一步"、第二步"同时删除媒体 / 仅删除消息"）；`UserCenter` 解绑 QQ 改 `ConfirmDialog`（带确认按钮文案）；`SubscriptionDashboard` 复制回退去掉 `window.prompt`，改为提示手动记录 | 新增守卫脚本 `scripts/check-no-native-dialogs.mjs`（去注释后扫描，`window.confirm/alert/prompt` 计数为 0，失败即退出 1） |
| F.3 | 代码分割：① `main.js` 不再全局注册 `<v-chart>`（原先 `import { VChart } from './config/echarts'` 把整个 echarts 打进入口），改由 `UserCenter`/`CreditsDashboard` 局部注册；② 路由**全量懒加载**（`LoginPage`/`HomeView`/`UserCenter` 之前是静态 import）；③ `vite.config.js` 用 rolldown 支持的**函数式** `manualChunks` 显式拆出 `echarts`(含 zrender/vue-demi) 与 `markdown`，警告上限调整到 700KB 并注明理由 | 入口包 **1141KB → 51KB**；产物：`echarts` 655KB、`DocView` 297KB、`HomeView` 192KB、`markdown` 126KB、`UserCenter` 99KB、`AdminView` 94KB |
| F.3 验证 | 新增 `scripts/check-dashboard-charts.mjs`：预检两个 dashboard 接口 200，再断言三个图表页各自渲染出 canvas（用户中心 4、管理后台概览 3、资金流水 3），并过滤"后端瞬时不可用"噪音 | 6/6 通过（此脚本的价值：懒加载 echarts 后"页面白屏但没人发现"的风险被自动化兜住） |

> 说明：`txToMap` 的三个副本已在本轮收尾（见下）。

| F.4 | 共享确认弹窗补齐无障碍与键盘操作：`role="alertdialog"` + `aria-modal` + `aria-labelledby/describedby`、打开后焦点进入弹窗（默认聚焦确认按钮）、关闭后焦点归还触发元素、Esc 关闭；新增 `scripts/check-dialog-a11y.mjs` 用真实浏览器断言上述 6 项 | `ConfirmDialog.vue`；脚本 8/8 通过（含"焦点归还到 btn-action delete"） |
| F.2（部分） | `txToMap` 三个副本收敛为 `CreditTransactionMapper`：`toMap`（用户端/订单详情，10 字段契约）与 `toAdminCashView`（管理端资金流水，额外三列现金字段，无现金时显式 null 防前端列错位）；新增 `CreditTransactionMapperTest` 3 项固定出参契约 | 实测用户端与管理端流水接口字段齐全；`mvn test` **210/210** |
| F.2（状态统一） | 新增 `components/common/StatePanel.vue`：**一个组件承载 loading / empty / error 三态**（错误态独立图标与警示色、可挂「重试」CTA、`compact` 供表格行内使用）。迁移 7 个管理列表页 + `AdminView` 抽屉 + 用户端订阅页共 **19 处**状态渲染，删除页面内散落的 `audit-loading`/`audit-empty`/`orders-*` 样式；**关键修复：原先错误态复用空态样式，接口失败显示"暂无数据"** | 新增守卫 `scripts/check-shared-states.mjs`（组件能力 + 9 个列表页必须引用 + 旧 class 零残留；首跑即抓出 `CreditsDashboard` 遗留选择器与 `admin-shared.css` 注释）；新增 `scripts/check-error-state.mjs`：拦截接口伪造 500 → 断言错误态与重试按钮 → 恢复接口点重试 → 列表恢复，**4/4 通过** |

> F.2 范围说明：统一的是**列表页**状态（管理端列表 + 用户端订单列表）。聊天区（`ChatInterface`）的空/加载是占满整屏的占位布局、并带自定义图标，语义与列表行不同，保留原实现并在本文件记录，避免为"一致"而牺牲该处布局。

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
