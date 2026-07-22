# 安全加固与架构治理 Spec

## Why
对 `qq-ai-assistant` 进行代码审查后，发现项目存在大量安全红线（硬编码密钥、Webhook 无校验、文件接口公开暴露）、架构分层破坏（Controller 直连 Repository、@Async 自调用失效）、以及工程债务（FastJSON、System.out.println、路径硬编码）。本 spec 将这些问题按优先级排序，分批次修复，使项目达到可安全部署的状态。

## What Changes
- **BREAKING**: `application.yml` 中所有硬编码 secret 默认值将被删除，启动时强制校验，空值阻止启动
- **BREAKING**: Webhook token 兼容模式开关将被删除，永远严格校验
- **BREAKING**: `/uploads/**`、`/images/**` 不再 permitAll，改为受控接口代理
- **BREAKING**: 系统组件启停接口从普通登录权限提升为 ADMIN 专属
- 移除 FastJSON 依赖，全量迁移至 Jackson
- 修复 `@Async` 自调用失效，引入 ApplicationEventPublisher 异步事件机制
- 拆分 RootWebhookController 巨型方法，引入策略模式解析消息
- Controller 不再直接注入 Repository，所有数据访问走 Service
- 统一异常处理与 API 响应格式，消除 `Map<String, Object>` 滥用
- 数据库变更引入 Flyway 管理，生产环境关闭 `ddl-auto: update`
- 统一日志使用 SLF4J，替换所有 `System.out.println`

## Impact
- Affected specs: 所有涉及认证、消息接收、文件访问、系统管理的功能
- Affected code: `SecurityConfig`, `RootWebhookController`, `MessageController`, `AuthController`, `SystemController`, `MessageService`, `NapCatService`, `AstrBotService`, `JwtUtil`, `JwtAuthenticationFilter`, `application.yml`, `pom.xml`, 前端 `api.js`, `router/index.js`

## ADDED Requirements

### Requirement: 安全配置强制校验
The system SHALL 在启动时校验所有安全相关配置项，任何 secret/token/key 使用默认值或空值时，必须抛出异常阻止应用启动。

#### Scenario: 启动时 JWT secret 为默认值
- **WHEN** 应用启动
- **GIVEN** `jwt.secret` 为默认值或空
- **THEN** 抛出 `IllegalStateException`，提示用户通过环境变量配置

#### Scenario: 启动时 NapCat webhook token 为空
- **WHEN** 应用启动
- **GIVEN** `napcat.webhook-token` 为空
- **THEN** 抛出 `IllegalStateException`，提示必须配置 webhook token

### Requirement: Webhook 消息严格校验
The system SHALL 对所有进入的 NapCat Webhook 消息进行 token 严格校验，不再提供兼容模式降级开关。

#### Scenario: 合法 Webhook 请求
- **WHEN** NapCat 发送消息到 `/` 或 `/api/napcat/webhook`
- **GIVEN** 请求携带正确的 Authorization Bearer token
- **THEN** 正常处理消息

#### Scenario: 非法 Webhook 请求
- **WHEN** 任意来源 POST 到 `/` 或 `/api/napcat/webhook`
- **GIVEN** token 缺失或不匹配
- **THEN** 返回 401 Unauthorized，不处理消息体

### Requirement: 文件访问权限控制
The system SHALL 对所有上传的文件访问进行用户权限校验，不再直接暴露静态资源路径。

#### Scenario: 合法用户访问自己的文件
- **WHEN** 已登录用户请求 `/api/messages/file/{fileId}`
- **GIVEN** 该文件关联的消息属于用户绑定的 QQ 号
- **THEN** 返回文件信息

#### Scenario: 非法用户尝试访问他人文件
- **WHEN** 用户 A 请求属于用户 B 的文件
- **THEN** 返回 403 Forbidden

### Requirement: 系统组件管理权限提升
The system SHALL 将所有系统组件启停接口（start-all, stop-all, start-astrbot 等）限制为 ADMIN 角色访问。

#### Scenario: 普通用户尝试启停组件
- **WHEN** ROLE_USER 用户 POST `/api/system/start-all`
- **THEN** 返回 403 Forbidden

#### Scenario: 管理员正常操作
- **WHEN** ROLE_ADMIN 用户 POST `/api/system/start-all`
- **THEN** 正常执行并返回结果

### Requirement: 异步消息处理事件机制
The system SHALL 使用 Spring ApplicationEventPublisher 替代 `@Async` 自调用，实现消息保存后的异步 AI 总结。

#### Scenario: 新消息到达
- **WHEN** `MessageService.saveMessage()` 完成保存
- **THEN** 发布 `MessageSavedEvent`
- **AND** `MessageEventListener` 异步消费事件，调用 AstrBot 总结
- **AND** 保存操作不阻塞等待总结结果

### Requirement: 消息去重数据库唯一约束
The system SHALL 在数据库层面对 `message_id` 字段添加唯一索引，防止并发重复入库。

#### Scenario: 并发重复消息
- **WHEN** 两条相同 message_id 的消息同时到达
- **THEN** 第一条成功入库，第二条触发 `DuplicateKeyException`
- **AND** 被捕获后返回已存在响应，不报错

### Requirement: 统一 API 响应格式
The system SHALL 使用统一的 `ApiResponse<T>` 包装所有 API 返回，消除字符串/Map 混用。

#### Scenario: 成功响应
- **THEN** 返回 `{"code": 200, "data": ..., "message": "ok"}`

#### Scenario: 错误响应
- **THEN** 返回 `{"code": 400/401/403/500, "data": null, "message": "友好提示"}`
- **AND** 生产环境不暴露堆栈信息

### Requirement: Flyway 数据库版本管理
The system SHALL 使用 Flyway 管理数据库 schema 变更，生产环境使用 `validate` 模式。

#### Scenario: 首次启动
- **WHEN** 应用启动且数据库为空
- **THEN** Flyway 执行 V1__init.sql 创建所有表和索引

#### Scenario: 后续升级
- **WHEN** 新增 migration 脚本
- **THEN** Flyway 按版本顺序执行，schema 与代码保持一致

## MODIFIED Requirements

### Requirement: 认证与授权流程
- `AuthController` 移除 `@CrossOrigin(origins = "*")`，由全局 CORS 配置控制
- `JwtAuthenticationFilter` tokenVersion 不匹配时必须写 401 响应，不能静默放行
- Token 黑名单从 Caffeine 本地缓存迁移策略（先统一接口，后续可接入 Redis）

### Requirement: 消息接收与解析
- `RootWebhookController` 仅负责接收和校验，消息解析委托给 `MessageParserService`
- CQ 码解析抽离为 `CqCodeUtils`，不手写 indexOf+正则
- 支持的消息类型（文本、图片、语音、视频、文件、转发、回复）使用策略模式注册处理器

## REMOVED Requirements

### Requirement: FastJSON 依赖
**Reason**: FastJSON 历史漏洞多，项目已依赖 Jackson，重复引入增加攻击面
**Migration**: 所有 `JSON.parseObject`、`JSON.toJSONString`、`JSONObject`、`JSONArray` 替换为 Jackson 等价物

### Requirement: `webhook-token-strict` 配置项
**Reason**: 安全不能降级，不应提供关闭校验的开关
**Migration**: 删除该配置项及相关代码，永远执行严格校验

### Requirement: `processAllMessages` 公开接口
**Reason**: 未认证即可无限触发，存在 DoS 和费用风险
**Migration**: 添加认证并限制为 ADMIN 权限，或改为内部定时任务触发
