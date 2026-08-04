import { ref, reactive, computed } from 'vue';
import { adminCreditsApi } from '../services/api';

// 积分流水 Tab：多条件搜索 + 分页 + 顶栏汇总 + 导出
// 后端 CreditTransactionType: NEW_USER_BONUS, SIGN_IN, AI_CONSUMPTION, AI_CHAT,
// AI_ANALYZE, SUBSCRIPTION_PURCHASE, REFUND, ADMIN_GRANT, ADMIN_DEDUCT, EXPIRE
// 后端 CreditDirection: IN / OUT
export const TX_TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'NEW_USER_BONUS', label: '新人奖励' },
  { value: 'SIGN_IN', label: '签到' },
  { value: 'AI_CONSUMPTION', label: 'AI 消费' },
  { value: 'AI_CHAT', label: 'AI 对话' },
  { value: 'AI_ANALYZE', label: 'AI 分析' },
  { value: 'SUBSCRIPTION_PURCHASE', label: '订阅购买' },
  { value: 'REFUND', label: '退款' },
  { value: 'ADMIN_GRANT', label: '管理员发放' },
  { value: 'ADMIN_DEDUCT', label: '管理员扣减' },
  { value: 'EXPIRE', label: '过期' },
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

  async function exportTransactions() {
    txExporting.value = true;
    try {
      await adminCreditsApi.getTransactions(buildParams(true));
      if (showSystemMsg) showSystemMsg('流水已导出下载');
    } catch (error) {
      if (showSystemMsg) showSystemMsg('导出失败: ' + error.message, 'error');
    } finally {
      txExporting.value = false;
    }
  }

  return {
    txList, txLoading, txPage, txSize, txTotalElements, txTotalPages, txExporting,
    filters, summary,
    loadTransactions, searchTransactions, resetFilters, goToTxPage, exportTransactions,
  };
}
