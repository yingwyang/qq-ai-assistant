import { ref, reactive, computed } from 'vue';
import { adminOrdersApi } from '../services/api';

// 订单管理 Tab：筛选 + 分页 + 补单 + 取消/退款 + 详情抽屉 + 导出
// 后端 OrderStatus: PENDING, PAID, PENDING_REFUND, DISPUTED, REFUNDED, CANCELLED, EXPIRED
// 后端 SubscriptionTier: FREE, LITE, PRO, PROPLUS, ULTRA, MEGA, SMALL_MONTH_CARD, LARGE_MONTH_CARD
// manualCreate planCode 接受: LITE / PRO / PROPLUS / ULTRA / MEGA / SMALL_MONTH_CARD / LARGE_MONTH_CARD
export const ORDER_STATUS_OPTIONS = [
  { value: 'PENDING', label: '待支付' },
  { value: 'PAID', label: '已支付' },
  { value: 'PENDING_REFUND', label: '退款审批中' },
  { value: 'DISPUTED', label: '纠纷中' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'EXPIRED', label: '已过期' },
];

export const ORDER_PLAN_OPTIONS = [
  { value: 'LITE', label: '直购积分·600' },
  { value: 'PRO', label: '直购积分·3500' },
  { value: 'PROPLUS', label: '直购积分·16000' },
  { value: 'ULTRA', label: '直购积分·45000' },
  { value: 'MEGA', label: '直购积分·100000' },
  { value: 'SMALL_MONTH_CARD', label: '小月卡' },
  { value: 'LARGE_MONTH_CARD', label: '大月卡' },
];

export function orderStatusText(s) {
  const found = ORDER_STATUS_OPTIONS.find((o) => o.value === s);
  return found ? found.label : (s || '-');
}

export function planTierText(t) {
  const found = ORDER_PLAN_OPTIONS.find((o) => o.value === t);
  if (found) return found.label;
  if (t === 'FREE') return '免费版';
  return t || '-';
}

