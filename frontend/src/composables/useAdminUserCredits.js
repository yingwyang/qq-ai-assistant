import { computed, ref, reactive } from 'vue';
import { adminCreditsApi } from '../services/api';
import { showConfirm } from '../components/ConfirmDialog.vue';

// 用户积分 Tab：搜索/层级/余额区间筛选 + 排序 + 分页 + 调账（单个/批量）+ 导出 + 跳资金流水
// 列：用户名、昵称、角色、余额、订阅等级(tier)、订阅到期、累计收入、累计支出、消费封禁状态
export function useAdminUserCredits({ showSystemMsg } = {}) {
  const ucList = ref([]);
  const ucLoading = ref(false);
  const ucError = ref('');
  const ucKeyword = ref('');
  const ucTier = ref('');
  const ucMin = ref('');
  const ucMax = ref('');
  const ucSort = ref('balance');
  const ucOrder = ref('desc');
  const ucPage = ref(0);
  const ucSize = ref(20);
  const ucTotalElements = ref(0);
  const ucTotalPages = ref(0);
  let ucSearchTimer = null;

  const ucTierOptions = [
    { value: '', label: '全部层级' },
    { value: 'FREE', label: '免费版' },
    { value: 'SMALL_MONTH_CARD', label: '小月卡' },
    { value: 'LARGE_MONTH_CARD', label: '大月卡' },
    { value: 'LITE', label: '直购积分·600' },
    { value: 'PRO', label: '直购积分·3500' },
    { value: 'PROPLUS', label: '直购积分·16000' },
    { value: 'ULTRA', label: '直购积分·45000' },
    { value: 'MEGA', label: '直购积分·100000' },
  ];

  const ucSortOptions = [
    { value: 'balance,desc', label: '余额（高→低）' },
    { value: 'balance,asc', label: '余额（低→高）' },
    { value: 'totalSpent,desc', label: '累计消耗（高→低）' },
    { value: 'totalEarned,desc', label: '累计获得（高→低）' },
    { value: 'updatedAt,desc', label: '最近变动（新→旧）' },
    { value: 'userId,asc', label: '用户 ID（小→大）' },
  ];

  const selectedUserIds = ref(new Set());
  const selectedUserCount = computed(() => selectedUserIds.value.size);

  const ucFilterActive = computed(() =>
    !!ucKeyword.value.trim() || !!ucTier.value || ucMin.value !== '' || ucMax.value !== '');

  const buildParams = () => {
    const params = {
      page: ucPage.value,
      size: ucSize.value,
      sort: ucSort.value,
      order: ucOrder.value,
    };
    if (ucKeyword.value.trim()) params.keyword = ucKeyword.value.trim();
    if (ucTier.value) params.tier = ucTier.value;
    if (ucMin.value !== '') params.min = Number(ucMin.value);
    if (ucMax.value !== '') params.max = Number(ucMax.value);
    return params;
  };

  // 调整积分弹窗（单个 / 批量共用）
  const adjustModal = reactive({
    visible: false,
    userId: null,
    username: '',
    nickname: '',
    balance: 0,
    amount: 0,
    reason: '',
    submitting: false,
    /** 批量模式：目标用户行列表 */
    batchRows: null,
  });

  const isBatchAdjust = computed(() => Array.isArray(adjustModal.batchRows) && adjustModal.batchRows.length > 0);

  async function loadUserCredits(page = ucPage.value) {
    ucLoading.value = true;
    ucError.value = '';
    ucPage.value = page;
    try {
      const data = await adminCreditsApi.getUserCredits(buildParams());
      ucList.value = data?.content || [];
      ucTotalElements.value = data?.totalElements ?? 0;
      ucTotalPages.value = data?.totalPages ?? 0;
      ucPage.value = data?.number ?? ucPage.value;
    } catch (error) {
      ucError.value = error.message || '加载用户积分失败';
      if (showSystemMsg) showSystemMsg('加载用户积分失败: ' + error.message, 'error');
      ucList.value = [];
      ucTotalElements.value = 0;
      ucTotalPages.value = 0;
    } finally {
      ucLoading.value = false;
    }
  }

  function onUcSearchInput() {
    if (ucSearchTimer) clearTimeout(ucSearchTimer);
    ucSearchTimer = setTimeout(() => {
      ucPage.value = 0;
      loadUserCredits(0);
    }, 300);
  }

  function setUcTier(tier) {
    ucTier.value = tier || '';
    ucPage.value = 0;
    loadUserCredits(0);
  }

  function setUcRange() {
    ucPage.value = 0;
    loadUserCredits(0);
  }

  function setUcSort(value) {
    const [field, order] = String(value || 'balance,desc').split(',');
    ucSort.value = field || 'balance';
    ucOrder.value = order || 'desc';
    ucPage.value = 0;
    loadUserCredits(0);
  }

  function setUcPageSize(size) {
    ucSize.value = Number(size) || 20;
    ucPage.value = 0;
    loadUserCredits(0);
  }

  function resetUcFilters() {
    ucKeyword.value = '';
    ucTier.value = '';
    ucMin.value = '';
    ucMax.value = '';
    ucSort.value = 'balance';
    ucOrder.value = 'desc';
    ucPage.value = 0;
    loadUserCredits(0);
  }

  function goToUcPage(page) {
    if (page < 0 || page >= ucTotalPages.value) return;
    loadUserCredits(page);
  }

  const ucExportUrl = computed(() => adminCreditsApi.exportUserCreditsUrl({
    keyword: ucKeyword.value.trim() || undefined,
    tier: ucTier.value || undefined,
    min: ucMin.value !== '' ? ucMin.value : undefined,
    max: ucMax.value !== '' ? ucMax.value : undefined,
    sort: ucSort.value,
    order: ucOrder.value,
  }));

  // ===== 选择与批量调账 =====
  const isUserSelected = (id) => selectedUserIds.value.has(id);

  const toggleUserSelection = (id) => {
    const next = new Set(selectedUserIds.value);
    if (next.has(id)) next.delete(id); else next.add(id);
    selectedUserIds.value = next;
  };

  const toggleSelectAllUsers = () => {
    if (selectedUserIds.value.size === ucList.value.length && ucList.value.length > 0) {
      selectedUserIds.value = new Set();
      return;
    }
    selectedUserIds.value = new Set(ucList.value.map((u) => u.userId));
  };

  const clearUserSelection = () => {
    selectedUserIds.value = new Set();
  };

  function openAdjustModal(row) {
    adjustModal.visible = true;
    adjustModal.userId = row.userId;
    adjustModal.username = row.username;
    adjustModal.nickname = row.nickname;
    adjustModal.balance = row.balance;
    adjustModal.amount = 0;
    adjustModal.reason = '';
    adjustModal.submitting = false;
    adjustModal.batchRows = null;
  }

  /** 批量调账：对选中用户执行同一笔增减 */
  async function openBatchAdjustModal() {
    const rows = ucList.value.filter((row) => selectedUserIds.value.has(row.userId));
    if (rows.length === 0) {
      if (showSystemMsg) showSystemMsg('请先选择用户', 'error');
      return;
    }
    const ok = await showConfirm({
      title: '批量调账',
      message: `将对选中的 ${rows.length} 个用户执行同一笔积分增减，是否继续？`,
      type: 'warning',
      confirmText: '开始调账',
    });
    if (!ok) return;
    adjustModal.visible = true;
    adjustModal.userId = null;
    adjustModal.username = `批量（${rows.length} 人）`;
    adjustModal.nickname = '';
    adjustModal.balance = 0;
    adjustModal.amount = 0;
    adjustModal.reason = '';
    adjustModal.submitting = false;
    adjustModal.batchRows = rows.map((r) => ({ userId: r.userId, username: r.username }));
  }

  function closeAdjustModal() {
    adjustModal.visible = false;
    adjustModal.batchRows = null;
  }

  async function submitAdjust() {
    const amount = Number(adjustModal.amount);
    if (!Number.isFinite(amount) || amount === 0) {
      if (showSystemMsg) showSystemMsg('调整金额必须为非零数字（正数增加、负数扣减）', 'error');
      return;
    }
    if (!adjustModal.reason || !adjustModal.reason.trim()) {
      if (showSystemMsg) showSystemMsg('请填写调账原因', 'error');
      return;
    }
    adjustModal.submitting = true;
    const reason = adjustModal.reason.trim();
    try {
      if (isBatchAdjust.value) {
        // 批量：逐条提交并汇总，部分失败不静默跳过
        let succeeded = 0;
        const failed = [];
        for (const row of adjustModal.batchRows) {
          try {
            await adminCreditsApi.adjust({ userId: row.userId, amount, reason });
            succeeded++;
          } catch (e) {
            failed.push(`${row.username}: ${e.message}`);
          }
        }
        if (showSystemMsg) {
          if (failed.length === 0) {
            showSystemMsg(`已为 ${succeeded} 个用户调整积分 ${amount > 0 ? '+' : ''}${amount}`);
          } else {
            showSystemMsg(`成功 ${succeeded} 个，失败 ${failed.length} 个（${failed.slice(0, 3).join('；')}）`, 'error');
          }
        }
        clearUserSelection();
      } else {
        if (adjustModal.userId == null) return;
        const res = await adminCreditsApi.adjust({ userId: adjustModal.userId, amount, reason });
        if (showSystemMsg) {
          const newBalance = res?.newBalance ?? adjustModal.balance + amount;
          showSystemMsg(`已调整 ${adjustModal.username} 积分 ${amount > 0 ? '+' : ''}${amount}，新余额 ${newBalance}`);
        }
      }
      closeAdjustModal();
      await loadUserCredits(ucPage.value);
    } catch (error) {
      if (showSystemMsg) showSystemMsg('调整失败: ' + error.message, 'error');
    } finally {
      adjustModal.submitting = false;
    }
  }

  function cleanup() {
    if (ucSearchTimer) clearTimeout(ucSearchTimer);
  }

  return {
    ucList, ucLoading, ucError, ucKeyword, ucTier, ucMin, ucMax, ucSort, ucOrder, ucPage, ucSize,
    ucTotalElements, ucTotalPages,
    ucTierOptions, ucSortOptions, ucFilterActive, ucExportUrl,
    setUcTier, setUcRange, setUcSort, setUcPageSize, resetUcFilters,
    selectedUserCount, isUserSelected, toggleUserSelection, toggleSelectAllUsers, clearUserSelection,
    openBatchAdjustModal, isBatchAdjust,
    adjustModal,
    loadUserCredits, onUcSearchInput, goToUcPage,
    openAdjustModal, closeAdjustModal, submitAdjust,
    cleanup,
  };
}
