# 铃音QQ对话 - 前端

基于 **Vue 3 + Vite** 构建的前端 SPA，与 `backend/`（Spring Boot）配套使用。

## 技术栈

- **Vue 3.5** — 组合式 API（`<script setup>`）
- **Vue Router 4** — 路由与权限守卫（登录校验 + 管理员二次确认）
- **Vite 8** — 构建工具（开发代理 /api、/images、/uploads、/ws 到后端 8081）
- **ECharts / vue-echarts** — 数据可视化（仪表盘、趋势图）
- **markdown-it + DOMPurify** — AI 富文本渲染与 XSS 过滤
- **原生 fetch 封装** — `src/services/api.js`（统一请求、401 自动登出、错误提示）

## 目录结构

```
frontend/
├── public/                  # 静态资源（图标 / SVG）
├── src/
│   ├── components/          # 组件（ChatInterface / Sidebar / AstrBotChat / PersonaManager ...）
│   ├── composables/         # 组合式函数（use* 钩子，20 个）
│   ├── views/               # 页面（HomeView / AdminView / LoginPage / UserCenter / DocView）
│   ├── router/              # 路由配置与守卫
│   ├── services/api.js      # API 服务层
│   ├── utils/               # 工具函数
│   ├── docs/                # 应用内文档（DocView 展示）
│   ├── App.vue              # 根组件（主题切换 + 路由刷新）
│   ├── main.js              # 入口文件
│   └── style.css            # 全局样式
├── index.html
├── package.json
└── vite.config.js           # Vite 配置（含开发代理）
```

## 脚本

```bash
npm install      # 安装依赖
npm run dev      # 开发模式 http://localhost:5173
npm run build    # 生产构建（产物在 dist/）
npm run preview  # 本地预览构建产物
```

## 环境要求

- Node.js 18+
- 后端服务运行在 `http://localhost:8081`
- MySQL + RabbitMQ 已启动