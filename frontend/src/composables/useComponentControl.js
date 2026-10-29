import { computed, ref } from 'vue';
import { systemApi } from '../services/api';
import logger from '../utils/logger';

const POLL_INTERVAL_MS = 5000;

/**
 * 组件控制（数据驱动）：组件列表来自 /api/system/components，
 * 启停走统一接口；NapCat 的二维码/登录态/WebUI 仍保留专属逻辑。
 */
export function useComponentControl({ showSystemMsg } = {}) {
  const components = ref([]);
  /** { [id]: 'starting' | 'stopping' } */
  const pending = ref({});
  const componentErrors = ref({});
  const lastStatusAt = ref('');
  const isRefreshing = ref(false);
  const isStartingAll = ref(false);
  const isStoppingAll = ref(false);

  const qrCode = ref('');
  /** NapCat 登录态（扫码成功后 UI 要切到「已登录」，不能再显示二维码） */
  const napCatLogin = ref({ checked: false, loggedIn: false, qq: '', nickname: '', error: '' });
  const napCatWebUiUrl = ref('');
  const autoLogin = ref(localStorage.getItem('napcat_auto_login') === 'true');
  const isCheckingLogin = ref(false);

  let statusInterval = null;

  /** 兼容旧调用方：astrbot / napcat / gptsovits 三键视角 */
  const componentStatus = computed(() => {
    const map = {
      astrbot: { running: false, state: 'STOPPED' },
      napcat: { running: false, state: 'STOPPED' },
      gptsovits: { running: false, state: 'STOPPED' },
    };
    for (const c of components.value) {
      map[c.legacyKey || c.id] = { running: !!c.running, state: c.state };
    }
    return map;
  });

  const isStartingAstrBot = computed(() => pending.value.astrbot === 'starting');
  const isStoppingAstrBot = computed(() => pending.value.astrbot === 'stopping');
  const isStartingNapCat = computed(() => pending.value.napcat === 'starting');
  const isStoppingNapCat = computed(() => pending.value.napcat === 'stopping');
  const isStartingGptSovits = computed(() => pending.value['gpt-sovits'] === 'starting');
  const isStoppingGptSovits = computed(() => pending.value['gpt-sovits'] === 'stopping');

  const setPending = (id, value) => {
    pending.value = { ...pending.value, [id]: value };
  };

  const getComponentStatus = async () => {
    isRefreshing.value = true;
    try {
      const res = await systemApi.getComponents();
      components.value = (res && res.components) || [];
      lastStatusAt.value = new Date().toLocaleTimeString();
    } catch (error) {
      logger.error('读取组件状态失败:', error);
      if (showSystemMsg) showSystemMsg('读取组件状态失败: ' + error.message, 'error');
    } finally {
      isRefreshing.value = false;
    }
  };

  const startComponent = async (id) => {
    setPending(id, 'starting');
    componentErrors.value = { ...componentErrors.value, [id]: '' };
    try {
      await systemApi.startComponent(id);
      if (showSystemMsg) showSystemMsg('组件启动成功: ' + id);
    } catch (error) {
      componentErrors.value = { ...componentErrors.value, [id]: error.message };
      if (showSystemMsg) showSystemMsg('组件启动失败 ' + id + ': ' + error.message, 'error');
    } finally {
      setPending(id, null);
      await getComponentStatus();
    }
  };

  const stopComponent = async (id) => {
    setPending(id, 'stopping');
    componentErrors.value = { ...componentErrors.value, [id]: '' };
    try {
      await systemApi.stopComponent(id, { force: true });
      if (showSystemMsg) showSystemMsg('组件已停止: ' + id);
    } catch (error) {
      componentErrors.value = { ...componentErrors.value, [id]: error.message };
      if (showSystemMsg) showSystemMsg('组件停止失败 ' + id + ': ' + error.message, 'error');
    } finally {
      setPending(id, null);
      await getComponentStatus();
    }
  };

  const refreshNapCatLoginStatus = async () => {
    const running = componentStatus.value.napcat?.running;
    if (!running) {
      napCatLogin.value = { checked: true, loggedIn: false, qq: '', nickname: '', error: '' };
      return napCatLogin.value;
    }
    try {
      const res = await systemApi.checkNapCatLoginStatus();
      napCatLogin.value = {
        checked: true,
        loggedIn: !!(res && res.loggedIn),
        qq: (res && res.qq) || '',
        nickname: (res && res.nickname) || '',
        error: '',
      };
    } catch (error) {
      logger.error('读取 NapCat 登录状态失败:', error);
      napCatLogin.value = { checked: true, loggedIn: false, qq: '', nickname: '', error: error.message };
    }
    return napCatLogin.value;
  };

  /** 重启组件（NapCat 重启会断开 QQ 登录，调用方需确认） */
  const restartComponent = async (id) => {
    setPending(id, 'restarting');
    componentErrors.value = { ...componentErrors.value, [id]: '' };
    try {
      await systemApi.restartComponent(id);
      if (showSystemMsg) showSystemMsg('组件已重启: ' + id);
    } catch (error) {
      componentErrors.value = { ...componentErrors.value, [id]: error.message };
      if (showSystemMsg) showSystemMsg('组件重启失败 ' + id + ': ' + error.message, 'error');
    } finally {
      setPending(id, null);
      await getComponentStatus();
      await refreshNapCatLoginStatus();
    }
  };

  const startAllComponents = async () => {
    isStartingAll.value = true;
    try {
      for (const c of components.value) {
        if (c.running) continue;
        await startComponent(c.id);
      }
    } finally {
      isStartingAll.value = false;
    }
  };

  const stopAllComponents = async () => {
    isStoppingAll.value = true;
    try {
      for (const c of components.value) {
        if (!c.running) continue;
        await stopComponent(c.id);
      }
    } finally {
      isStoppingAll.value = false;
    }
  };

  // 兼容旧调用方
  const startAstrBot = () => startComponent('astrbot');
  const stopAstrBot = () => stopComponent('astrbot');
  const startNapCat = () => startComponent('napcat');
  const stopNapCat = () => stopComponent('napcat');
  const startGptSovits = () => startComponent('gpt-sovits');
  const stopGptSovits = () => stopComponent('gpt-sovits');

  const refreshQrCode = () => {
    qrCode.value = '/api/system/napcat/qrcode-image?timestamp=' + Date.now();
  };

  const onAutoLoginChange = () => {
    localStorage.setItem('napcat_auto_login', autoLogin.value);
  };

  const checkNapCatLogin = async () => {
    if (!autoLogin.value) return;
    isCheckingLogin.value = true;
    try {
      const res = await systemApi.checkNapCatLoginStatus();
      if (res && res.loggedIn && showSystemMsg) {
        showSystemMsg('NapCat 已自动登录: ' + (res.qq || ''), 'success');
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
      if (res && res.url) napCatWebUiUrl.value = res.url;
    } catch (error) {
      logger.error('获取 NapCat WebUI 地址失败:', error);
    }
  };

  const openNapCatWebUI = () => {
    window.open(napCatWebUiUrl.value || 'http://127.0.0.1:6099/webui', '_blank');
  };

  const startPolling = () => {
    stopPolling();
    getComponentStatus();
    statusInterval = setInterval(() => getComponentStatus(), POLL_INTERVAL_MS);
  };

  const stopPolling = () => {
    if (statusInterval) {
      clearInterval(statusInterval);
      statusInterval = null;
    }
  };

  return {
    components,
    pending,
    componentStatus,
    componentErrors,
    lastStatusAt,
    isRefreshing,
    isStartingAll,
    isStoppingAll,
    qrCode,
    napCatLogin,
    napCatWebUiUrl,
    autoLogin,
    isCheckingLogin,
    isStartingAstrBot,
    isStoppingAstrBot,
    isStartingNapCat,
    isStoppingNapCat,
    isStartingGptSovits,
    isStoppingGptSovits,
    getComponentStatus,
    refreshNapCatLoginStatus,
    startComponent,
    stopComponent,
    restartComponent,
    startAllComponents,
    stopAllComponents,
    startAstrBot,
    stopAstrBot,
    startNapCat,
    stopNapCat,
    startGptSovits,
    stopGptSovits,
    refreshQrCode,
    onAutoLoginChange,
    checkNapCatLogin,
    loadNapCatWebUiUrl,
    openNapCatWebUI,
    startPolling,
    stopPolling,
  };
}
