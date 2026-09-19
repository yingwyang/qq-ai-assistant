<template>
  <div class="tab-panel admin-refund-approve">
    <AdminPageHeader title="退款审批" subtitle="待审批的退款申请（同意 / 驳回）">
      <button class="btn-action promote" @click="loadOrders(ordersPage)">刷新</button>
    </AdminPageHeader>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
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
          <tr v-if="ordersLoading"><td colspan="7" class="audit-loading">加载中...</td></tr>
          <tr v-else-if="orders.length === 0"><td colspan="7" class="audit-empty">暂无待审批的退款申请</td></tr>
          <tr v-for="o in orders" :key="o.orderNo">
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
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';

export default {
  name: 'AdminRefundApprove',
  components: { Icon, AdminPageHeader },
  setup() {
    const adminOrders = inject('adminOrders');
    const planTierText = inject('adminPlanTierText');
    const formatDate = inject('adminFormatDate');
    const openApproveRefundModal = inject('adminOpenApproveRefundModal');
    const openRejectRefundModal = inject('adminOpenRejectRefundModal');

    return {
      orders: adminOrders.orders,
      ordersLoading: adminOrders.ordersLoading,
      ordersPage: adminOrders.ordersPage,
      ordersTotalElements: adminOrders.ordersTotalElements,
      ordersTotalPages: adminOrders.ordersTotalPages,
      loadOrders: adminOrders.loadOrders,
      goToOrdersPage: adminOrders.goToOrdersPage,
      openOrderDetail: adminOrders.openOrderDetail,
      planTierText,
      formatDate,
      openApproveRefundModal,
      openRejectRefundModal,
    };
  },
};
</script>

<style scoped>
</style>