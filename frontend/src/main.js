import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { showToast } from './components/Toast.vue'

// 注意：echarts / vue-echarts 不再在入口全局注册。
// 原先 `import { VChart } from './config/echarts'` 会把整个 echarts（约 1MB）打进入口包，
// 而图表只出现在仪表盘/积分页等按需加载的页面；现在由真正用到 <v-chart> 的组件各自局部注册，
// echarts 随之进入那些路由分包，首屏体积显著下降。

// 全局 system:toast 事件桥接（CreditsDashboard 等组件通过该事件显示 Toast）
window.addEventListener('system:toast', (e) => {
  const { msg, type = 'info' } = e.detail || {};
  if (msg) showToast(msg, type);
});

const app = createApp(App)
app.use(router)
app.mount('#app')
