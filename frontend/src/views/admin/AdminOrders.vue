<template>
  <div class="tab-panel admin-orders">
    <AdminPageHeader title="订单管理" subtitle="订阅订单查询、补单、退款与作废" />

    <!-- 筛选区 -->
    <div class="tx-filters">
      <div class="tx-filter-row">
        <input v-model="orderFilters.orderNo" type="text" placeholder="订单号精确搜索" class="tx-filter-input" />
        <input v-model="orderFilters.keyword" type="text" placeholder="用户关键字" class="tx-filter-input" />
        <div class="range-presets">
          <button class="chart-btn" @click="setOrdersRange('today')">今天</button>
          <button class="chart-btn" @click="setOrdersRange('7')">近 7 天</button>
          <button class="chart-btn" @click="setOrdersRange('30')">近 30 天</button>
          <button class="chart-btn" @click="setOrdersRange('90')">近 90 天</button>
          <button class="chart-btn" @click="setOrdersRange('all')">全部</button>
        </div>
      </div>
      <div class="tx-filter-row">
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
        <label class="size-picker">
          每页
          <select :value="String(ordersSize)" @change="setOrdersSize($event.target.value)">
            <option value="20">20</option>
            <option value="50">50</option>
            <option value="100">100</option>
          </select>
        </label>
        <button class="btn-action promote" @click="searchOrders">搜索</button>
        <button class="btn-action" @click="resetOrderFilters">重置</button>
        <button class="btn-action promote" :disabled="ordersExporting" @click="exportOrders">{{ ordersExporting ? '导出中...' : '导出 JSON' }}</button>
        <button class="btn-action promote" @click="openManualModal">+ 补单</button>
      </div>
    </div>

    <div class="data-toolbar">
      <span class="data-toolbar-info">
        共 {{ ordersTotalElements }} 笔订单<template v-if="pendingSelectedCount">，其中 {{ pendingSelectedCount }} 笔待确认收款可选</template>
      </span>
      <div class="data-toolbar-actions">
        <button
          class="btn-action enable"
          :disabled="batchRunning || pendingSelectedCount === 0"
          @click="batchApprovePayment"
        >批量确认收款<span v-if="pendingSelectedCount">（{{ pendingSelectedCount }}）</span></button>
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
                :checked="selectableOrderNos.length > 0 && selectedCount === selectableOrderNos.length"
                :disabled="selectableOrderNos.length === 0"
                @change="toggleSelectAll"
              />
            </th>
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
          <tr v-if="ordersLoading"><td colspan="10"><StatePanel state="loading" compact /></td></tr>
          <tr v-else-if="orders.length === 0"><td colspan="10"><StatePanel state="empty" title="暂无订单数据" compact /></td></tr>
          <tr v-for="o in orders" :key="o.orderNo">
            <td class="col-check">
              <input
                type="checkbox"
                :checked="selected.has(o.orderNo)"
                :disabled="o.status !== 'PENDING'"
                :title="o.status === 'PENDING' ? '' : '只有「待确认收款」订单可以批量确认收款'"
                @change="toggleSelect(o.orderNo)"
              />
            </td>
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
import { computed, inject, ref, watch } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { adminOrdersApi } from '../../services/api';
import { showConfirm } from '../../components/ConfirmDialog.vue';
import StatePanel from '../../components/common/StatePanel.vue';

