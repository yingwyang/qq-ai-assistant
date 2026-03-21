# 铃音QQ对话 - 跨端智能聊天辅助系统

基于 SpringBoot + Vue3 + AstrBot + NapCat + GPT-SoVITS 的跨端智能聊天辅助系统。

## 项目简介

铃音QQ对话是一个创新的聊天辅助平台，通过 NapCat 监听 QQ 消息，经由 AstrBot 推送到 SpringBoot 后端存入数据库，前端从数据库获取群聊内容，结合 AI 实现消息解释与总结。

### 核心功能

- **QQ消息监听与管理** - 实时接收和存储QQ群聊消息
- **AI消息总结与分析** - 通过 AstrBot 实现智能消息分析
- **语音合成功能** - 集成 GPT-SoVITS 实现文本转语音
- **系统组件一键控制** - 一键启动/停止 AstrBot、NapCat、GPT-SoVITS
- **NapCat二维码登录** - 嵌入 NapCat 扫码登录功能
- **消息归档与清理** - 自动归档历史消息，定期清理旧数据

## 技术架构

### 后端技术栈

- **Spring Boot 3.2.0** - 核心框架
- **Spring Data JPA** - 数据持久化
- **MySQL 8.0** - 主数据库，存储消息元数据
- **MinIO** - 对象存储，存储图片/视频/音频等大文件
- **WebSocket** - 实时消息推送
- **Maven** - 项目构建

### 前端技术栈

- **Vue 3** - 前端框架
- **Vite** - 构建工具
- **Axios** - HTTP客户端
- **CSS Grid/Flexbox** - 响应式布局

### 第三方组件

- **AstrBot** - AI聊天与消息总结
- **NapCat** - QQ消息监听与OneBot协议实现
- **GPT-SoVITS** - 语音合成

## 项目结构

```
qq-ai-assistant/
├── backend/                    # SpringBoot后端
│   ├── src/main/java/com/qqai/
│   │   ├── config/            # 配置类
│   │   ├── controller/        # API控制器
│   │   ├── entity/            # 实体类
│   │   ├── repository/        # 数据访问层
│   │   ├── service/           # 业务逻辑层
│   │   └── websocket/         # WebSocket处理器
│   ├── src/main/resources/
│   │   ├── application.yml    # 应用配置
│   │   └── init-mysql.sql     # 数据库初始化脚本
│   └── pom.xml                # Maven配置
├── frontend/                   # Vue3前端
│   ├── src/
│   │   ├── components/        # Vue组件
│   │   ├── services/          # API服务
│   │   ├── App.vue            # 根组件
│   │   └── main.js            # 入口文件
│   ├── package.json           # NPM配置
│   └── vite.config.js         # Vite配置
└── README.md                  # 项目说明
```

## 数据库设计

### 核心表结构

1. **messages** - 消息表
   - 存储QQ消息的元数据
   - 支持消息归档和状态跟踪

2. **users** - 用户表
   - 记录登录账号信息
   - 存储用户头像和昵称

3. **groups** - 群聊表
   - 记录群聊信息
   - 存储群聊头像和成员数量

4. **user_groups** - 用户群聊关联表
   - 记录用户和群聊的关联关系
   - 支持多对多关系

5. **file_records** - 文件记录表
   - 存储文件元数据
   - 支持MinIO对象存储

## 快速开始

### 环境要求

- Java 17+
- Node.js 18+
- MySQL 8.0+
- MinIO (可选，用于文件存储)

### 1. 克隆项目

```bash
git clone <repository-url>
cd qq-ai-assistant
```

### 2. 配置数据库

```bash
# 创建数据库
mysql -u root -p < backend/src/main/resources/init-mysql.sql
```

### 3. 启动后端

```bash
cd backend
mvn clean install
java -jar target/qq-ai-assistant-1.0-SNAPSHOT.jar
```

后端服务将运行在 http://localhost:8081

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端服务将运行在 http://localhost:5173

### 5. 配置 NapCat

1. 启动 NapCat 并登录QQ
2. 在 NapCat WebUI 中配置 HTTP 上报：
   - URL: `http://localhost:8081`
   - Token: 配置文件中设置的 token
   - 启用 `reportSelfMessage` 以接收发送的消息

