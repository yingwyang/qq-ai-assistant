import { ref, reactive, computed } from 'vue';
import { adminCreditsApi } from '../services/api';

// 积分流水 Tab：多条件搜索 + 分页 + 顶栏汇总 + 导出
// 后端 CreditTransactionType: NEW_USER_BONUS, SIGN_IN, AI_CONSUMPTION, AI_CHAT,
// AI_ANALYZE, SUBSCRIPTION_PURCHASE, REFUND, ADMIN_GRANT, ADMIN_DEDUCT, EXPIRE, MONTHLY_CARD_DAILY
// 后端 CreditDirection: IN / OUT
export const TX_TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'NEW_USER_BONUS', label: '新人奖励' },
  { value: 'SIGN_IN', label: '签到' },
  { value: 'AI_CONSUMPTION', label: 'AI 消费' },
  { value: 'AI_CHAT', label: 'AI 对话' },
  { value: 'AI_ANALYZE', label: 'AI 分析' },
  { value: 'TTS_SYNTHESIS', label: '语音合成' },
  { value: 'SUBSCRIPTION_PURCHASE', label: '订阅购买' },
  { value: 'MONTHLY_CARD_DAILY', label: '月卡每日奖励' },
  { value: 'REFUND', label: '退款' },
  { value: 'ADMIN_GRANT', label: '管理员发放' },
  { value: 'ADMIN_DEDUCT', label: '管理员扣减' },
  { value: 'EXPIRE', label: '过期' },
  { value: 'CASH_INCOME', label: '现金收入（模拟）' },
  { value: 'CASH_EXPENSE', label: '现金支出（模拟）' },
];

/** 模拟现金账的类别（与后端 CashLedgerService 的常量一致） */
export const CASH_CATEGORY_OPTIONS = [
  { value: 'MANUAL', label: '手工记账' },
  { value: 'SUBSCRIPTION', label: '订阅收入' },
  { value: 'REFUND', label: '订单退款' },
  { value: 'AI_COST', label: '模型调用成本' },
];

export const TX_DIRECTION_OPTIONS = [
  { value: '', label: '全部方向' },
  { value: 'IN', label: '收入' },
  { value: 'OUT', label: '支出' },
];

export function txTypeText(t) {
  const found = TX_TYPE_OPTIONS.find((o) => o.value === t);
  return found ? found.label : (t || '-');
}

