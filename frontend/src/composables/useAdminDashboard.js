import { ref, onMounted, onUnmounted } from 'vue';
import { dashboardApi, systemApi } from '../services/api';

const POLL_INTERVAL_MS = 15000;

export function useAdminDashboard() {
  const stats = ref({
    totalMessages: 0,
    totalGroups: 0,
    activeGroups: 0,
    totalUsers: 0,
    totalConversations: 0,
    activeConversations: 0,
    totalFiles: 0,
  });
  const messageTrend = ref([]);
  const trendDays = ref(7);
  const trendInterval = ref('day');
  const groupRanking = ref([]);
  const qqRanking = ref([]);
  const tooltipVisible = ref(false);
  const tooltipData = ref({ date: '', count: 0 });
  const tooltipStyle = ref({ left: '0px', top: '0px' });
  const scrollWrapper = ref(null);
  const componentStatus = ref({
    astrbot: { running: false },
    napcat: { running: false },
    gptsovits: { running: false },
  });
  const qrCode = ref('');
  const systemMessage = ref('');
  const systemMessageType = ref('');
  const autoLogin = ref(localStorage.getItem('napcat_auto_login') === 'true');
  const isCheckingLogin = ref(false);
  const diskUsage = ref({
    uploadsSizeFormatted: '0 B',
    totalSpaceFormatted: '0 B',
    usedSpaceFormatted: '0 B',
    freeSpaceFormatted: '0 B',
    usagePercent: 0,
  });

  const isStartingAstrBot = ref(false);
  const isStoppingAstrBot = ref(false);
  const isStartingNapCat = ref(false);
  const isStoppingNapCat = ref(false);
  const isStartingGptSovits = ref(false);
  const isStoppingGptSovits = ref(false);

  let statusInterval = null;

  const loadStats = async () => {
    try {
      stats.value = await dashboardApi.getStats();
    } catch (error) {
      console.error('加载统计数据失败:', error);
    }
  };

  const loadTrend = async () => {
    try {
      messageTrend.value = await dashboardApi.getMessageTrend(trendDays.value, trendInterval.value);
    } catch (error) {
      console.error('加载消息趋势失败:', error);
    }
  };

  const setTrendDays = (days, interval = 'day') => {
    trendDays.value = days;
    trendInterval.value = interval;
    loadTrend();
  };

  const showTooltip = (event, item) => {
    tooltipData.value = item;
    tooltipVisible.value = true;
    const rect = event.target.getBoundingClientRect();
    tooltipStyle.value = {
      left: rect.left + rect.width / 2 - 40 + 'px',
      top: rect.top - 50 + 'px',
    };
  };

  const hideTooltip = () => {
    tooltipVisible.value = false;
  };

  const loadRanking = async () => {
    try {
      groupRanking.value = await dashboardApi.getGroupRanking();
    } catch (error) {
      console.error('加载群聊排行失败:', error);
    }
  };

  const loadQQRanking = async () => {
    try {
      qqRanking.value = await dashboardApi.getQQRanking();
    } catch (error) {
      console.error('加载QQ排行失败:', error);
    }
  };

  const loadDiskUsage = async () => {
    try {
      diskUsage.value = await systemApi.getDiskUsage();
    } catch (error) {
      console.error('加载磁盘使用情况失败:', error);
    }
  };

  const getComponentStatus = async () => {
    try {
      componentStatus.value = await systemApi.getComponentStatus();
    } catch (error) {
      console.error('获取组件状态失败:', error);
    }
  };

  const refreshQrCode = () => {
    qrCode.value = `/api/system/napcat/qrcode-image?timestamp=${Date.now()}`;
  };

  const onAutoLoginChange = () => {
    localStorage.setItem('napcat_auto_login', autoLogin.value);
  };

  const checkNapCatLogin = async () => {
    if (!autoLogin.value) return;
    isCheckingLogin.value = true;
    try {
      const res = await systemApi.checkNapCatLoginStatus();
      if (res.loggedIn) {
        showSystemMsg(`NapCat 已自动登录: ${res.qq || ''}`, 'success');
      }
    } catch (error) {
      console.error('检查 NapCat 登录状态失败:', error);
    } finally {
      isCheckingLogin.value = false;
    }
  };

  const getBarHeight = (count) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    return Math.max((count / max) * 100, 10);
  };

  const getLinePoints = () => {
    if (!messageTrend.value || messageTrend.value.length === 0) return '';
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    const len = messageTrend.value.length;
    return messageTrend.value.map((item, index) => {
      const x = len > 1 ? (index / (len - 1)) * 100 : 50;
      const y = 60 - (item.count / max) * 50 - 5;
      return `${x},${y}`;
    }).join(' ');
  };

  const getPointX = (index) => {
    const len = messageTrend.value.length;
    return len > 1 ? (index / (len - 1)) * 100 : 50;
  };

  const getPointY = (count) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    return 60 - (count / max) * 50 - 5;
  };

  const getPointXPercent = (index) => {
    const len = messageTrend.value.length;
    return len > 1 ? (index / (len - 1)) * 100 : 50;
  };

  const getPointYPercent = (count) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    const y = 60 - (count / max) * 50 - 5;
    return (y / 60) * 100;
  };

  const getAreaPoints = () => {
    if (!messageTrend.value || messageTrend.value.length === 0) return '';
    const len = messageTrend.value.length;
    const linePoints = getLinePoints();
    const firstX = len > 1 ? 0 : 50;
    const lastX = len > 1 ? 100 : 50;
    const bottomY = 55;
    return `${firstX},${bottomY} ${linePoints} ${lastX},${bottomY}`;
  };

  const getYAxisLabel = (index) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    const step = max / 4;
    return Math.round(step * (4 - index));
  };

  const getRankWidth = (count) => {
    const max = Math.max(...groupRanking.value.map((i) => i.messageCount), 1);
    return Math.max((count / max) * 100, 5);
  };

  const getQQRankWidth = (count) => {
    const max = Math.max(...qqRanking.value.map((i) => i.messageCount), 1);
    return Math.max((count / max) * 100, 5);
  };

  const getRankColor = (index) => {
    const colors = ['#e74c3c', '#e67e22', '#f1c40f', '#3498db', '#9b59b6'];
    return colors[index % colors.length];
  };

  const truncateName = (name) => {
    if (!name) return '未知群聊';
    return name.length > 12 ? `${name.substring(0, 12)}...` : name;
  };

  const truncateQQName = (name, qq) => {
    if (!name) return qq || '未知用户';
    return name.length > 10 ? `${name.substring(0, 10)}...` : name;
  };

  const showSystemMsg = (msg, type = 'success') => {
    systemMessage.value = msg;
    systemMessageType.value = type;
    setTimeout(() => { systemMessage.value = ''; }, 5000);
  };

  const wrapComponentAction = async (loadingRef, action, successMsg) => {
    loadingRef.value = true;
    try {
      const res = await action();
      showSystemMsg(res.message || successMsg);
      await getComponentStatus();
    } catch (error) {
      showSystemMsg(`${successMsg.replace('成功', '失败')}: ${error.message}`, 'error');
    } finally {
      loadingRef.value = false;
    }
  };

  const startAstrBot = () => wrapComponentAction(isStartingAstrBot, systemApi.startAstrBot, 'AstrBot 启动成功');
  const stopAstrBot = () => wrapComponentAction(isStoppingAstrBot, systemApi.stopAstrBot, 'AstrBot 停止成功');
  const startNapCat = async () => {
    isStartingNapCat.value = true;
    try {
      const res = await systemApi.startNapCat(autoLogin.value);
      showSystemMsg(res.message || 'NapCat 启动成功');
      await getComponentStatus();
      setTimeout(refreshQrCode, 3000);
    } catch (error) {
      showSystemMsg(`NapCat 启动失败: ${error.message}`, 'error');
    } finally {
      isStartingNapCat.value = false;
    }
  };
  const stopNapCat = () => wrapComponentAction(isStoppingNapCat, systemApi.stopNapCat, 'NapCat 停止成功');
  const startGptSovits = () => wrapComponentAction(isStartingGptSovits, systemApi.startGptSovits, 'GPT-SoVITS 启动成功');
  const stopGptSovits = () => wrapComponentAction(isStoppingGptSovits, systemApi.stopGptSovits, 'GPT-SoVITS 停止成功');
  
  const openGptSovitsWebUI = () => {
    window.open('http://localhost:8000', '_blank');
  };

  const startPolling = () => {
    stopPolling();
    statusInterval = setInterval(getComponentStatus, POLL_INTERVAL_MS);
  };

  const stopPolling = () => {
    if (statusInterval) {
      clearInterval(statusInterval);
      statusInterval = null;
    }
  };

  const loadDashboard = () => {
    loadStats();
    loadTrend();
    loadRanking();
    loadQQRanking();
    loadDiskUsage();
    getComponentStatus();
    refreshQrCode();
    if (autoLogin.value) checkNapCatLogin();
  };

  let isDown = false;
  let startX;
  let scrollLeft;

  const setupDragScroll = () => {
    const el = scrollWrapper.value;
    if (!el) return;

    const onMouseDown = (e) => {
      isDown = true;
      el.classList.add('dragging');
      startX = e.pageX - el.offsetLeft;
      scrollLeft = el.scrollLeft;
    };

    const onMouseLeave = () => {
      isDown = false;
      el.classList.remove('dragging');
    };

    const onMouseUp = () => {
      isDown = false;
      el.classList.remove('dragging');
    };

    const onMouseMove = (e) => {
      if (!isDown) return;
      e.preventDefault();
      const x = e.pageX - el.offsetLeft;
      const walk = (x - startX) * 1.5;
      el.scrollLeft = scrollLeft - walk;
    };

    el.addEventListener('mousedown', onMouseDown);
    el.addEventListener('mouseleave', onMouseLeave);
    el.addEventListener('mouseup', onMouseUp);
    el.addEventListener('mousemove', onMouseMove);

    return () => {
      el.removeEventListener('mousedown', onMouseDown);
      el.removeEventListener('mouseleave', onMouseLeave);
      el.removeEventListener('mouseup', onMouseUp);
      el.removeEventListener('mousemove', onMouseMove);
    };
  };

  let removeDragListeners = null;

  onMounted(() => {
    loadDashboard();
    startPolling();
    removeDragListeners = setupDragScroll();
  });

  onUnmounted(() => {
    stopPolling();
    if (removeDragListeners) removeDragListeners();
  });

  return {
    stats,
    messageTrend,
    trendDays,
    trendInterval,
    groupRanking,
    qqRanking,
    componentStatus,
    qrCode,
    systemMessage,
    systemMessageType,
    autoLogin,
    isCheckingLogin,
    diskUsage,
    tooltipVisible,
    tooltipData,
    tooltipStyle,
    scrollWrapper,
    isStartingAstrBot,
    isStoppingAstrBot,
    isStartingNapCat,
    isStoppingNapCat,
    isStartingGptSovits,
    isStoppingGptSovits,
    loadStats,
    loadTrend,
    loadRanking,
    loadQQRanking,
    getComponentStatus,
    refreshQrCode,
    onAutoLoginChange,
    checkNapCatLogin,
    getBarHeight,
    getLinePoints,
    getPointX,
    getPointY,
    getPointXPercent,
    getPointYPercent,
    getAreaPoints,
    getYAxisLabel,
    getRankWidth,
    getQQRankWidth,
    getRankColor,
    truncateName,
    truncateQQName,
    setTrendDays,
    showTooltip,
    hideTooltip,
    showSystemMsg,
    startAstrBot,
    stopAstrBot,
    startNapCat,
    stopNapCat,
    startGptSovits,
    stopGptSovits,
    openGptSovitsWebUI,
    loadDashboard,
    startPolling,
    stopPolling,
  };
}
