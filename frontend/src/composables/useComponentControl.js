import { ref } from 'vue';
import { systemApi } from '../services/api';
import logger from '../utils/logger';

const POLL_INTERVAL_MS = 5000;

export function useComponentControl({ showSystemMsg } = {}) {
  const componentStatus = ref({
    astrbot: { running: false },
    napcat: { running: false },
    gptsovits: { running: false },
  });
  const qrCode = ref('');
  const napCatWebUiUrl = ref('');
  const autoLogin = ref(localStorage.getItem('napcat_auto_login') === 'true');
  const isCheckingLogin = ref(false);
  const isStartingAstrBot = ref(false);
  const isStoppingAstrBot = ref(false);
  const isStartingNapCat = ref(false);
  const isStoppingNapCat = ref(false);
  const isStartingGptSovits = ref(false);
  const isStoppingGptSovits = ref(false);

  let statusInterval = null;

  const getComponentStatus = async () => {
    try {
      componentStatus.value = await systemApi.getComponentStatus();
    } catch (error) {
      logger.error('获取组件状态失败:', error);
    }
  };

  const refreshQrCode = () => {
    const timestamp = Date.now();
    qrCode.value = `/api/system/napcat/qrcode-image?timestamp=${timestamp}`;
  };

  const onAutoLoginChange = () => {
    localStorage.setItem('napcat_auto_login', autoLogin.value);
  };

  const checkNapCatLogin = async () => {
    if (!autoLogin.value) return;
    isCheckingLogin.value = true;
    try {
      const res = await systemApi.checkNapCatLoginStatus();
      if (res.loggedIn && showSystemMsg) {
        showSystemMsg(`NapCat 已自动登录: ${res.qq || ''}`, 'success');
      }
    } catch (error) {
      logger.error('检查 NapCat 登录状态失败:', error);
    } finally {
      isCheckingLogin.value = false;
    }
  };

  const loadNapCatWebUiUrl = async () => {
    try {
      const res = await systemApi.getNapCatWebUiUrl();
      if (res.url) {
        napCatWebUiUrl.value = res.url;
      }
    } catch (error) {
      logger.error('获取 NapCat WebUI 地址失败:', error);
    }
  };

  const openNapCatWebUI = () => {
    const url = napCatWebUiUrl.value || 'http://127.0.0.1:6099/webui';
    window.open(url, '_blank');
  };

  const openGptSovitsWebUI = () => {
    const url = 'http://localhost:9874';
    window.open(url, '_blank');
  };

  const startAstrBot = async () => {
    isStartingAstrBot.value = true;
    try {
      const res = await systemApi.startAstrBot();
      if (showSystemMsg) showSystemMsg(res.message || 'AstrBot 启动成功');
      await getComponentStatus();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('AstrBot 启动失败: ' + error.message, 'error');
    } finally {
      isStartingAstrBot.value = false;
    }
  };

  const stopAstrBot = async () => {
    isStoppingAstrBot.value = true;
    try {
      const res = await systemApi.stopAstrBot();
      if (showSystemMsg) showSystemMsg(res.message || 'AstrBot 停止成功');
      await getComponentStatus();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('AstrBot 停止失败: ' + error.message, 'error');
    } finally {
      isStoppingAstrBot.value = false;
    }
  };

  const startNapCat = async () => {
    isStartingNapCat.value = true;
    try {
      const res = await systemApi.startNapCat(autoLogin.value);
      if (showSystemMsg) showSystemMsg(res.message || 'NapCat 启动成功');
      await getComponentStatus();
      setTimeout(refreshQrCode, 3000);
    } catch (error) {
      if (showSystemMsg) showSystemMsg('NapCat 启动失败: ' + error.message, 'error');
    } finally {
      isStartingNapCat.value = false;
    }
  };

  const stopNapCat = async () => {
    isStoppingNapCat.value = true;
    try {
      const res = await systemApi.stopNapCat();
      if (showSystemMsg) showSystemMsg(res.message || 'NapCat 停止成功');
      await getComponentStatus();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('NapCat 停止失败: ' + error.message, 'error');
    } finally {
      isStoppingNapCat.value = false;
    }
  };

  const startGptSovits = async () => {
    isStartingGptSovits.value = true;
    try {
      const res = await systemApi.startGptSovits();
      if (showSystemMsg) showSystemMsg(res.message || 'GPT-SoVITS 启动成功');
      await getComponentStatus();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('GPT-SoVITS 启动失败: ' + error.message, 'error');
    } finally {
      isStartingGptSovits.value = false;
    }
  };

  const stopGptSovits = async () => {
    isStoppingGptSovits.value = true;
    try {
      const res = await systemApi.stopGptSovits();
      if (showSystemMsg) showSystemMsg(res.message || 'GPT-SoVITS 停止成功');
      await getComponentStatus();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('GPT-SoVITS 停止失败: ' + error.message, 'error');
    } finally {
      isStoppingGptSovits.value = false;
    }
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

  return {
    componentStatus, qrCode, napCatWebUiUrl, autoLogin, isCheckingLogin,
    isStartingAstrBot, isStoppingAstrBot,
    isStartingNapCat, isStoppingNapCat,
    isStartingGptSovits, isStoppingGptSovits,
    getComponentStatus, refreshQrCode, onAutoLoginChange, checkNapCatLogin,
    loadNapCatWebUiUrl, openNapCatWebUI, openGptSovitsWebUI,
    startAstrBot, stopAstrBot, startNapCat, stopNapCat, startGptSovits, stopGptSovits,
    startPolling, stopPolling,
  };
}
