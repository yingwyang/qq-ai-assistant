<template>
  <div class="tab-panel admin-transactions">
    <div class="panel-title">
      <Icon name="file" :size="20" />
      <h2>积分流水</h2>
    </div>

    <!-- 顶栏汇总 -->
    <div class="tx-summary-bar">
      <div class="tx-summary-card earned">
        <div class="tx-summary-label">全站收入合计</div>
        <div class="tx-summary-value">+{{ txTotalSummary.totalEarned }}</div>
      </div>
      <div class="tx-summary-card spent">
        <div class="tx-summary-label">全站支出合计</div>
        <div class="tx-summary-value">-{{ txTotalSummary.totalSpent }}</div>
      </div>
      <div class="tx-summary-card net">
        <div class="tx-summary-label">全站净额</div>
        <div class="tx-summary-value">{{ txTotalSummary.totalNet > 0 ? '+' : '' }}{{ txTotalSummary.totalNet }}</div>
      </div>
      <div class="tx-summary-card count">
        <div class="tx-summary-label">全站总条数</div>
        <div class="tx-summary-value">{{ txTotalSummary.totalCount }}</div>
      </div>
    </div>
    <div class="tx-summary-bar" style="margin-top: 8px;">
      <div class="tx-summary-card earned" style="border-left-color: #81c784;">
        <div class="tx-summary-label">本页收入</div>
        <div class="tx-summary-value">+{{ txSummary.earned }}</div>
      </div>
      <div class="tx-summary-card spent" style="border-left-color: #e57373;">
        <div class="tx-summary-label">本页支出</div>
        <div class="tx-summary-value">-{{ txSummary.spent }}</div>
      </div>
      <div class="tx-summary-card net" style="border-left-color: #64b5f6;">
        <div class="tx-summary-label">本页净额</div>
        <div class="tx-summary-value">{{ txSummary.net > 0 ? '+' : '' }}{{ txSummary.net }}</div>
      </div>
      <div class="tx-summary-card count" style="border-left-color: #ffb74d;">
        <div class="tx-summary-label">本页条数</div>
        <div class="tx-summary-value">{{ txSummary.count }}</div>
      </div>
    </div>

    <!-- 筛选区 -->
    <div class="tx-filters">
      <div class="tx-filter-row">
        <input v-model="txFilters.userId" type="text" placeholder="用户 ID" class="tx-filter-input" />
        <select v-model="txFilters.type" class="audit-action-select">
          <option v-for="opt in txTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <select v-model="txFilters.direction" class="audit-action-select">
          <option v-for="opt in txDirectionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <input v-model="txFilters.relatedId" type="text" placeholder="关联 ID" class="tx-filter-input" />
      </div>
      <div class="tx-filter-row">
        <input v-model="txFilters.start" type="date" class="tx-filter-input" title="开始日期" />
        <input v-model="txFilters.end" type="date" class="tx-filter-input" title="结束日期" />
        <input v-model="txFilters.min" type="number" placeholder="最小金额" class="tx-filter-input" />
        <input v-model="txFilters.max" type="number" placeholder="最大金额" class="tx-filter-input" />
        <button class="btn-action promote" @click="searchTransactions">搜索</button>
        <button class="btn-action" @click="resetTxFilters">重置</button>
        <button class="btn-action promote" :disabled="txExporting" @click="exportTransactions">{{ txExporting ? '导出中...' : '导出 JSON' }}</button>
      </div>
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>用户 ID</th>
            <th>类型</th>
            <th>方向</th>
            <th>金额</th>
            <th>余额</th>
            <th>备注</th>
            <th>关联 ID</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="txLoading"><td colspan="9" class="audit-loading">加载中...</td></tr>
          <tr v-else-if="txList.length === 0"><td colspan="9" class="audit-empty">暂无流水数据</td></tr>
          <tr v-for="tx in txList" :key="tx.id">
            <td>{{ tx.id }}</td>
            <td>{{ tx.userId }}</td>
            <td>{{ txTypeTextLabel(tx.type) }}</td>
            <td><span class="dir-badge" :class="(tx.direction || '').toLowerCase()">{{ tx.direction === 'IN' ? '收入' : '支出' }}</span></td>
            <td :class="tx.direction === 'IN' ? 'credits-cell' : 'spent-cell'">{{ tx.direction === 'IN' ? '+' : '-' }}{{ Math.abs(tx.amount) }}</td>
            <td>{{ tx.balanceAfter }}</td>
            <td class="audit-detail">{{ tx.remark || '-' }}</td>
            <td class="audit-target">{{ tx.relatedId || '-' }}</td>
            <td>{{ formatDate(tx.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="user-pagination">
      <span class="pagination-info">共 {{ txTotalElements }} 条，第 {{ txPage + 1 }} / {{ Math.max(1, txTotalPages) }} 页</span>
      <div class="pagination-btns">
        <button class="btn-page" :disabled="txPage === 0" @click="goToTxPage(0)">首页</button>
        <button class="btn-page" :disabled="txPage === 0" @click="goToTxPage(txPage - 1)">上一页</button>
        <button class="btn-page" :disabled="txPage >= txTotalPages - 1" @click="goToTxPage(txPage + 1)">下一页</button>
        <button class="btn-page" :disabled="txPage >= txTotalPages - 1" @click="goToTxPage(txTotalPages - 1)">末页</button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';

export default {
  name: 'AdminTransactions',
  components: { Icon },
  setup() {
    const adminTx = inject('adminTx');
    const txTypeOptions = inject('adminTxTypeOptions');
    const txDirectionOptions = inject('adminTxDirectionOptions');
    const txTypeTextLabel = inject('adminTxTypeTextLabel');
    const formatDate = inject('adminFormatDate');
    return {
      txList: adminTx.txList,
      txLoading: adminTx.txLoading,
      txPage: adminTx.txPage,
      txTotalElements: adminTx.txTotalElements,
      txTotalPages: adminTx.txTotalPages,
      txExporting: adminTx.txExporting,
      txFilters: adminTx.filters,
      txSummary: adminTx.summary,
      txTotalSummary: adminTx.txTotalSummary,
      txTypeOptions,
      txDirectionOptions,
      txTypeTextLabel,
      loadTransactions: adminTx.loadTransactions,
      searchTransactions: adminTx.searchTransactions,
      resetTxFilters: adminTx.resetFilters,
      goToTxPage: adminTx.goToTxPage,
      exportTransactions: adminTx.exportTransactions,
      formatDate,
    };
  },
};
</script>

<style scoped>
.tx-summary-bar { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; margin-bottom: 16px; }
.tx-summary-card { padding: 14px 16px; border-radius: 8px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); }
.tx-summary-card.earned { border-left: 3px solid #4caf50; }
.tx-summary-card.spent { border-left: 3px solid #f44336; }
.tx-summary-card.net { border-left: 3px solid #2196f3; }
.tx-summary-card.count { border-left: 3px solid #ff9800; }
.tx-summary-label { font-size: 12px; color: var(--text-muted, #888); margin-bottom: 4px; }
.tx-summary-value { font-size: 20px; font-weight: 700; color: var(--text-primary, #333); }
.tx-filters { display: flex; flex-direction: column; gap: 10px; padding: 14px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; margin-bottom: 16px; }
.tx-filter-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.tx-filter-input { padding: 6px 10px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 4px; font-size: 13px; min-width: 140px; background: var(--card-bg, #fff); color: var(--text-primary, #333); }
.tx-filter-input:focus { outline: none; border-color: var(--accent-color, #3498db); }
</style>