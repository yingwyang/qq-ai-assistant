import { ref, computed } from 'vue';
import { creditsApi } from '../services/api';
import { showToast } from '../components/Toast.vue';

// ===== 全局单例状态（module-level ref，多组件共享同一引用）=====
const balance = ref(0);
const tier = ref('FREE');
const expiresAt = ref(null);
const todaySigned = ref(false);
const loading = ref(false);
const isSigningIn = ref(false);
let loaded = false;

const tierLabel = computed(() => {
  const map = {
    FREE: '免费版',
    LITE: '轻享版',
    PRO: '专业版',
    PROPLUS: '旗舰版',
    PRO_PLUS: '旗舰版',
    ULTRA: '至尊版',
  };
  return map[tier.value] || tier.value || '免费版';
});

/**
 * 用户积分全局共享状态。
 * 通过 module-level ref 实现单例：UserMenuPopover / Sidebar / UserCenter
 * 引用的是同一份 balance / todaySigned，签到成功后三处自动同步。
 */
export function useUserCreditsStore() {
  const reload = async () => {
    loading.value = true;
    try {
      // creditsApi.getBalance() 经 parseResponse 解包后直接返回 data 对象
      const data = await creditsApi.getBalance();
      balance.value = Number(data?.balance ?? 0);
      tier.value = data?.subscriptionTier || data?.tier || 'FREE';
      expiresAt.value = data?.subscriptionExpiresAt || data?.tierExpireAt || null;
      todaySigned.value = !!(data?.todaySignInDone ?? data?.todayDone ?? false);
    } catch (error) {
      // 401 已由 api.js 统一处理（清 token + auth:logout），这里静默失败
      console.error('加载积分余额失败:', error);
    } finally {
      loading.value = false;
    }
  };

  /**
   * 立即签到。
   * 成功返回 { points, streakDays, newBalance }，并更新共享 balance / todaySigned。
   * 失败已在内部捕获并按 errorCode 显示友好提示，仍抛出规范化错误供调用方做 UI 收尾。
   */
  const signIn = async () => {
    if (isSigningIn.value) return;
    if (todaySigned.value) {
      showToast('今天已签到过了', 'info');
      const err = new Error('今天已签到过了');
      err.errorCode = 'ALREADY_SIGNED_IN';
      todaySigned.value = true;
      throw err;
    }
    isSigningIn.value = true;
    try {
      const data = await creditsApi.signIn();
      const points = Number(data?.points ?? 0);
      if (data?.newBalance !== undefined && data?.newBalance !== null) {
        balance.value = Number(data.newBalance);
      } else {
        balance.value += points;
      }
      todaySigned.value = true;
      showToast(`签到成功 +${points}`, 'success');
      return data;
    } catch (error) {
      const code = error?.errorCode;
      const msg = error?.message || '';

      if (code === 'ALREADY_SIGNED_IN') {
        todaySigned.value = true;
        showToast('今天已签到过了', 'info');
        const err = new Error('今天已签到过了');
        err.errorCode = 'ALREADY_SIGNED_IN';
        throw err;
      }

      // 网络断开 / 后端未启动 / 500 等服务器内部错误统一提示
      const isNetworkError =
        error?.name === 'TypeError' ||
        msg.includes('Failed to fetch') ||
        msg.includes('服务暂时不可用') ||
        msg.includes('无法连接到服务器') ||
        msg.includes('getsockopt') ||
        code === 'INTERNAL_ERROR' ||
        /^5\d\d$/.test(code) ||
        msg.includes('HTTP error! status: 5');

      if (isNetworkError) {
        showToast('服务繁忙，请稍后重试', 'error');
        const err = new Error('服务繁忙，请稍后重试');
        err.errorCode = 'SERVICE_UNAVAILABLE';
        throw err;
      }

      showToast(msg || '服务繁忙，请稍后重试', 'error');
      throw error;
    } finally {
      isSigningIn.value = false;
    }
  };

  // 首次引用时自动加载一次（后续组件复用同一份已加载状态）
  if (!loaded) {
    loaded = true;
    reload().catch(() => {});
  }

  return {
    balance,
    tier,
    tierLabel,
    expiresAt,
    todaySigned,
    loading,
    isSigningIn,
    reload,
    signIn,
  };
}
