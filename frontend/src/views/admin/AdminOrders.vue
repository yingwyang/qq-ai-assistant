<template>
  <div class="tab-panel admin-orders">
    <div class="panel-title">
      <Icon name="file" :size="20" />
      <h2>订单管理</h2>
    </div>

    <!-- 筛选区 -->
    <div class="tx-filters">
      <div class="tx-filter-row">
        <input v-model="orderFilters.orderNo" type="text" placeholder="订单号精确搜索" class="tx-filter-input" />
        <input v-model="orderFilters.keyword" type="text" placeholder="用户关键字" class="tx-filter-input" />
        <div class="status-multi-select">
          <span class="status-multi-label">状态：</span>
          <label v-for="opt in orderStatusOptions" :key="opt.value" class="status-chip" :class="{ active: orderFilters.statusSelected.includes(opt.value) }">
            <input type="checkbox" :value="opt.value" v-model="orderFilters.statusSelected" />
            {{ opt.label }}
          </label>
        </div>
      </div>
      <div class="tx-filter-row">
        <input v-model="orderFilters.start" type="date" class="tx-filter-input" title="开始日期" />
        <input v-model="orderFilters.end" type="date" class="tx-filter-input" title="结束日期" />
        <input v-model="orderFilters.minPrice" type="number" step="0.01" placeholder="最小金额" class="tx-filter-input" />
        <input v-model="orderFilters.maxPrice" type="number" step="0.01" placeholder="最大金额" class="tx-filter-input" />
        <button class="btn-action promote" @click="searchOrders">搜索</button>
        <button class="btn-action" @click="resetOrderFilters">重置</button>
        <button class="btn-action promote" :disabled="ordersExporting" @click="exportOrders">{{ ordersExporting ? '导出中...' : '导出 JSON' }}</button>
        <button class="btn-action promote" @click="openManualModal">+ 补单</button>
      </div>
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
            <th>订单号</th>
            <th>用户 ID</th>
            <th>套餐</th>
            <th>金额</th>
            <th>积分</th>
            <th>状态</th>
            <th>支付时间</th>
            <th>到期</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="ordersLoading"><td colspan="9" class="audit-loading">加载中...</td></tr>
          <tr v-else-if="orders.length === 0"><td colspan="9" class="audit-empty">暂无订单数据</td></tr>
          <tr v-for="o in orders" :key="o.orderNo">
            <td class="order-no-cell" @click="openOrderDetail(o.orderNo)" :title="o.orderNo">{{ o.orderNo }}</td>
            <td>{{ o.userId }}</td>
            <td>{{ planTierText(o.planTier) }}</td>
            <td class="amount-cell">¥{{ Number(o.price || 0).toFixed(2) }}</td>
            <td class="credits-cell">{{ o.creditAmount }}</td>
            <td><span class="status-tag" :class="'status-' + o.status">{{ orderStatusText(o.status) }}</span></td>
            <td>{{ formatDate(o.paidAt) }}</td>
            <td>{{ formatDate(o.expiresAt) }}</td>
            <td class="action-cell">
              <button class="btn-link" @click="openOrderDetail(o.orderNo)">详情</button>
              <button v-if="o.status === 'PENDING'" class="btn-link" style="color: #2e7d32; font-weight: 600;" @click="handleApprovePayment(o.orderNo)">确认收款</button>
              <button v-if="o.status === 'PENDING'" class="btn-link btn-danger" @click="openCancelModal(o.orderNo)">作废</button>
              <button v-if="o.status === 'PAID'" class="btn-link btn-warn" @click="openRefundModal(o.orderNo)">退款</button>
              <button v-if="o.status === 'DISPUTED'" class="btn-link btn-warn" @click="openDisputeModal(o.orderNo)">处理纠纷</button>
              <button class="btn-link" @click="exportSingleOrder(o.orderNo)">导出</button>
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

export default {
  name: 'AdminOrders',
  components: { Icon },
  setup() {
    const adminOrders = inject('adminOrders');
    const orderStatusOptions = inject('adminOrderStatusOptions');
    const orderPlanOptions = inject('adminOrderPlanOptions');
    const orderStatusText = inject('adminOrderStatusText');
    const planTierText = inject('adminPlanTierText');
    const formatDate = inject('adminFormatDate');
    const submitApprovePayment = inject('adminSubmitApprovePayment');

    // 确认收款：二次确认后调用
    const handleApprovePayment = (orderNo) => {
      if (!window.confirm(`确认该订单（${orderNo}）已到账？确认后将更新为"已支付"状态并发放积分。`)) return;
      submitApprovePayment(orderNo);
    };

    return {
      orders: adminOrders.orders,
      ordersLoading: adminOrders.ordersLoading,
      ordersPage: adminOrders.ordersPage,
      ordersTotalElements: adminOrders.ordersTotalElements,
      ordersTotalPages: adminOrders.ordersTotalPages,
      ordersExporting: adminOrders.ordersExporting,
      orderFilters: adminOrders.filters,
      orderStatusOptions,
      orderPlanOptions,
      orderStatusText,
      planTierText,
      loadOrders: adminOrders.loadOrders,
      searchOrders: adminOrders.searchOrders,
      resetOrderFilters: adminOrders.resetOrderFilters,
      goToOrdersPage: adminOrders.goToOrdersPage,
      openManualModal: adminOrders.openManualModal,
      openCancelModal: adminOrders.openCancelModal,
      openRefundModal: adminOrders.openRefundModal,
      openDisputeModal: adminOrders.openDisputeModal,
      openOrderDetail: adminOrders.openOrderDetail,
      exportSingleOrder: adminOrders.exportSingleOrder,
      exportOrders: adminOrders.exportOrders,
      formatDate,
      handleApprovePayment,
    };
  },
};
</script>

<style scoped>
.tx-filters { display: flex; flex-direction: column; gap: 10px; padding: 14px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; margin-bottom: 16px; }
.tx-filter-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.tx-filter-input { padding: 6px 10px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 4px; font-size: 13px; min-width: 140px; background: var(--card-bg, #fff); color: var(--text-primary, #333); }
.tx-filter-input:focus { outline: none; border-color: var(--accent-color, #3498db); }
.status-multi-select { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.status-multi-label { font-size: 13px; color: #666; }
.status-chip { display: inline-flex; align-items: center; gap: 4px; padding: 3px 10px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 12px; font-size: 12px; cursor: pointer; color: #666; user-select: none; transition: all 0.2s; }
.status-chip input[type='checkbox'] { width: 13px; height: 13px; cursor: pointer; }
.status-chip.active { background: var(--accent-color, #3498db); color: #fff; border-color: var(--accent-color, #3498db); }
</style>