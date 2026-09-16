import { ref, onMounted, watch } from 'vue';

const THEME_KEY = 'app_theme';

/**
 * 主题状态是**模块级单例**：
 * 之前每个组件调用 useTheme() 都会新建一个 ref('light')，导致在用户菜单里切换主题时
 * 只有菜单自己那份状态变了（并把 class 写到 <html>），App.vue 根节点仍挂着 theme-light，
 * 而 CSS 变量正是定义在根节点上 —— 结果整页变量没跟着切换，看起来像"闪一下/重新加载"。
 */
const theme = ref('light');
let initialized = false;
let systemWatcherBound = false;

function applyTheme(mode) {
  theme.value = mode;
  document.documentElement.className = 'theme-' + mode;
}

export function useTheme() {
  const initTheme = () => {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'light' || saved === 'dark') {
      applyTheme(saved);
    } else {
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      applyTheme(prefersDark ? 'dark' : 'light');
    }
  };

  const toggleTheme = () => {
    const next = theme.value === 'light' ? 'dark' : 'light';
    applyTheme(next);
    localStorage.setItem(THEME_KEY, next);
  };

  const setTheme = mode => {
    applyTheme(mode);
    localStorage.setItem(THEME_KEY, mode);
  };

  const watchSystemTheme = () => {
    if (systemWatcherBound) return;
    systemWatcherBound = true;
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    mql.addEventListener('change', e => {
      if (!localStorage.getItem(THEME_KEY)) {
        applyTheme(e.matches ? 'dark' : 'light');
      }
    });
  };

  onMounted(() => {
    // 只初始化一次：第一个挂载的组件负责读取本地存储/系统偏好
    if (!initialized) {
      initialized = true;
      initTheme();
    } else {
      applyTheme(theme.value);   // 后续组件挂载时把 class 同步回来（例如刷新后）
    }
    watchSystemTheme();
  });

  // 单例状态变化时同步 <html> 的 class（所有消费者共享同一个 ref，会一起更新）
  watch(theme, val => {
    document.documentElement.className = 'theme-' + val;
  });

  return {
    theme,
    toggleTheme,
    setTheme,
    initTheme
  };
}
