# 安全加固与架构治理 - 实现计划

## Task 1: 安全配置启动校验与密钥清理
- **Priority**: P0
- **Depends On**: None
- **Description**:
  - 删除 `application.yml` 中所有硬编码的 secret/token/password 默认值
  - 创建 `SecurityPropertiesValidator`，在 `ApplicationStartedEvent` 中校验 jwt.secret、napcat.webhook-token、napcat.token 等
  - 空值或默认值时抛 `IllegalStateException` 阻止启动
  - 将 `application.yml` 加入 `.gitignore`，提供 `application.yml.example`
- **Acceptance Criteria**: 启动时若安全配置未设置，应用拒绝启动并给出明确提示

## Task 2: Webhook Token 严格校验与兼容模式删除
- **Priority**: P0
- **Depends On**: Task 1
- **Description**:
  - 删除 `napcat.webhook-token-strict` 配置项及 `RootWebhookController` 中相关逻辑
  - `RootWebhookController` 永远执行严格 token 校验，失败返回 401
  - `NapCatWebhookController` 保留转发逻辑，但校验在 RootWebhookController 统一处理
- **Acceptance Criteria**: 不带正确 token 的 POST 到 `/` 或 `/api/napcat/webhook` 返回 401

## Task 3: 文件访问权限控制
- **Priority**: P0
- **Depends On**: None
- **Description**:
  - `SecurityConfig` 中移除 `.requestMatchers("/uploads/**").permitAll()` 和 `.requestMatchers("/images/**").permitAll()`
  - 创建 `FileController` 提供受控的文件下载接口，校验当前用户是否有权访问该文件
  - 前端所有文件引用改为通过受控接口获取
- **Acceptance Criteria**: 直接访问 `/uploads/xxx` 返回 403；通过接口且有权时正常下载

## Task 4: 系统组件接口权限提升
- **Priority**: P0
- **Depends On**: None
- **Description**:
  - `SecurityConfig` 中为 `/api/system/start-all`、`/api/system/stop-all`、`/api/system/start-*`、`/api/system/stop-*`、`/api/system/tts` 等添加 `.hasRole("ADMIN")`
  - `SystemController` 移除不必要的 try-catch 包装，由全局异常处理接管
- **Acceptance Criteria**: ROLE_USER 调用系统组件启停接口返回 403

## Task 5: FastJSON 全量迁移至 Jackson
- **Priority**: P0
- **Depends On**: None
- **Description**:
  - `pom.xml` 中移除 `com.alibaba:fastjson` 依赖
  - 替换所有 `com.alibaba.fastjson.JSON`、`JSONObject`、`JSONArray` 为 Jackson 等价物
  - 涉及文件：`RootWebhookController`、`NapCatService`、`AstrBotService`、所有用到 FastJSON 的类
  - 确保序列化/反序列化行为一致（特别是字段命名策略）
- **Acceptance Criteria**: 编译通过，pom.xml 中无 fastjson 依赖，功能测试正常

## Task 6: @Async 自调用修复 - 事件机制
- **Priority**: P0
- **Depends On**: None
- **Description**:
  - 创建 `MessageSavedEvent` 事件类
  - `MessageService.saveMessage()` 中移除 `processMessageAsync()` 自调用，改为 `eventPublisher.publishEvent(new MessageSavedEvent(savedMessage))`
  - 创建 `MessageEventListener` 监听事件并异步处理（使用 `@EventListener` + `@Async`）
  - 配置独立线程池 `messageTaskExecutor`，避免阻塞默认线程池
- **Acceptance Criteria**: 保存消息接口响应时间不受 AI 总结影响；AI 总结异步执行

## Task 7: Controller 分层清理 - 移除 Repository 直连
- **Priority**: P1
- **Depends On**: None
- **Description**:
  - `MessageController` 中移除 `MessageRepository` 注入，文件权限校验逻辑委托给 `FileStorageService`
  - `RootWebhookController` 中移除 `MessageRepository` 注入，消息去重和回复查找走 `MessageService`
  - 校验所有 Controller，确保没有直接注入 Repository 的情况
- **Acceptance Criteria**: 所有 Controller 只依赖 Service，不依赖 Repository

