import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { VChart } from './config/echarts'
import { showToast } from './components/Toast.vue'

// 全局 system:toast 事件桥接（CreditsDashboard 等组件通过该事件显示 Toast）
window.addEventListener('system:toast', (e) => {
  const { msg, type = 'info' } = e.detail || {};
  if (msg) showToast(msg, type);
});

const app = createApp(App)
app.use(router)
app.component('v-chart', VChart)
app.mount('#app')
