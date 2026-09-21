import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'


export default defineConfig({
  plugins: [vue()],
  server: {
    host: true,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        secure: false,
      },
      '/images': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        secure: false,
      },
      '/uploads': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        secure: false,
      },
      '/ws': {
        target: 'ws://localhost:8081',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    // 上限 700KB：入口壳实测 51KB，唯一超过 600KB 的是按需加载的 echarts 第三方包（655KB），
    // 它只在仪表盘/积分页请求，属于可接受的 vendor 体积；这里保留告警用于盯住"自己代码"的膨胀。
    chunkSizeWarningLimit: 700,
    // 显式拆出重型第三方库（Vite 8 基于 rolldown，manualChunks 只支持函数形式）：
    //  - echarts / zrender / vue-echarts（约 600KB）：只有仪表盘/积分页用，独立成包便于缓存与并行加载
    //  - markdown-it / dompurify：文档与富文本渲染用
    // vue / vue-router 保持默认（入口壳本身很小，实测 50KB）。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (id.includes('echarts') || id.includes('zrender') || id.includes('vue-demi')) return 'echarts';
          if (id.includes('markdown-it') || id.includes('dompurify')) return 'markdown';
          return undefined;
        },
      },
    },
  },
})