export default {
  name: 'AdminOrders',
  components: { Icon, AdminPageHeader, StatePanel },
  setup() {
    const adminOrders = inject('adminOrders');
    const orderStatusOptions = inject('adminOrderStatusOptions');
    const orderPlanOptions = inject('adminOrderPlanOptions');
    const orderStatusText = inject('adminOrderStatusText');
    const planTierText = inject('adminPlanTierText');
    const formatDate = inject('adminFormatDate');
    const submitApprovePayment = inject('adminSubmitApprovePayment');
    const showSystemMsg = inject('adminShowMsg', null);

    const ordersSize = computed(() => adminOrders.ordersSize.value);
    const setOrdersSize = adminOrders.setOrdersSize;
    const setOrdersRange = adminOrders.setOrdersRange;

    // 批量确认收款：只有「待确认收款」订单可勾选，避免误把已支付/已退款订单再点一遍
    const selected = ref(new Set());
    const batchRunning = ref(false);
    const selectedCount = computed(() => selected.value.size);
    const selectableOrderNos = computed(() =>
      adminOrders.orders.value.filter((o) => o.status === 'PENDING').map((o) => o.orderNo));
    const pendingSelectedCount = computed(() =>
      adminOrders.orders.value.filter((o) => o.status === 'PENDING' && selected.value.has(o.orderNo)).length);

    const toggleSelect = (orderNo) => {
      const next = new Set(selected.value);
      if (next.has(orderNo)) next.delete(orderNo); else next.add(orderNo);
      selected.value = next;
    };

    const toggleSelectAll = () => {
      if (selected.value.size === selectableOrderNos.value.length && selectableOrderNos.value.length > 0) {
        selected.value = new Set();
        return;
      }
      selected.value = new Set(selectableOrderNos.value);
    };

    const clearSelection = () => {
      selected.value = new Set();
    };

    // 换页 / 换筛选后旧的勾选已不在当前列表，清空避免"看不见的选中"
    watch(() => adminOrders.orders.value, () => clearSelection());

    const batchApprovePayment = async () => {
      const targets = adminOrders.orders.value.filter(
        (o) => o.status === 'PENDING' && selected.value.has(o.orderNo));
      if (targets.length === 0) return;
      const total = targets.reduce((sum, o) => sum + Number(o.price || 0), 0);
      const ok = await showConfirm({
        title: '批量确认收款',
        message: `将把选中的 ${targets.length} 笔订单（合计 ¥${total.toFixed(2)}）标记为「已支付」并发放积分，是否继续？`,
        type: 'warning',
        confirmText: '批量确认',
      });
      if (!ok) return;

      batchRunning.value = true;
      const failed = [];
      let succeeded = 0;
      try {
        for (const order of targets) {
          try {
            await adminOrdersApi.approvePayment(order.orderNo);
            succeeded++;
          } catch (e) {
            failed.push(`${order.orderNo}: ${e.message}`);
          }
        }
        if (showSystemMsg) {
          if (failed.length === 0) {
            showSystemMsg(`已确认收款 ${succeeded} 笔订单`);
          } else {
            showSystemMsg(`成功 ${succeeded} 笔，失败 ${failed.length} 笔（${failed.slice(0, 3).join('；')}）`, 'error');
          }
        }
        clearSelection();
        await adminOrders.loadOrders(adminOrders.ordersPage.value);
      } finally {
        batchRunning.value = false;
      }
    };

    // 确认收款：二次确认后调用
    const handleApprovePayment = async (orderNo) => {
      const ok = await showConfirm({
        title: '确认收款',
        message: `确认该订单（${orderNo}）已到账？确认后将更新为"已支付"状态并发放积分。`,
        type: 'warning',
        confirmText: '确认到账',
      });
      if (!ok) return;
      submitApprovePayment(orderNo);
    };

    return {
      orders: adminOrders.orders,
      ordersLoading: adminOrders.ordersLoading,
      ordersPage: adminOrders.ordersPage,
      ordersSize,
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
      setOrdersSize,
      setOrdersRange,
      openManualModal: adminOrders.openManualModal,
      openCancelModal: adminOrders.openCancelModal,
      openRefundModal: adminOrders.openRefundModal,
      openDisputeModal: adminOrders.openDisputeModal,
      openOrderDetail: adminOrders.openOrderDetail,
      exportSingleOrder: adminOrders.exportSingleOrder,
      exportOrders: adminOrders.exportOrders,
      formatDate,
      handleApprovePayment,
      selected,
      selectedCount,
      selectableOrderNos,
      pendingSelectedCount,
      toggleSelect,
      toggleSelectAll,
      clearSelection,
      batchRunning,
      batchApprovePayment,
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
.status-multi-label { font-size: 13px; color: var(--text-secondary, #666); }
.status-chip { display: inline-flex; align-items: center; gap: 4px; padding: 3px 10px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 12px; font-size: 12px; cursor: pointer; color: var(--text-secondary, #666); user-select: none; transition: all 0.2s; }
.status-chip input[type='checkbox'] { width: 13px; height: 13px; cursor: pointer; }
.status-chip.active { background: var(--accent-color, #3498db); color: #fff; border-color: var(--accent-color, #3498db); }
.range-presets { display: flex; gap: 4px; }
.size-picker { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--text-secondary, #666); }
.size-picker select { padding: 5px 8px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 4px; background: var(--input-bg, #fff); color: var(--text-primary, #333); font-size: 12.5px; }
.col-check { width: 36px; text-align: center; }
.col-check input { width: 14px; height: 14px; cursor: pointer; }
</style>