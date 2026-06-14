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
  const groupRanking = ref([]);
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
      messageTrend.value = await dashboardApi.getMessageTrend();
    } catch (error) {
      console.error('加载消息趋势失败:', error);
    }
  };

  const loadRanking = async () => {
    try {
      groupRanking.value = await dashboardApi.getGroupRanking();
    } catch (error) {
      console.error('加载群聊排行失败:', error);
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

  const getRankWidth = (count) => {
    const max = Math.max(...groupRanking.value.map((i) => i.messageCount), 1);
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
    getComponentStatus();
    refreshQrCode();
    if (autoLogin.value) checkNapCatLogin();
  };

  onMounted(() => {
    loadDashboard();
    startPolling();
  });

  onUnmounted(stopPolling);

  return {
    stats,
    messageTrend,
    groupRanking,
    componentStatus,
    qrCode,
    systemMessage,
    systemMessageType,
    autoLogin,
    isCheckingLogin,
    isStartingAstrBot,
    isStoppingAstrBot,
    isStartingNapCat,
    isStoppingNapCat,
    isStartingGptSovits,
    isStoppingGptSovits,
    loadStats,
    loadTrend,
    loadRanking,
    getComponentStatus,
    refreshQrCode,
    onAutoLoginChange,
    checkNapCatLogin,
    getBarHeight,
    getRankWidth,
    getRankColor,
    truncateName,
    showSystemMsg,
    startAstrBot,
    stopAstrBot,
    startNapCat,
    stopNapCat,
    startGptSovits,
    stopGptSovits,
    loadDashboard,
    startPolling,
    stopPolling,
  };
}
