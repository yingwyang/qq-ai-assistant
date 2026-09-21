<template>
  <div class="tab-panel admin-refund-approve">
    <AdminPageHeader title="退款审批" subtitle="待审批的退款申请（同意 / 驳回）">
      <template #meta>
        <span v-if="selectedCount > 0" class="dirty-badge">已选 {{ selectedCount }} 笔</span>
      </template>
      <button class="btn-action promote" @click="loadOrders(ordersPage)">刷新</button>
    </AdminPageHeader>

    <div class="data-toolbar">
      <span class="data-toolbar-info">共 {{ ordersTotalElements }} 笔待审批退款（同意后按比例扣回积分与月卡天数）</span>
      <div class="data-toolbar-actions">
        <button class="btn-action enable" :disabled="batchRunning || selectedCount === 0" @click="batchApprove">批量同意</button>
        <button class="btn-action disable" :disabled="batchRunning || selectedCount === 0" @click="batchReject">批量驳回</button>
        <button v-if="selectedCount > 0" class="btn-action" :disabled="batchRunning" @click="clearSelection">取消选择</button>
      </div>
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
            <th class="col-check">
              <input
                type="checkbox"
                :checked="orders.length > 0 && selectedCount === orders.length"
                @change="toggleSelectAll"
              />
            </th>
            <th>订单号</th>
            <th>套餐</th>
            <th>金额</th>
            <th>用户 ID</th>
            <th>退款原因</th>
            <th>申请时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="ordersLoading"><td colspan="9"><StatePanel state="loading" compact /></td></tr>
          <tr v-else-if="orders.length === 0"><td colspan="9"><StatePanel state="empty" title="暂无待审批的退款申请" compact /></td></tr>
          <tr v-for="o in orders" :key="o.orderNo">
            <td class="col-check">
              <input type="checkbox" :checked="selected.has(o.orderNo)" @change="toggleSelect(o.orderNo)" />
            </td>
            <td class="order-no-cell" @click="openOrderDetail(o.orderNo)" :title="o.orderNo">{{ o.orderNo }}</td>
            <td>{{ planTierText(o.planTier) }}</td>
            <td class="amount-cell">¥{{ Number(o.price || 0).toFixed(2) }}</td>
            <td>{{ o.userId }}</td>
            <td>{{ o.refundReason || '-' }}</td>
            <td>{{ formatDate(o.refundRequestedAt || o.updatedAt) }}</td>
            <td class="action-cell">
              <button class="btn-link btn-warn" @click="openApproveRefundModal(o.orderNo)">同意退款</button>
              <button class="btn-link btn-danger" @click="openRejectRefundModal(o.orderNo)">驳回</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="user-pagination">
      <span class="pagination-info">共 {{ ordersTotalElements }} 条，第 {{ ordersPage + 1 }} / {{ Math.max(1, ordersTotalPages) }} 页</span>
      <div class="pagination-btns">
        <button class="btn-page" :disabled="ordersPage === 0" @click="goToOrdersPage(0)">首页</button>
        <button class="btn-page" :disabled="ordersPage === 0" @click="goToOrdersPage(ordersPage - 1)">上一页</button>
        <button class="btn-page" :disabled="ordersPage >= ordersTotalPages - 1" @click="goToOrdersPage(ordersPage + 1)">下一页</button>
        <button class="btn-page" :disabled="ordersPage >= ordersTotalPages - 1" @click="goToOrdersPage(ordersTotalPages - 1)">末页</button>
      </div>
    </div>
  </div>
</template>

<script>
import { computed, inject, onMounted, ref, watch } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { adminOrdersApi } from '../../services/api';
import { showConfirm } from '../../components/ConfirmDialog.vue';
import StatePanel from '../../components/common/StatePanel.vue';

