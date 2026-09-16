import { ref, computed, watch } from 'vue';
import { creditsApi } from '../services/api';
import { useUserCreditsStore } from './useUserCreditsStore';
import { useCreditsChart } from './useCreditsChart';
import logger from '../utils/logger';

export const TX_TYPE_LABELS = {
  AI_CHAT: 'AI 对话',
  DAILY_SIGN_IN: '每日签到',
  SUBSCRIPTION_PURCHASE: '订阅购买',
  SUBSCRIPTION_RENEWAL: '订阅续费',
  NEW_USER_BONUS: '新用户福利',
  MONTHLY_LOGIN_BONUS: '每月登录赠送',
  LOYALTY_BONUS: '老用户福利',
  REFUND: '退款',
  ADMIN_ADJUST: '管理员调整',
  ADMIN_ADJUSTMENT: '管理员调整',
  OTHER: '其他',
};

export const TX_TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'AI_CHAT', label: 'AI 对话' },
  { value: 'DAILY_SIGN_IN', label: '每日签到' },
  { value: 'SUBSCRIPTION_PURCHASE', label: '订阅购买' },
  { value: 'SUBSCRIPTION_RENEWAL', label: '订阅续费' },
  { value: 'NEW_USER_BONUS', label: '新用户福利' },
  { value: 'MONTHLY_LOGIN_BONUS', label: '每月登录赠送' },
  { value: 'LOYALTY_BONUS', label: '老用户福利' },
  { value: 'REFUND', label: '退款' },
  { value: 'ADMIN_ADJUST', label: '管理员调整' },
  { value: 'OTHER', label: '其他' },
];

