# API 接口文档

QQ AI 助手提供 RESTful API，所有接口均需 Bearer Token 鉴权。

## 认证

### 登录

```
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "********"
}
```

**响应**：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "eyJhbGci...",
    "user": { "id": 1, "username": "admin", "role": "ADMIN" }
  }
}
```

### 获取当前用户

```
GET /api/auth/me
Authorization: Bearer <token>
```

### 登出

```
POST /api/auth/logout
Authorization: Bearer <token>
```

> 登出时后端会自动停止所有运行中的插件。

## 消息接口

### 获取消息列表

```
GET /api/messages?group=xxx&page=0&size=20
Authorization: Bearer <token>
```

### 获取最近群组

```
GET /api/messages/recent-groups
Authorization: Bearer <token>
```

## 数据统计

### 仪表盘统计

```
GET /api/dashboard/stats
Authorization: Bearer <token>
```

### 消息时段分布

```
GET /api/dashboard/hourly-distribution
Authorization: Bearer <token>
```

### AI 对话趋势

```
GET /api/dashboard/ai-trend
Authorization: Bearer <token>
```

## 插件管理

### 获取插件状态

```
GET /api/plugins/status
Authorization: Bearer <token>
```

### 启动插件

```
POST /api/plugins/{name}/start
Authorization: Bearer <token>
```

### 停止插件

```
POST /api/plugins/{name}/stop
Authorization: Bearer <token>
```

## 用户设置

### 获取设置

```
GET /api/user-settings
Authorization: Bearer <token>
```

### 更新设置

```
PUT /api/user-settings
Content-Type: application/json
Authorization: Bearer <token>

{
  "botName": "铃音",
  "astrbotKey": "abk_xxx",
  "providers": "[{\\"name\\":\\"siliconflow\\",\\"apiKey\\":\\"xxx\\"}]"
}
```