### 6. 启动 AstrBot (可选)

```bash
cd astrbot
python main.py
```

### 7. 启动 GPT-SoVITS (可选)

```bash
cd GPT-SoVITS-v2pro-20250604-nvidia50
runtime\python.exe -I api_v2.py -a 127.0.0.1 -p 8000
```

## 使用说明

### 登录与系统控制

1. 打开网页后，点击左侧导航栏的"登录"按钮
2. 在弹出的登录窗口中：
   - 查看 NapCat 二维码并扫码登录
   - 一键启动/停止系统组件
   - 查看项目介绍

### 查看群聊消息

1. 从左侧导航栏的"最近对话"中选择群聊
2. 或手动输入群聊ID并点击"加载消息"
3. 消息会自动加载并显示在聊天界面

### 消息显示规则

- **用户消息**（发送的消息）- 右对齐显示
- **群消息**（接收的消息）- 左对齐显示
- **AI总结** - 左对齐显示，带有特殊标识

## API文档

### 消息相关API

- `GET /api/messages/group/{groupId}` - 获取群聊消息
- `GET /api/messages/recent-groups` - 获取最近对话的群聊
- `POST /api/messages/webhook` - 接收NapCat消息推送
- `POST /api/messages/archive` - 手动触发消息归档

### 系统控制API

- `GET /api/system/status` - 获取系统组件状态
- `POST /api/system/start-all` - 启动所有组件
- `POST /api/system/stop-all` - 停止所有组件

### NapCat相关API

- `GET /api/napcat/qrcode` - 获取登录二维码
- `GET /api/napcat/login-status` - 获取登录状态

## 配置说明

### 后端配置 (application.yml)

```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/qq_chat?useUnicode=true&characterEncoding=utf8mb4
    username: root
    password: your_password
  
  jpa:
    hibernate:
      ddl-auto: update

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket-name: qq-chat
```

### NapCat配置

在 `napcat/NapCat.Shell/config/onebot11_{qq}.json` 中配置：

```json
{
  "network": {
    "httpClients": [
      {
        "enable": true,
        "name": "qq-chat",
        "url": "http://localhost:8081",
        "reportSelfMessage": true,
        "messagePostFormat": "array",
        "token": "your_token",
        "debug": true
      }
    ]
  }
}
```

## 消息存储策略

### 分表策略

- 按月份自动分表存储消息
- 支持按群聊ID分表（可配置）

### 归档策略

- 自动归档90天前的消息
- 归档后的消息移动到历史表
- 支持手动触发归档

### 文件存储

- 小文件（元数据）- 存储在MySQL
- 大文件（图片/视频/音频）- 存储在MinIO
- 文件元数据记录在file_records表

## 开发计划

### 已实现功能

- [x] 基础消息接收与存储
- [x] 群聊消息展示
- [x] 用户头像显示
- [x] 图片消息解析与显示
- [x] 左侧导航栏最近对话
- [x] 系统组件一键控制
- [x] NapCat二维码登录
- [x] 消息归档与清理
- [x] 发送消息存储
- [x] 用户-群聊关联关系

### 待实现功能

- [ ] AI消息总结功能
- [ ] 语音合成功能
- [ ] 消息搜索功能
- [ ] 多账号支持
- [ ] 消息撤回处理
- [ ] 文件下载功能

## 常见问题

### 1. 消息不显示

- 检查NapCat是否正确配置HTTP上报
- 检查后端服务是否正常运行
- 检查数据库连接是否正常

### 2. 头像不显示

- 检查网络连接是否正常
- QQ头像API需要外网访问
- 检查浏览器控制台是否有错误

### 3. 系统组件启动失败

- 检查组件路径配置是否正确
- 检查端口是否被占用
- 查看后端日志获取详细错误信息

## 贡献指南

欢迎提交Issue和Pull Request！

## 许可证

MIT License

## 联系方式

如有问题，请通过以下方式联系：
- 提交GitHub Issue
- 发送邮件至项目维护者

---

**注意**：本项目仅供学习和研究使用，请遵守相关法律法规和QQ用户协议。