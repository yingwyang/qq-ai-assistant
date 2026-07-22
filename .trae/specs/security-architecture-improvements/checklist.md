# 安全加固与架构治理 - 验证检查清单

## 安全红线
- [x] application.yml 中无硬编码 secret 默认值
- [x] 启动时安全配置未设置则应用拒绝启动
- [x] Webhook 不带正确 token 返回 401，不存在兼容模式
- [x] `/uploads/**`、`/images/**` 不再 permitAll
- [x] 系统组件启停接口仅限 ADMIN 访问
- [x] processAllMessages 不再公开暴露
- [x] AuthController 移除 `@CrossOrigin(origins = "*")`

## 架构治理
- [x] pom.xml 中无 FastJSON 依赖
- [x] 代码中无 `com.alibaba.fastjson` 引用
- [x] @Async 自调用已修复，使用事件机制异步处理
- [x] 所有 Controller 不再直接注入 Repository
- [x] RootWebhookController 方法已拆分，行数从 370 精简至 ~126（核心解析委托给 MessageParserService）
- [x] CQ 码解析已抽离为独立工具类
- [x] 消息类型处理器使用策略模式（MessageParserService）

## API 与异常
- [x] 所有 Controller 返回统一 ApiResponse<T> 格式
- [x] 错误响应不暴露堆栈或技术细节
- [x] GlobalExceptionHandler 覆盖常见异常类型
- [x] 生产环境 server.error.include-stacktrace=never 生效

## 数据库
- [x] message_id 字段有唯一索引
- [x] 并发重复消息只有一条入库
- [x] Flyway 正常执行 migration
- [x] 生产环境 ddl-auto 为 validate

## 日志与工程
- [x] 代码中无 System.out.println / System.err.println
- [x] 关键链路有 traceId（MDC）
- [x] 文件路径不再硬编码（或基于配置）

## 前端
- [x] 进入 /admin 前向后端确认真实角色
- [x] 修改 localStorage user_role 无法绕过管理页权限
- [x] 401 响应统一处理并跳转登录
