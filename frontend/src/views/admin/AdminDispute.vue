<template>
  <div class="tab-panel admin-dispute">
    <AdminPageHeader title="纠纷处理" subtitle="用户发起的订单纠纷申请">
      <template #meta>
        <span v-if="selectedCount > 0" class="dirty-badge">已选 {{ selectedCount }} 笔</span>
      </template>
      <button class="btn-action promote" @click="loadOrders(ordersPage)">刷新</button>
    </AdminPageHeader>

    <div class="data-toolbar">
      <span class="data-toolbar-info">共 {{ ordersTotalElements }} 笔待处理纠纷</span>
      <div class="data-toolbar-actions">
        <button class="btn-action disable" :disabled="batchRunning || selectedCount === 0" @click="batchRejectDisputes">批量驳回纠纷</button>
        <button v-if="selectedCount > 0" class="btn-action" :disabled="batchRunning" @click="clearSelection">取消选择</button>
      </div>
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
            <th class="col-check">
              <input type="checkbox" :checked="orders.length > 0 && selectedCount === orders.length" @change="toggleSelectAll" />
            </th>
            <th>订单号</th>
            <th>套餐</th>
            <th>金额</th>
            <th>用户 ID</th>
            <th>纠纷原因</th>
            <th>申诉时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="ordersLoading"><td colspan="9" class="audit-loading">加载中...</td></tr>
          <tr v-else-if="orders.length === 0"><td colspan="9" class="audit-empty">暂无待处理的纠纷</td></tr>
          <tr v-for="o in orders" :key="o.orderNo">
            <td class="col-check">
              <input type="checkbox" :checked="selected.has(o.orderNo)" @change="toggleSelect(o.orderNo)" />
            </td>
            <td class="order-no-cell" @click="openOrderDetail(o.orderNo)" :title="o.orderNo">{{ o.orderNo }}</td>
            <td>{{ planTierText(o.planTier) }}</td>
            <td class="amount-cell">¥{{ Number(o.price || 0).toFixed(2) }}</td>
            <td>{{ o.userId }}</td>
            <td>{{ o.disputeReason || o.refundReason || '-' }}</td>
            <td>{{ formatDate(o.disputedAt || o.updatedAt) }}</td>
            <td class="action-cell">
              <button class="btn-link btn-warn" @click="openDisputeAgree(o.orderNo)">同意退款</button>
              <button class="btn-link btn-danger" @click="openDisputeReject(o.orderNo)">驳回纠纷</button>
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

export default {
  name: 'AdminDispute',
  components: { Icon, AdminPageHeader },
  setup() {
    const adminOrders = inject('adminOrders');
    const planTierText = inject('adminPlanTierText');
    const formatDate = inject('adminFormatDate');
    const openDisputeAgree = inject('adminOpenDisputeAgree');
    const openDisputeReject = inject('adminOpenDisputeReject');
    const showSystemMsg = inject('adminShowMsg', null);
    const loadPendingCount = inject('adminLoadPendingCount', null);

    // 批量驳回纠纷：勾选后逐条调用 resolveDispute(agree=false) 并汇总结果。
    // 批量「同意退款」会真实退款并扣回积分，风险高，故只提供逐单入口。
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

    watch(() => adminOrders.orders.value, () => clearSelection());

    const batchRejectDisputes = async () => {
      const targets = adminOrders.orders.value.filter((o) => selected.value.has(o.orderNo));
      if (targets.length === 0) return;
      const ok = await showConfirm({
        title: '批量驳回纠纷',
        message: `将对选中的 ${targets.length} 笔纠纷执行「驳回纠纷」（订单恢复到申请纠纷前的状态，不退款）。`,
        type: 'warning',
        confirmText: '批量驳回',
      });
      if (!ok) return;

      batchRunning.value = true;
      const failed = [];
      let succeeded = 0;
      try {
        for (const order of targets) {
          try {
            await adminOrdersApi.resolveDispute(order.orderNo, { agree: false, reason: '批量驳回纠纷' });
            succeeded++;
          } catch (e) {
            failed.push(`${order.orderNo}: ${e.message}`);
          }
        }
        if (showSystemMsg) {
          if (failed.length === 0) {
            showSystemMsg(`已驳回 ${succeeded} 笔纠纷`);
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
      batchRejectDisputes,
      planTierText,
      formatDate,
      openDisputeAgree,
      openDisputeReject,
    };
  },
};
</script>

<style scoped>
.col-check { width: 36px; text-align: center; }
.col-check input { width: 14px; height: 14px; cursor: pointer; }
</style>