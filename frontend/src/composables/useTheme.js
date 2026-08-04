import { ref, onMounted, watch } from 'vue';

const THEME_KEY = 'app_theme';

export function useTheme() {
  const theme = ref('light');

  const applyTheme = (mode) => {
    theme.value = mode;
    document.documentElement.className = 'theme-' + mode;
  };

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

  const setTheme = (mode) => {
    applyTheme(mode);
    localStorage.setItem(THEME_KEY, mode);
  };

  const watchSystemTheme = () => {
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    mql.addEventListener('change', (e) => {
      if (!localStorage.getItem(THEME_KEY)) {
        applyTheme(e.matches ? 'dark' : 'light');
      }
    });
  };

  onMounted(() => {
    initTheme();
    watchSystemTheme();
  });

  // 监听 theme 变化，确保 class 同步
  watch(theme, (val) => {
    document.documentElement.className = 'theme-' + val;
  });

  return {
    theme,
    toggleTheme,
    setTheme,
    initTheme
  };
}