## Task 8: RootWebhookController 拆分与策略模式
- **Priority**: P1
- **Depends On**: Task 5 (FastJSON 迁移完成后)
- **Description**:
  - 创建 `MessageParserService`，负责从 NapCat payload 解析消息元数据
  - 创建 `CqCodeUtils` 工具类，封装所有 CQ 码提取逻辑
  - 创建消息类型处理器接口 `MessageTypeHandler` 及实现：`TextHandler`、`ImageHandler`、`VoiceHandler`、`VideoHandler`、`FileHandler`、`ForwardHandler`、`ReplyHandler`
  - `RootWebhookController` 仅负责 token 校验、调用 parser、路由到对应 handler、保存结果
- **Acceptance Criteria**: RootWebhookController 方法行数 < 100 行；每种消息类型有独立处理器

## Task 9: 统一 API 响应格式与全局异常处理
- **Priority**: P1
- **Depends On**: None
- **Description**:
  - 创建 `ApiResponse<T>` 统一响应包装类
  - 创建业务异常：`BizException`、`NotFoundException`、`ForbiddenException`、`UnauthorizedException`
  - 完善 `GlobalExceptionHandler`，按异常类型返回统一格式
  - 逐步替换 Controller 中的 `Map.of()` 和字符串返回，统一使用 `ApiResponse`
  - 生产环境配置不返回堆栈（`server.error.include-stacktrace=never` 已存在，需确保生效）
- **Acceptance Criteria**: 所有 API 返回统一格式；错误响应不暴露技术细节

## Task 10: 消息去重数据库唯一约束
- **Priority**: P1
- **Depends On**: None
- **Description**:
  - `Message` 实体 `messageId` 字段添加 `@Column(unique = true)` 或数据库手动加唯一索引
  - `RootWebhookController` 捕获 `DataIntegrityViolationException`，返回已存在响应
  - 或者使用 `INSERT ... ON DUPLICATE KEY UPDATE` 语义（按数据库选型）
- **Acceptance Criteria**: 并发发送相同 message_id 只有一条入库

## Task 11: Flyway 数据库版本管理
- **Priority**: P1
- **Depends On**: None
- **Description**:
  - `pom.xml` 添加 `flyway-core` 和 `flyway-mysql` 依赖
  - 将 `init-mysql.sql` 转为 `db/migration/V1__init.sql`
  - `application.yml` 生产 profile 设置 `ddl-auto: validate`
  - 保留 `ddl-auto: update` 仅在 dev profile
- **Acceptance Criteria**: 空数据库启动时 Flyway 自动建表；生产环境 JPA 只做校验

## Task 12: 日志规范化与 System.out 清理
- **Priority**: P2
- **Depends On**: None
- **Description**:
  - 全局搜索 `System.out.println` 和 `System.err.println`
  - 替换为 `log.info`、`log.warn`、`log.error`
  - 对关键链路（消息接收、Webhook、文件下载）添加 traceId（MDC）
- **Acceptance Criteria**: 代码中无 System.out.println；日志输出有统一格式

## Task 13: 前端权限校验加固
- **Priority**: P1
- **Depends On**: None
- **Description**:
  - `router/index.js` 进入 `/admin` 前先调用后端 `/api/auth/me` 确认真实角色
  - `localStorage` 中的 `user_role` 仅用于 UI 展示，不做安全决策
  - `api.js` 中 401 响应统一处理，清除 token 并跳转登录
- **Acceptance Criteria**: 手动修改 localStorage user_role 为 ADMIN 无法进入管理页

## Task 14: processAllMessages 接口安全加固
- **Priority**: P1
- **Depends On**: None
- **Description**:
  - `MessageController.processAllMessages()` 添加认证和 ADMIN 权限校验
  - 或改为内部定时任务触发，不再暴露为 HTTP 接口
  - 增加防重入锁（`synchronized` 或分布式锁）
- **Acceptance Criteria**: 未认证/非管理员无法触发；并发调用不会重复执行

# Task Dependencies
- Task 2 depends on Task 1
- Task 8 depends on Task 5
- Task 9 可与 Task 3、Task 4、Task 6、Task 7 并行
- Task 10 可与 Task 6 并行
- Task 11 可与 Task 1 并行