export function useCreditsDashboard({ showSystemMsg, onOpenUpgrade, onSwitchTab } = {}) {
  // 共享积分状态（与 UserMenuPopover / Sidebar 同步）
  const {
    signIn, isSigningIn, todaySigned: storeTodaySigned,
    signInPoints, signInBasePoints, monthlyCardBonus, monthlyCardBonusTier, monthlyCardTier,
  } = useUserCreditsStore();

  // 月卡档位中文名（用于签到卡上标注"含大小月卡额外 +400"）
  const monthlyCardLabel = computed(() => {
    const map = { SMALL_MONTH_CARD: '小月卡', LARGE_MONTH_CARD: '大月卡', ALL: '大小月卡' };
    return map[monthlyCardTier.value] || '月卡';
  });

  const balance = ref(0);
  const totalEarned = ref(0);
  const totalSpent = ref(0);
  const tier = ref('FREE');
  const tierExpireAt = ref(null);

  const signInStatus = ref({ signedIn: false, consecutiveDays: 0 });

  // 与全局共享状态同步：其他组件（UserMenuPopover）签到后，本页按钮立即变为已签
  watch(storeTodaySigned, (signed) => {
    if (signed && !signInStatus.value.signedIn) {
      signInStatus.value = { ...signInStatus.value, signedIn: true };
    }
  });

  const rewards = ref([]);
  const rewardsLoading = ref(false);

  const transactions = ref([]);
  const transactionsTotal = ref(0);
  const transactionsLoading = ref(false);
  const txPage = ref(0);
  const txSize = ref(20);
  const txIncomeTotal = ref(0);
  const txSpendTotal = ref(0);
  const txNet = ref(0);

  const activeDetailTab = ref('ALL');
  const filters = ref({
    type: '',
    startDate: '',
    endDate: '',
    relatedId: '',
  });

  const trendDays = ref(7);
  const trendData = ref([]);
  const trendLoading = ref(false);

  const isLoading = ref(false);
  const highlightSignIn = ref(false);

  // 积分趋势图表（封装于 useCreditsChart）
  const { trendChartOption } = useCreditsChart(trendData);

  const formatNumber = (n) => {
    if (n === null || n === undefined) return '0';
    return Number(n).toLocaleString('zh-CN');
  };

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

  const loadBalance = async () => {
    try {
      const data = await creditsApi.getBalance();
      balance.value = data?.balance ?? 0;
      totalEarned.value = data?.totalEarned ?? 0;
      totalSpent.value = data?.totalSpent ?? 0;
      // 后端字段 subscriptionTier / subscriptionExpiresAt
      tier.value = data?.subscriptionTier || data?.tier || 'FREE';
      tierExpireAt.value = data?.subscriptionExpiresAt || data?.tierExpireAt || null;
    } catch (error) {
      logger.error('加载积分余额失败:', error);
    }
  };

  const loadSignInStatus = async (range = 7) => {
    try {
      const data = await creditsApi.getSignInStatus(range);
      // 后端字段 todayDone / streakDays
      signInStatus.value = {
        signedIn: data?.todayDone ?? data?.signedIn ?? false,
        consecutiveDays: data?.streakDays ?? data?.consecutiveDays ?? 0,
        calendar: data?.calendar || [],
      };
    } catch (error) {
      logger.error('加载签到状态失败:', error);
    }
  };

  const loadRewards = async () => {
    rewardsLoading.value = true;
    try {
      rewards.value = await creditsApi.getRewards() || [];
    } catch (error) {
      logger.error('加载奖励列表失败:', error);
      rewards.value = [];
    } finally {
      rewardsLoading.value = false;
    }
  };

  const loadTransactions = async (page = 0, size = 20, direction = null) => {
    transactionsLoading.value = true;
    try {
      // 后端 CreditDirection 枚举为 IN / OUT
      const dir = direction || (activeDetailTab.value === 'INCOME' ? 'IN'
        : activeDetailTab.value === 'EXPENSE' ? 'OUT' : null);
      const params = {
        page,
        size,
        direction: dir,
        type: filters.value.type || undefined,
        // 后端使用 start/end；旧字段 startDate/endDate 兼容
        start: filters.value.startDate || undefined,
        end: filters.value.endDate || undefined,
        relatedId: filters.value.relatedId || undefined,
      };
      const data = await creditsApi.getTransactions(params);
      // 后端返回 { content, totalElements, totalPages, incomeTotal, spendTotal, net }
      transactions.value = data?.content || data?.items || data?.list || data || [];
      transactionsTotal.value = data?.totalElements ?? data?.total ?? transactions.value.length;
      txIncomeTotal.value = data?.incomeTotal ?? 0;
      txSpendTotal.value = data?.spendTotal ?? 0;
      txNet.value = data?.net ?? (txIncomeTotal.value - txSpendTotal.value);
    } catch (error) {
      logger.error('加载交易明细失败:', error);
      transactions.value = [];
      transactionsTotal.value = 0;
    } finally {
      transactionsLoading.value = false;
    }
  };

  const loadTrend = async (days) => {
    trendLoading.value = true;
    try {
      const data = await creditsApi.getTrend(days);
      // 后端返回 { series: [{ date, earned, spent, net }] }
      trendData.value = (data?.series || (Array.isArray(data) ? data : []));
    } catch (error) {
      logger.error('加载积分趋势失败:', error);
      trendData.value = [];
    } finally {
      trendLoading.value = false;
    }
  };

  const loadAll = async () => {
    isLoading.value = true;
    try {
      await Promise.all([
        loadBalance(),
        loadSignInStatus(),
        loadRewards(),
        loadTransactions(txPage.value, txSize.value),
        loadTrend(trendDays.value),
      ]);
    } finally {
      isLoading.value = false;
    }
  };

  const doSignIn = async () => {
    if (signInStatus.value.signedIn || isSigningIn.value) return;
    try {
      // 统一走共享 store.signIn()，错误提示与加载状态由 store 兜底
      const result = await signIn();
      // 后端返回 { points, streakDays, newBalance }
      const points = result?.points ?? 150;
      const newStreak = result?.streakDays ?? (signInStatus.value.consecutiveDays || 0) + 1;
      signInStatus.value = {
        ...signInStatus.value,
        signedIn: true,
        consecutiveDays: newStreak,
      };
      totalEarned.value = totalEarned.value + points;
      // 刷新明细、余额、趋势图、奖励列表
      loadTransactions(txPage.value, txSize.value);
      loadBalance();
      loadTrend(trendDays.value);
      loadRewards();
      // 通知订阅管理页刷新余额
      window.dispatchEvent(new CustomEvent('credits:sign-in-success'));
    } catch (error) {
      // store 已按 errorCode 显示友好提示，这里只做本地状态同步，避免未捕获异常
      if (error?.errorCode === 'ALREADY_SIGNED_IN') {
        signInStatus.value.signedIn = true;
      }
    }
  };

  const changeDetailTab = (tab) => {
    activeDetailTab.value = tab;
    txPage.value = 0;
    loadTransactions(0, txSize.value);
  };

  const searchTransactions = (page = 0) => {
    txPage.value = page;
    loadTransactions(page, txSize.value);
  };

  const resetFilters = () => {
    filters.value = { type: '', startDate: '', endDate: '', relatedId: '' };
    searchTransactions(0);
  };

  const switchTrendDays = (days) => {
    trendDays.value = days;
    loadTrend(days);
  };

  const gotoSubscription = () => {
    onSwitchTab?.('subscription');
    onOpenUpgrade?.();
  };

  const totalTxPages = computed(() => {
    return Math.max(1, Math.ceil(transactionsTotal.value / txSize.value));
  });

  const txTypeLabel = (type) => TX_TYPE_LABELS[type] || type || '其他';

  const triggerSignInHighlight = () => {
    highlightSignIn.value = true;
    setTimeout(() => { highlightSignIn.value = false; }, 2000);
  };

  return {
    balance, totalEarned, totalSpent, tier, tierExpireAt, tierLabel,
    signInStatus, isSigningIn,
    signInPoints, signInBasePoints, monthlyCardBonus, monthlyCardBonusTier, monthlyCardTier, monthlyCardLabel,
    rewards, rewardsLoading,
    transactions, transactionsTotal, transactionsLoading,
    txPage, txSize, totalTxPages,
    txIncomeTotal, txSpendTotal, txNet,
    activeDetailTab, filters,
    trendDays, trendData, trendLoading, trendChartOption,
    isLoading, highlightSignIn,
    formatNumber, txTypeLabel,
    loadAll, loadBalance, loadSignInStatus, loadRewards, loadTransactions, loadTrend,
    doSignIn, changeDetailTab, searchTransactions, resetFilters, switchTrendDays, gotoSubscription,
    triggerSignInHighlight,
  };
}
