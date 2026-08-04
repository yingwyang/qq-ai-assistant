import { showToast } from '../components/Toast.vue';
import { useComponentControl } from './useComponentControl';

const COMPONENTS = [
  {
    key: 'napcat',
    name: 'NapCat',
    startMethod: 'startNapCat',
  },
  {
    key: 'astrbot',
    name: 'AstrBot',
    startMethod: 'startAstrBot',
  },
  {
    key: 'gptsovits',
    name: 'GPT-SoVITS',
    startMethod: 'startGptSovits',
  },
];

const ATTEMPTED_KEY = 'auto_start_attempted';

export function useAutoStartAfterLogin() {
  const defaultShowMsg = (msg, type = 'info') => {
    showToast(msg, type);
  };

  const runSequence = async ({ showSystemMsg, force = false } = {}) => {
    // session-level 去重：同一标签页登录后只自动尝试一次，避免反复调用导致
    // 频繁刷新 / 退出登录 / 表单丢失。force=true 供用户手动触发时使用。
    if (!force && sessionStorage.getItem(ATTEMPTED_KEY) === '1') {
      return;
    }
    sessionStorage.setItem(ATTEMPTED_KEY, '1');

    const showMsg = showSystemMsg || defaultShowMsg;
    const componentCtrl = useComponentControl({ showSystemMsg: showMsg });

    try {
      await componentCtrl.getComponentStatus();
    } catch (e) {
      console.warn('刷新组件状态失败，继续执行启动流程:', e);
    }

    const total = COMPONENTS.length;

    for (let i = 0; i < COMPONENTS.length; i++) {
      const comp = COMPONENTS[i];
      const step = i + 1;

      const isRunning =
        componentCtrl.componentStatus.value &&
        componentCtrl.componentStatus.value[comp.key] &&
        componentCtrl.componentStatus.value[comp.key].running;

      if (isRunning) {
        showMsg(`启动组件 (${step}/${total}): ${comp.name} 已在运行`, 'info');
        continue;
      }

      showMsg(`启动组件 (${step}/${total}): ${comp.name}...`, 'info');

      try {
        const startFn = componentCtrl[comp.startMethod];
        if (typeof startFn === 'function') {
          await startFn();
          showMsg(`启动组件 (${step}/${total}): ${comp.name} 成功`, 'success');
        } else {
          showMsg(`启动组件 (${step}/${total}): ${comp.name} 方法不存在`, 'warning');
        }
      } catch (error) {
        const errMsg = (error && error.message) || String(error);
        showMsg(`启动组件 (${step}/${total}): ${comp.name} 失败 - ${errMsg}`, 'error');
      }
    }
  };

  return {
    runSequence,
  };
}