export function useAdminOrders({ showSystemMsg } = {}) {
  const orders = ref([]);
  const ordersLoading = ref(false);
  const ordersPage = ref(0);
  const ordersSize = ref(20);
  const ordersTotalElements = ref(0);
  const ordersTotalPages = ref(0);
  const ordersExporting = ref(false);

  // 筛选条件
  const filters = reactive({
    orderNo: '',
    keyword: '',
    statusSelected: [], // 多选数组
    start: '',
    end: '',
    minPrice: '',
    maxPrice: '',
  });

  // 补单弹窗
  const manualModal = reactive({
    visible: false,
    userId: '',
    planCode: 'LITE',
    priceCents: '', // 可选，分
    pointsGranted: '', // 可选
    durationDays: '', // 可选
    orderNo: '', // 可选
    remark: '',
    submitting: false,
  });

  // 退款弹窗
  const refundModal = reactive({
    visible: false,
    orderNo: '',
    reason: '',
    refundRatio: 1.0,
    submitting: false,
  });

  // 作废弹窗
  const cancelModal = reactive({
    visible: false,
    orderNo: '',
    reason: '',
    submitting: false,
  });

  // 纠纷处理弹窗
  const disputeModal = reactive({
    visible: false,
    orderNo: '',
    reason: '',
    agree: true, // true=同意退款, false=驳回
    submitting: false,
  });

  // 详情抽屉
  const detailDrawer = reactive({
    visible: false,
    loading: false,
    orderNo: '',
    data: null,
    relatedTransactions: [],
  });

  const statusMultiText = computed(() => {
    if (!filters.statusSelected || filters.statusSelected.length === 0) return '';
    return filters.statusSelected.join(',');
  });

  function buildListParams() {
    const p = {
      page: ordersPage.value,
      size: ordersSize.value,
    };
    if (filters.orderNo.trim()) p.orderNo = filters.orderNo.trim();
    if (filters.keyword.trim()) p.keyword = filters.keyword.trim();
    if (statusMultiText.value) p.status = statusMultiText.value;
    if (filters.start) p.start = filters.start;
    if (filters.end) p.end = filters.end;
    if (filters.minPrice !== '' && filters.minPrice !== null) p.minPrice = filters.minPrice;
    if (filters.maxPrice !== '' && filters.maxPrice !== null) p.maxPrice = filters.maxPrice;
    return p;
  }

  function buildExportParams() {
    const p = {};
    if (filters.orderNo.trim()) p.orderNo = filters.orderNo.trim();
    if (filters.keyword.trim()) p.keyword = filters.keyword.trim();
    if (statusMultiText.value) p.status = statusMultiText.value;
    if (filters.start) p.start = filters.start;
    if (filters.end) p.end = filters.end;
    if (filters.minPrice !== '' && filters.minPrice !== null) p.minPrice = filters.minPrice;
    if (filters.maxPrice !== '' && filters.maxPrice !== null) p.maxPrice = filters.maxPrice;
    return p;
  }

  async function loadOrders(page = ordersPage.value) {
    ordersPage.value = page;
    ordersLoading.value = true;
    try {
      const data = await adminOrdersApi.list(buildListParams());
      orders.value = data?.content || [];
      ordersTotalElements.value = data?.totalElements ?? 0;
      ordersTotalPages.value = data?.totalPages ?? 0;
      ordersPage.value = data?.number ?? ordersPage.value;
    } catch (error) {
      if (showSystemMsg) showSystemMsg('加载订单失败: ' + error.message, 'error');
      orders.value = [];
    } finally {
      ordersLoading.value = false;
    }
  }

  function searchOrders() {
    ordersPage.value = 0;
    loadOrders(0);
  }

  function resetOrderFilters() {
    filters.orderNo = '';
    filters.keyword = '';
    filters.statusSelected = [];
    filters.start = '';
    filters.end = '';
    filters.minPrice = '';
    filters.maxPrice = '';
    ordersPage.value = 0;
    loadOrders(0);
  }

  function goToOrdersPage(page) {
    if (page < 0 || page >= ordersTotalPages.value) return;
    loadOrders(page);
  }

  // 补单
  function openManualModal() {
    manualModal.visible = true;
    manualModal.userId = '';
    manualModal.planCode = 'LITE';
    manualModal.priceCents = '';
    manualModal.pointsGranted = '';
    manualModal.durationDays = '';
    manualModal.orderNo = '';
    manualModal.remark = '';
    manualModal.submitting = false;
  }

  function closeManualModal() {
    manualModal.visible = false;
  }

  async function submitManualCreate() {
    const userId = Number(manualModal.userId);
    if (!Number.isFinite(userId) || userId <= 0) {
      if (showSystemMsg) showSystemMsg('请输入有效的用户 ID', 'error');
      return;
    }
    if (!manualModal.planCode) {
      if (showSystemMsg) showSystemMsg('请选择套餐', 'error');
      return;
    }
    manualModal.submitting = true;
    try {
      const payload = {
        userId,
        planCode: manualModal.planCode,
        remark: manualModal.remark || undefined,
      };
      if (manualModal.orderNo.trim()) payload.orderNo = manualModal.orderNo.trim();
      if (manualModal.priceCents !== '' && manualModal.priceCents !== null) {
        payload.priceCents = Number(manualModal.priceCents);
      }
      if (manualModal.pointsGranted !== '' && manualModal.pointsGranted !== null) {
        payload.pointsGranted = Number(manualModal.pointsGranted);
      }
      if (manualModal.durationDays !== '' && manualModal.durationDays !== null) {
        payload.durationDays = Number(manualModal.durationDays);
      }
      const res = await adminOrdersApi.manualCreate(payload);
      if (showSystemMsg) showSystemMsg(`补单成功：${res?.orderNo || ''} 已直接支付`);
      closeManualModal();
      await loadOrders(0);
    } catch (error) {
      if (showSystemMsg) showSystemMsg('补单失败: ' + error.message, 'error');
    } finally {
      manualModal.submitting = false;
    }
  }

  // 作废（PENDING only）
  function openCancelModal(orderNo) {
    cancelModal.visible = true;
    cancelModal.orderNo = orderNo;
    cancelModal.reason = '';
    cancelModal.submitting = false;
  }

  function closeCancelModal() {
    cancelModal.visible = false;
  }
  // 纠纷处理
  function openDisputeModal(orderNo) {
    disputeModal.visible = true;
    disputeModal.orderNo = orderNo;
    disputeModal.reason = '';
    disputeModal.agree = true;
    disputeModal.submitting = false;
  }

  function closeDisputeModal() {
    disputeModal.visible = false;
  }

  async function submitResolveDispute() {
    if (!disputeModal.orderNo) return;
    if (!disputeModal.reason.trim()) {
      if (showSystemMsg) showSystemMsg('请填写处理说明', 'error');
      return;
    }
    disputeModal.submitting = true;
    try {
      const res = await adminOrdersApi.resolveDispute(disputeModal.orderNo, {
        agree: disputeModal.agree,
        reason: disputeModal.reason.trim(),
      });
      if (showSystemMsg) {
        // 原来这里引用了不存在的变量 action → 提交成功后抛 ReferenceError，
        // 被下面 catch 捕获并提示"纠纷处理失败"，管理员会误以为失败而重复提交。
        const outcome = disputeModal.agree ? '同意退款' : '驳回申诉';
        const rp = res?.refundPoints != null ? `，扣减 ${res.refundPoints} 积分` : '';
        showSystemMsg(`纠纷处理完成：订单 ${disputeModal.orderNo} ${outcome}${rp}`);
      }
      closeDisputeModal();
      await loadOrders(ordersPage.value);
      if (detailDrawer.visible && detailDrawer.orderNo === disputeModal.orderNo) {
        openOrderDetail(disputeModal.orderNo);
      }
    } catch (error) {
      if (showSystemMsg) showSystemMsg('纠纷处理失败: ' + error.message, 'error');
    } finally {
      disputeModal.submitting = false;
    }
  }

  async function submitCancel() {
    if (!cancelModal.orderNo) return;
    cancelModal.submitting = true;
    try {
      await adminOrdersApi.cancel(cancelModal.orderNo, cancelModal.reason.trim());
      if (showSystemMsg) showSystemMsg(`订单 ${cancelModal.orderNo} 已作废`);
      closeCancelModal();
      await loadOrders(ordersPage.value);
      if (detailDrawer.visible && detailDrawer.orderNo === cancelModal.orderNo) {
        detailDrawer.data = { ...detailDrawer.data, status: 'CANCELLED' };
      }
    } catch (error) {
      if (showSystemMsg) showSystemMsg('作废失败: ' + error.message, 'error');
    } finally {
      cancelModal.submitting = false;
    }
  }

  // 退款（PAID only）
  function openRefundModal(orderNo) {
    refundModal.visible = true;
    refundModal.orderNo = orderNo;
    refundModal.reason = '';
    refundModal.refundRatio = 1.0;
    refundModal.submitting = false;
  }

  function closeRefundModal() {
    refundModal.visible = false;
  }

  async function submitRefund() {
    if (!refundModal.orderNo) return;
    const ratio = Number(refundModal.refundRatio);
    if (!Number.isFinite(ratio) || ratio <= 0 || ratio > 1) {
      if (showSystemMsg) showSystemMsg('退款比例必须在 0~1 之间', 'error');
      return;
    }
    if (!refundModal.reason.trim()) {
      if (showSystemMsg) showSystemMsg('请填写退款原因', 'error');
      return;
    }
    refundModal.submitting = true;
    try {
      const res = await adminOrdersApi.refund(refundModal.orderNo, {
        reason: refundModal.reason.trim(),
        refundRatio: ratio,
      });
      if (showSystemMsg) {
        const rp = res?.refundPoints != null ? `，扣减 ${res.refundPoints} 积分` : '';
        showSystemMsg(`订单 ${refundModal.orderNo} 已退款${rp}`);
      }
      closeRefundModal();
      await loadOrders(ordersPage.value);
      if (detailDrawer.visible && detailDrawer.orderNo === refundModal.orderNo) {
        // 刷新详情
        openOrderDetail(refundModal.orderNo);
      }
    } catch (error) {
      if (showSystemMsg) showSystemMsg('退款失败: ' + error.message, 'error');
    } finally {
      refundModal.submitting = false;
    }
  }

  // 导出单条（复用 list 接口按 orderNo 精确筛选后下载）
  async function exportSingleOrder(orderNo) {
    try {
      await adminOrdersApi.export({ orderNo });
      if (showSystemMsg) showSystemMsg(`订单 ${orderNo} 已导出`);
    } catch (error) {
      if (showSystemMsg) showSystemMsg('导出失败: ' + error.message, 'error');
    }
  }

  // 导出当前筛选
  async function exportOrders() {
    ordersExporting.value = true;
    try {
      await adminOrdersApi.export(buildExportParams());
      if (showSystemMsg) showSystemMsg('订单已导出下载');
    } catch (error) {
      if (showSystemMsg) showSystemMsg('导出失败: ' + error.message, 'error');
    } finally {
      ordersExporting.value = false;
    }
  }

  // 详情抽屉
  async function openOrderDetail(orderNo) {
    detailDrawer.visible = true;
    detailDrawer.loading = true;
    detailDrawer.orderNo = orderNo;
    detailDrawer.data = null;
    detailDrawer.relatedTransactions = [];
    try {
      const data = await adminOrdersApi.detail(orderNo);
      detailDrawer.data = data;
      detailDrawer.relatedTransactions = Array.isArray(data?.relatedTransactions)
        ? data.relatedTransactions
        : [];
    } catch (error) {
      if (showSystemMsg) showSystemMsg('加载详情失败: ' + error.message, 'error');
    } finally {
      detailDrawer.loading = false;
    }
  }

  function closeOrderDetail() {
    detailDrawer.visible = false;
  }

  // 时间轴事件（复用用户端风格）
  const timelineEvents = computed(() => {
    const d = detailDrawer.data;
    if (!d) return [];
    const evts = [];
    evts.push({ title: '订单创建', operator: '用户', time: d.createdAt, done: true });
    if (d.paidAt) evts.push({ title: '订单支付完成', operator: '系统', time: d.paidAt, done: true });
    if (d.status === 'CANCELLED') evts.push({ title: '订单已作废', operator: '管理员', time: d.updatedAt, done: true });
    if (d.status === 'REFUNDED') evts.push({ title: '退款处理完成', operator: '管理员', time: d.refundedAt || d.updatedAt, done: true });
    if (d.status === 'EXPIRED') evts.push({ title: '订单已过期', operator: '系统', time: d.expiresAt || d.updatedAt, done: true });
    return evts;
  });

  // 确认收款（PENDING → PAID）
  async function submitApprovePayment(orderNo) {
    if (!orderNo) return;
    try {
      const res = await adminOrdersApi.approvePayment(orderNo);
      if (showSystemMsg) showSystemMsg(`订单 ${orderNo} 已确认收款，状态更新为已支付`);
      await loadOrders(ordersPage.value);
    } catch (error) {
      if (showSystemMsg) showSystemMsg('确认收款失败: ' + error.message, 'error');
    }
  }

  return {
    orders, ordersLoading, ordersPage, ordersSize, ordersTotalElements, ordersTotalPages,
    ordersExporting, filters, statusMultiText,
    manualModal, refundModal, cancelModal, disputeModal, detailDrawer, timelineEvents,
    loadOrders, searchOrders, resetOrderFilters, goToOrdersPage,
    openManualModal, closeManualModal, submitManualCreate,
    openCancelModal, closeCancelModal, submitCancel,
    openDisputeModal, closeDisputeModal, submitResolveDispute,
    openRefundModal, closeRefundModal, submitRefund,
    exportSingleOrder, exportOrders,
    openOrderDetail, closeOrderDetail,
    submitApprovePayment,
  };
}


