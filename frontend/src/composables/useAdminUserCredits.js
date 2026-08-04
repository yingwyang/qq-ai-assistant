import { ref, reactive } from 'vue';
import { adminCreditsApi } from '../services/api';

// 用户积分 Tab：搜索 + 分页 + 调整积分弹窗
// 列：用户名、昵称、角色、余额、订阅等级(tier)、订阅到期、累计收入、累计支出、消费封禁状态
export function useAdminUserCredits({ showSystemMsg } = {}) {
  const ucList = ref([]);
  const ucLoading = ref(false);
  const ucKeyword = ref('');
  const ucPage = ref(0);
  const ucSize = ref(20);
  const ucTotalElements = ref(0);
  const ucTotalPages = ref(0);
  let ucSearchTimer = null;

  // 调整积分弹窗
  const adjustModal = reactive({
    visible: false,
    userId: null,
    username: '',
    nickname: '',
    balance: 0,
    amount: 0,
    reason: '',
    submitting: false,
  });

  async function loadUserCredits(page = ucPage.value) {
    ucLoading.value = true;
    ucPage.value = page;
    try {
      const params = {
        page: ucPage.value,
        size: ucSize.value,
      };
      if (ucKeyword.value.trim()) params.keyword = ucKeyword.value.trim();
      const data = await adminCreditsApi.getUserCredits(params);
      ucList.value = data?.content || [];
      ucTotalElements.value = data?.totalElements ?? 0;
      ucTotalPages.value = data?.totalPages ?? 0;
      ucPage.value = data?.number ?? ucPage.value;
    } catch (error) {
      if (showSystemMsg) showSystemMsg('加载用户积分失败: ' + error.message, 'error');
      ucList.value = [];
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

  function goToUcPage(page) {
    if (page < 0 || page >= ucTotalPages.value) return;
    loadUserCredits(page);
  }

  function openAdjustModal(row) {
    adjustModal.visible = true;
    adjustModal.userId = row.userId;
    adjustModal.username = row.username;
    adjustModal.nickname = row.nickname;
    adjustModal.balance = row.balance;
    adjustModal.amount = 0;
    adjustModal.reason = '';
    adjustModal.submitting = false;
  }

  function closeAdjustModal() {
    adjustModal.visible = false;
  }

  async function submitAdjust() {
    if (adjustModal.userId == null) return;
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
    try {
      const res = await adminCreditsApi.adjust({
        userId: adjustModal.userId,
        amount,
        reason: adjustModal.reason.trim(),
      });
      if (showSystemMsg) {
        const newBalance = res?.newBalance ?? adjustModal.balance + amount;
        showSystemMsg(`已调整 ${adjustModal.username} 积分 ${amount > 0 ? '+' : ''}${amount}，新余额 ${newBalance}`);
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
    ucList, ucLoading, ucKeyword, ucPage, ucSize,
    ucTotalElements, ucTotalPages,
    adjustModal,
    loadUserCredits, onUcSearchInput, goToUcPage,
    openAdjustModal, closeAdjustModal, submitAdjust,
    cleanup,
  };
}
