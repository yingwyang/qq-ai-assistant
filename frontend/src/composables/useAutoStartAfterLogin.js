import { showToast } from '../components/Toast.vue';
import { systemApi } from '../services/api';
import logger from '../utils/logger';

const ATTEMPTED_KEY = 'auto_start_attempted';

/**
 * 登录后自动启动：组件清单/顺序由服务端返回（按 descriptor order），
 * 不再维护前端 COMPONENTS 常量。
 */
export function useAutoStartAfterLogin() {
  const defaultShowMsg = (msg, type = 'info') => {
    showToast(msg, type);
  };

  const runSequence = async ({ showSystemMsg, force = false } = {}) => {
    if (!force && sessionStorage.getItem(ATTEMPTED_KEY) === '1') {
      return;
    }
    sessionStorage.setItem(ATTEMPTED_KEY, '1');

    const showMsg = showSystemMsg || defaultShowMsg;
    let components = [];
    try {
      const res = await systemApi.getComponents();
      components = (res && res.components) || [];
    } catch (error) {
      logger.warn('读取组件列表失败，跳过自动启动:', error);
      showMsg('读取组件列表失败，跳过自动启动: ' + error.message, 'warning');
      return;
    }

    const pending = components.filter((c) => c.enabled !== false && !c.running);
    if (pending.length === 0) {
      showMsg('组件已全部在运行', 'info');
      return;
    }

    const total = pending.length;
    for (let i = 0; i < pending.length; i += 1) {
      const comp = pending[i];
      const name = comp.displayName || comp.id;
      showMsg('启动组件 (' + (i + 1) + '/' + total + '): ' + name + '...', 'info');
      try {
        await systemApi.startComponent(comp.id);
        showMsg('启动组件 (' + (i + 1) + '/' + total + '): ' + name + ' 成功', 'success');
      } catch (error) {
        const errMsg = (error && error.message) || String(error);
        showMsg('启动组件 (' + (i + 1) + '/' + total + '): ' + name + ' 失败 - ' + errMsg, 'error');
      }
    }
  };

  return { runSequence };
}
