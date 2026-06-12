import { ref, computed, onMounted, onUnmounted } from 'vue';

/**
 * 响应式布局 composable
 * 监听窗口大小变化，提供断点判断
 */
export function useResponsive() {
  // 窗口宽度
  const windowWidth = ref(window.innerWidth);
  const windowHeight = ref(window.innerHeight);

  // 断点定义
  const breakpoints = {
    xs: 480,   // 手机
    sm: 768,   // 平板
    md: 1024,  // 小桌面
    lg: 1280,  // 大桌面
    xl: 1536   // 超大屏
  };

  // 计算当前断点
  const isMobile = computed(() => windowWidth.value < breakpoints.sm);
  const isTablet = computed(() => windowWidth.value >= breakpoints.sm && windowWidth.value < breakpoints.md);
  const isDesktop = computed(() => windowWidth.value >= breakpoints.md);
  const isLargeScreen = computed(() => windowWidth.value >= breakpoints.lg);

  // 布局模式
  const layoutMode = computed(() => {
    if (windowWidth.value < breakpoints.sm) return 'mobile';
    if (windowWidth.value < breakpoints.md) return 'tablet';
    return 'desktop';
  });

  // 侧边栏是否应该折叠
  const shouldCollapseSidebar = computed(() => windowWidth.value < breakpoints.md);

  // 是否应该显示三栏布局
  const shouldShowThreeColumn = computed(() => windowWidth.value >= breakpoints.lg);

  // 是否应该显示双栏布局
  const shouldShowTwoColumn = computed(() => windowWidth.value >= breakpoints.md && windowWidth.value < breakpoints.lg);

  // 处理窗口大小变化
  const handleResize = () => {
    windowWidth.value = window.innerWidth;
    windowHeight.value = window.innerHeight;
  };

  onMounted(() => {
    window.addEventListener('resize', handleResize);
    // 初始调用一次
    handleResize();
  });

  onUnmounted(() => {
    window.removeEventListener('resize', handleResize);
  });

  return {
    windowWidth,
    windowHeight,
    breakpoints,
    isMobile,
    isTablet,
    isDesktop,
    isLargeScreen,
    layoutMode,
    shouldCollapseSidebar,
    shouldShowThreeColumn,
    shouldShowTwoColumn
  };
}

export default useResponsive;