export function useAdminTransactions({ showSystemMsg } = {}) {
  const txList = ref([]);
  const txLoading = ref(false);
  const txPage = ref(0);
  const txSize = ref(20);
  const txTotalElements = ref(0);
  const txTotalPages = ref(0);
  const txExporting = ref(false);
  const txTotalSummary = ref({ totalEarned: 0, totalSpent: 0, totalNet: 0, totalCount: 0 });

  // 筛选条件
  const filters = reactive({
    userId: '',
    type: '',
    direction: '',
    start: '',
    end: '',
    min: '',
    max: '',
    relatedId: '',
  });

  // 顶栏汇总：基于当前页内容计算（后端响应未提供 summary，使用本页近似）
  const summary = computed(() => {
    let earned = 0;
    let spent = 0;
    for (const tx of txList.value) {
      const amt = Number(tx.amount) || 0;
      if (tx.direction === 'IN' || amt > 0) earned += Math.abs(amt);
      else spent += Math.abs(amt);
    }
    return {
      earned,
      spent,
      net: earned - spent,
      count: txList.value.length,
    };
  });

  function buildParams(forExport = false) {
    const p = {};
    if (filters.userId !== '' && filters.userId !== null) p.userId = filters.userId;
    if (filters.type) p.type = filters.type;
    if (filters.direction) p.direction = filters.direction;
    if (filters.start) p.start = filters.start;
    if (filters.end) p.end = filters.end;
    if (filters.min !== '' && filters.min !== null) p.min = filters.min;
    if (filters.max !== '' && filters.max !== null) p.max = filters.max;
    if (filters.relatedId) p.relatedId = filters.relatedId;
    if (forExport) {
      p.export = 1;
      p.format = forExport === 'csv' ? 'csv' : 'json';
    } else {
      p.page = txPage.value;
      p.size = txSize.value;
    }
    return p;
  }

  async function loadTransactions(page = txPage.value) {
    txPage.value = page;
    txLoading.value = true;
    try {
      const data = await adminCreditsApi.getTransactions(buildParams(false));
      txList.value = data?.content || [];
      txTotalElements.value = data?.totalElements ?? 0;
      txTotalPages.value = data?.totalPages ?? 0;
      txPage.value = data?.number ?? txPage.value;
      if (data?.summary) {
        txTotalSummary.value = data.summary;
      }
    } catch (error) {
      if (showSystemMsg) showSystemMsg('加载流水失败: ' + error.message, 'error');
      txList.value = [];
    } finally {
      txLoading.value = false;
    }
  }

  function searchTransactions() {
    txPage.value = 0;
    loadTransactions(0);
  }

  function resetFilters() {
    filters.userId = '';
    filters.type = '';
    filters.direction = '';
    filters.start = '';
    filters.end = '';
    filters.min = '';
    filters.max = '';
    filters.relatedId = '';
    txPage.value = 0;
    loadTransactions(0);
  }

  function goToTxPage(page) {
    if (page < 0 || page >= txTotalPages.value) return;
    loadTransactions(page);
  }

  async function exportTransactions(format = 'csv') {
    txExporting.value = true;
    try {
      await adminCreditsApi.getTransactions(buildParams(format === 'csv' ? 'csv' : true));
      if (showSystemMsg) {
        showSystemMsg(format === 'csv' ? '流水已导出为 CSV' : '流水已导出下载');
      }
    } catch (error) {
      if (showSystemMsg) showSystemMsg('导出失败: ' + error.message, 'error');
    } finally {
      txExporting.value = false;
    }
  }

  // ==================== 模拟现金账（与积分流水同一时间窗口） ====================
  // 现金不是真银行流水：订阅按套餐价、退款按比例、AI 消耗按积分成本折算，管理员也可手工记一笔。
  const cashDays = ref(30);
  const cashSummary = ref(null);
  const cashLoading = ref(false);
  const cashPreset = ref('30');           // today | 7 | 30 | 90 | custom

  /** 时间窗口（与筛选里的 start/end 共用：自定义时以筛选为准） */
  function cashWindow() {
    const to = new Date();
    const fmt = (d) => d.toISOString().slice(0, 10);
    if (cashPreset.value === 'today') return { start: fmt(to), end: fmt(to), days: 1 };
    const days = Number(cashPreset.value) || cashDays.value || 30;
    const from = new Date(to.getTime() - (days - 1) * 86400000);
    return { start: fmt(from), end: fmt(to), days };
  }

  async function loadCashSummary() {
    cashLoading.value = true;
    try {
      const w = cashWindow();
      cashDays.value = w.days;
      const data = await adminCreditsApi.getCashSummary(w);
      cashSummary.value = data || null;
    } catch (error) {
      if (showSystemMsg) showSystemMsg('加载现金汇总失败: ' + error.message, 'error');
      cashSummary.value = null;
    } finally {
      cashLoading.value = false;
    }
  }

  function setCashPreset(preset) {
    cashPreset.value = preset;
    if (preset === 'custom') {
      // 自定义：直接用筛选里的日期，联动积分流水
      const params = {};
      if (filters.start) params.start = filters.start;
      if (filters.end) params.end = filters.end;
      if (!params.start && !params.end) return;
      cashLoading.value = true;
      adminCreditsApi.getCashSummary(params)
        .then((data) => { cashSummary.value = data || null; })
        .catch((error) => { if (showSystemMsg) showSystemMsg('加载现金汇总失败: ' + error.message, 'error'); })
        .finally(() => { cashLoading.value = false; });
      return;
    }
    loadCashSummary();
  }

  /** 手工记一笔现金收支（模拟）；成功后刷新汇总 */
  const cashEntrySaving = ref(false);
  async function createCashEntry(payload) {
    cashEntrySaving.value = true;
    try {
      const res = await adminCreditsApi.createCashEntry(payload);
      if (showSystemMsg) showSystemMsg(`已记账：${payload.direction === 'IN' ? '收入' : '支出'} ${payload.amount} 元`);
      await loadCashSummary();
      await loadTransactions(0);
      return res;
    } catch (error) {
      if (showSystemMsg) showSystemMsg('记账失败: ' + error.message, 'error');
      throw error;
    } finally {
      cashEntrySaving.value = false;
    }
  }

  return {
    txList, txLoading, txPage, txSize, txTotalElements, txTotalPages, txExporting,
    txTotalSummary, filters, summary,
    loadTransactions, searchTransactions, resetFilters, goToTxPage, exportTransactions,
    cashDays, cashSummary, cashLoading, cashPreset, loadCashSummary, setCashPreset,
    createCashEntry, cashEntrySaving,
  };
}
