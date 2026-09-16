import { ref, computed } from 'vue';
import { creditsApi } from '../services/api';
import { showToast } from '../components/Toast.vue';
import logger from '../utils/logger';

// ===== 全局单例状态（module-level ref，多组件共享同一引用）=====
const balance = ref(0);
const tier = ref('FREE');
const expiresAt = ref(null);
const todaySigned = ref(false);
const loading = ref(false);
const isSigningIn = ref(false);
// 每日签到可得积分 = 基础签到分 + 月卡每日额外积分（后端 /credits/balance 下发）
const signInPoints = ref(0);
const signInBasePoints = ref(0);
const monthlyCardBonus = ref(0);       // 今日还可领取的月卡额外积分（已发过则为 0）
const monthlyCardBonusTier = ref(0);   // 月卡档位对应的每日额外积分（固定值，用于标注）
const monthlyCardTier = ref(null);
let loaded = false;  // 标记"是否成功加载过"，失败不标记，允许下次引用时重试

const tierLabel = computed(() => {
  const map = {
    FREE: '免费版',
    LITE: '直购积分·600',
    PRO: '直购积分·3500',
    PROPLUS: '直购积分·16000',
    PRO_PLUS: '直购积分·16000',
    ULTRA: '直购积分·45000',
    MEGA: '直购积分·100000',
    SMALL_MONTH_CARD: '小月卡',
    LARGE_MONTH_CARD: '大月卡',
    ALL: '全功能版',
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
      // 后端 CreditsBalance record 字段名是 tier + expiresAt
      tier.value = data?.subscriptionTier || data?.tier || 'FREE';
      expiresAt.value = data?.subscriptionExpiresAt || data?.tierExpireAt || data?.expiresAt || null;
      todaySigned.value = !!(data?.todaySignInDone ?? data?.todayDone ?? data?.todaySigned ?? false);
      signInBasePoints.value = Number(data?.signInBasePoints ?? 0);
      monthlyCardBonus.value = Number(data?.monthlyCardBonus ?? 0);
      monthlyCardBonusTier.value = Number(data?.monthlyCardBonusTier ?? data?.monthlyCardBonus ?? 0);
      monthlyCardTier.value = data?.monthlyCardTier || null;
      signInPoints.value = Number(data?.signInPoints ?? (signInBasePoints.value + monthlyCardBonus.value));
      loaded = true;  // 成功才标记，失败允许后续引用时重试
    } catch (error) {
      // 401 已由 api.js 统一处理（清 token + auth:logout），这里静默失败
      logger.error('加载积分余额失败:', error);
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
      const cardBonus = Number(data?.monthlyCardBonus ?? 0);
      if (data?.newBalance !== undefined && data?.newBalance !== null) {
        balance.value = Number(data.newBalance);
      } else {
        balance.value += points;
      }
      todaySigned.value = true;
      // 月卡用户提示里点出加成部分，让"每日签到积分"这条权益可见
      showToast(
        cardBonus > 0 ? `签到成功 +${points}（含月卡额外 +${cardBonus}）` : `签到成功 +${points}`,
        'success'
      );
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

  const reset = () => {
    balance.value = 0;
    tier.value = 'FREE';
    expiresAt.value = null;
    todaySigned.value = false;
    signInPoints.value = 0;
    signInBasePoints.value = 0;
    monthlyCardBonus.value = 0;
    monthlyCardBonusTier.value = 0;
    monthlyCardTier.value = null;
    loaded = false;  // 登出切换账号时重置缓存，允许新账号重新加载
  };

  return {
    balance,
    tier,
    tierLabel,
    expiresAt,
    todaySigned,
    loading,
    isSigningIn,
    signInPoints,
    signInBasePoints,
    monthlyCardBonus,
    monthlyCardBonusTier,
    monthlyCardTier,
    reload,
    reset,
    signIn,
  };
}
