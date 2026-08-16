<template>
  <div :class="'theme-' + (theme || 'light')">
    <router-view :key="viewKey" />
    <!-- 全局图片预览 Lightbox（所有页面共享同一弹窗） -->
    <ImagePreview />
  </div>
</template>

<script>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { useTheme } from './composables/useTheme';
import ImagePreview from './components/ImagePreview.vue';

export default {
  name: 'App',
  components: { ImagePreview },
  setup() {
    const { theme, toggleTheme, initTheme } = useTheme();
    const router = useRouter();
    const viewKey = ref(0);

    const handleRefresh = () => {
      viewKey.value++;
    };

    onMounted(() => {
      // 使用 router.afterEach 确保每次导航（包括同路由）都触发刷新
      router.afterEach(() => {
        viewKey.value++;
      });
      window.addEventListener('app:refresh', handleRefresh);
    });

    onBeforeUnmount(() => {
      window.removeEventListener('app:refresh', handleRefresh);
    });

    return { theme, toggleTheme, initTheme, viewKey };
  }
};
</script>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  line-height: 1.6;
  color: #333;
  background-color: #f5f5f5;
  margin: 0 !important;
  padding: 0 !important;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

#app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
}

/* 主题 CSS 变量 - 亮色 */
.theme-light {
  --bg-primary: #f5f5f5;
  --bg-secondary: #ffffff;
  --bg-tertiary: #f8f9fa;
  --text-primary: #333333;
  --text-secondary: #666666;
  --text-muted: #999999;
  --border-color: #e0e0e0;
  --accent-color: #3498db;
  --accent-hover: #2980b9;
  --success-color: #27ae60;
  --danger-color: #e74c3c;
  --sidebar-bg: #2c3e50;
  --sidebar-text: #ecf0f1;
  --sidebar-hover: #34495e;
  --sidebar-active: #3498db;
  --card-bg: #ffffff;
  --card-shadow: rgba(0, 0, 0, 0.1);
  --modal-overlay: rgba(0, 0, 0, 0.5);
  --input-bg: #ffffff;
  --input-border: #ddd;
}

/* 主题 CSS 变量 - 暗色 */
.theme-dark {
  --bg-primary: #1a1a2e;
  --bg-secondary: #16213e;
  --bg-tertiary: #0f3460;
  --text-primary: #e0e0e0;
  --text-secondary: #b0b0b0;
  --text-muted: #707070;
  --border-color: #333344;
  --accent-color: #3498db;
  --accent-hover: #5dade2;
  --success-color: #2ecc71;
  --danger-color: #e74c3c;
  --sidebar-bg: #0f1923;
  --sidebar-text: #c0c0c0;
  --sidebar-hover: #1a2a3a;
  --sidebar-active: #2980b9;
  --card-bg: #1e1e3a;
  --card-shadow: rgba(0, 0, 0, 0.3);
  --modal-overlay: rgba(0, 0, 0, 0.7);
  --input-bg: #2a2a4a;
  --input-border: #3a3a5a;
}
</style>
