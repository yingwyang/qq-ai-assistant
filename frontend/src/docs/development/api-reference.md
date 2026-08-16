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
## 群聊消息（补充）

### 标记群聊已读

```
POST /api/messages/read/{groupId}
Authorization: Bearer <token>
```

### 一键全部已读

```
POST /api/messages/read-all
Authorization: Bearer <token>
```

### 删除群聊会话（硬删除，不可恢复）

```
POST /api/messages/group/{groupId}/delete-conversation
Authorization: Bearer <token>
Content-Type: application/json

{
  "ownerQq": "123456789"
}
```

## 积分与签到

### 查询积分余额

```
GET /api/credits/balance
Authorization: Bearer <token>
```

### 每日签到

```
POST /api/credits/sign-in
Authorization: Bearer <token>
```

### 签到状态

```
GET /api/credits/sign-in/status
Authorization: Bearer <token>
```

### 积分流水

```
GET /api/credits/transactions?page=0&size=20&direction=&type=&start=&end=
Authorization: Bearer <token>
```

### 消费趋势

```
GET /api/credits/trend?days=7
Authorization: Bearer <token>
```

### 积分奖励规则

```
GET /api/credits/rewards
Authorization: Bearer <token>
```

### 管理员：积分规则 / 用户积分 / 调整

```
GET  /api/credits/admin/rule          # 查询积分规则
GET  /api/credits/admin/user-credits  # 用户积分列表
POST /api/credits/admin/adjust        # 调整用户积分（增加 / 扣除）
GET  /api/credits/admin/transactions  # 全量积分流水
```

## 订阅与订单

### 套餐列表

```
GET /api/subscriptions/plans
Authorization: Bearer <token>
```

### 购买套餐

```
POST /api/subscriptions/purchase
Authorization: Bearer <token>
Content-Type: application/json

{
  "planCode": "pro"
}
```

### 我的订单

```
GET /api/subscriptions/orders
Authorization: Bearer <token>
```

### 订单详情 / 取消 / 申请退款

```
GET  /api/subscriptions/orders/{orderNo}
POST /api/subscriptions/orders/{orderNo}/cancel
POST /api/subscriptions/orders/{orderNo}/refund-request
```

## TTS 语音合成

### 生成语音

```
POST /api/system/tts
Authorization: Bearer <token>
Content-Type: application/json

{
  "text": "要合成的文本"
}
```

### 音色列表 / 切换音色

```
GET  /api/system/tts/characters
POST /api/system/tts/switch-character
```