export default {
  name: 'AdminRefundApprove',
  components: { Icon, AdminPageHeader, StatePanel },
  setup() {
    const adminOrders = inject('adminOrders');
    const planTierText = inject('adminPlanTierText');
    const formatDate = inject('adminFormatDate');
    const openApproveRefundModal = inject('adminOpenApproveRefundModal');
    const openRejectRefundModal = inject('adminOpenRejectRefundModal');
    const showSystemMsg = inject('adminShowMsg', null);
    const loadPendingCount = inject('adminLoadPendingCount', null);

    // 批量审批：勾选当前页待审批订单，逐条调用同一套接口并汇总结果
    const selected = ref(new Set());
    const batchRunning = ref(false);
    const selectedCount = computed(() => selected.value.size);

    const toggleSelect = (orderNo) => {
      const next = new Set(selected.value);
      if (next.has(orderNo)) next.delete(orderNo); else next.add(orderNo);
      selected.value = next;
    };

    const toggleSelectAll = () => {
      if (selected.value.size === adminOrders.orders.value.length && adminOrders.orders.value.length > 0) {
        selected.value = new Set();
        return;
      }
      selected.value = new Set(adminOrders.orders.value.map((o) => o.orderNo));
    };

    const clearSelection = () => {
      selected.value = new Set();
    };

    // 列表刷新（换页/重新进入）后旧的选择已无意义，直接清空
    watch(() => adminOrders.orders.value, () => clearSelection());

    const runBatch = async (mode) => {
      const targets = adminOrders.orders.value.filter((o) => selected.value.has(o.orderNo));
      if (targets.length === 0) return;
      const isApprove = mode === 'approve';
      const ok = await showConfirm({
        title: isApprove ? '批量同意退款' : '批量驳回退款',
        message: `将对选中的 ${targets.length} 笔退款申请执行「${isApprove ? '同意退款' : '驳回'}」。`
          + (isApprove ? '同意后会按订单金额全额退款，并按比例扣回已发放积分与月卡天数。' : '驳回后订单会恢复到申请退款前的状态。'),
        type: isApprove ? 'warning' : 'error',
        confirmText: isApprove ? '批量同意' : '批量驳回',
      });
      if (!ok) return;

      batchRunning.value = true;
      const failed = [];
      let succeeded = 0;
      try {
        for (const order of targets) {
          try {
            if (isApprove) await adminOrdersApi.approveRefund(order.orderNo, '批量同意');
            else await adminOrdersApi.rejectRefund(order.orderNo, '批量驳回');
            succeeded++;
          } catch (e) {
            failed.push(`${order.orderNo}: ${e.message}`);
          }
        }
        if (showSystemMsg) {
          if (failed.length === 0) {
            showSystemMsg(`已${isApprove ? '同意' : '驳回'} ${succeeded} 笔退款申请`);
          } else {
            showSystemMsg(`成功 ${succeeded} 笔，失败 ${failed.length} 笔（${failed.slice(0, 3).join('；')}）`, 'error');
          }
        }
        clearSelection();
        await adminOrders.loadOrders(adminOrders.ordersPage.value);
        if (loadPendingCount) loadPendingCount();
      } finally {
        batchRunning.value = false;
      }
    };

    const batchApprove = () => runBatch('approve');
    const batchReject = () => runBatch('reject');

    onMounted(() => {
      if (loadPendingCount) loadPendingCount();
    });

    return {
      orders: adminOrders.orders,
      ordersLoading: adminOrders.ordersLoading,
      ordersPage: adminOrders.ordersPage,
      ordersTotalElements: adminOrders.ordersTotalElements,
      ordersTotalPages: adminOrders.ordersTotalPages,
      loadOrders: adminOrders.loadOrders,
      goToOrdersPage: adminOrders.goToOrdersPage,
      openOrderDetail: adminOrders.openOrderDetail,
      selected,
      selectedCount,
      toggleSelect,
      toggleSelectAll,
      clearSelection,
      batchRunning,
      batchApprove,
      batchReject,
      planTierText,
      formatDate,
      openApproveRefundModal,
      openRejectRefundModal,
    };
  },
};
</script>

<style scoped>
.col-check { width: 36px; text-align: center; }
.col-check input { width: 14px; height: 14px; cursor: pointer; }
</style>