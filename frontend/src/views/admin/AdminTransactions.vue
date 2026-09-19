<template>
  <div class="tab-panel admin-transactions">
    <AdminPageHeader
      title="资金流水"
      subtitle="积分账 + 模拟现金账（订阅按套餐价、退款按比例、AI 消耗按积分成本折算，可手工记账）"
    />

    <!-- 时间范围：图表与 KPI 共用 -->
    <div class="cash-toolbar">
      <div class="cash-presets">
        <button
          v-for="p in presets"
          :key="p.value"
          class="preset-btn"
          :class="{ active: cashPreset === p.value }"
          @click="setCashPreset(p.value)"
        >{{ p.label }}</button>
      </div>
      <div class="cash-toolbar-right">
        <span v-if="cashLoading" class="cash-loading">统计中…</span>
        <button class="btn-action promote" @click="openCashModal">
          <Icon name="add" :size="14" /> 手工记账
        </button>
        <button class="btn-action" @click="refreshAll">
          <Icon name="refresh" :size="14" /> 刷新
        </button>
      </div>
    </div>

    <!-- KPI：现金与积分两套口径 -->
    <div class="kpi-grid">
      <div class="kpi-card in">
        <div class="kpi-label">现金收入</div>
        <div class="kpi-value">¥{{ money(cashSummary?.income) }}</div>
        <div class="kpi-sub">{{ cashRangeLabel }}</div>
      </div>
      <div class="kpi-card out">
        <div class="kpi-label">现金支出</div>
        <div class="kpi-value">¥{{ money(cashSummary?.expense) }}</div>
        <div class="kpi-sub">退款 + 模型成本</div>
      </div>
      <div class="kpi-card net">
        <div class="kpi-label">净现金流</div>
        <div class="kpi-value" :class="netClass">¥{{ money(cashSummary?.net) }}</div>
        <div class="kpi-sub">收入 - 支出</div>
      </div>
      <div class="kpi-card points">
        <div class="kpi-label">积分净变动</div>
        <div class="kpi-value">{{ signed(cashSummary?.pointsNet) }}</div>
        <div class="kpi-sub">发放 {{ cashSummary?.pointsIn ?? 0 }} / 消耗 {{ cashSummary?.pointsOut ?? 0 }}</div>
      </div>
      <div class="kpi-card count">
        <div class="kpi-label">流水条数</div>
        <div class="kpi-value">{{ cashSummary?.count ?? 0 }}</div>
        <div class="kpi-sub">所选时间范围内</div>
      </div>
    </div>

    <!-- 图表：趋势 + 构成 -->
    <div class="chart-grid">
      <div class="chart-card wide">
        <div class="chart-head">
          <span class="chart-title">现金收支趋势</span>
          <span class="chart-hint">柱=收入/支出（元），线=净现金流</span>
        </div>
        <VChart v-if="trendOption" class="chart-body" :option="trendOption" autoresize />
        <div v-else class="chart-empty">暂无数据</div>
      </div>
      <div class="chart-card">
        <div class="chart-head">
          <span class="chart-title">现金构成</span>
          <span class="chart-hint">按类别</span>
        </div>
        <VChart v-if="categoryOption" class="chart-body" :option="categoryOption" autoresize />
        <div v-else class="chart-empty">暂无现金流水</div>
      </div>
      <div class="chart-card">
        <div class="chart-head">
          <span class="chart-title">类型分布</span>
          <span class="chart-hint">积分口径（Top 8）</span>
        </div>
        <VChart v-if="typeOption" class="chart-body" :option="typeOption" autoresize />
        <div v-else class="chart-empty">暂无数据</div>
      </div>
    </div>

    <div v-if="cashSummary?.truncated" class="cash-warn">
      时间范围内流水过多，统计只覆盖最近的部分数据；请缩小时间范围查看准确结果。
    </div>

    <!-- 明细筛选 -->
    <div class="tx-filters">
      <div class="filter-title">流水明细</div>
      <div class="tx-filter-row">
        <input v-model="txFilters.userId" type="text" placeholder="用户 ID" class="tx-filter-input" />
        <select v-model="txFilters.type" class="audit-action-select">
          <option v-for="opt in txTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <select v-model="txFilters.direction" class="audit-action-select">
          <option v-for="opt in txDirectionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <input v-model="txFilters.relatedId" type="text" placeholder="关联 ID" class="tx-filter-input" />
        <label class="cash-only-toggle">
          <input v-model="cashOnly" type="checkbox" /> 只看有现金变动的
        </label>
      </div>
      <div class="tx-filter-row">
        <input v-model="txFilters.start" type="date" class="tx-filter-input" title="开始日期" />
        <input v-model="txFilters.end" type="date" class="tx-filter-input" title="结束日期" />
        <input v-model="txFilters.min" type="number" placeholder="最小积分" class="tx-filter-input" />
        <input v-model="txFilters.max" type="number" placeholder="最大积分" class="tx-filter-input" />
        <button class="btn-action promote" @click="searchTransactions">搜索</button>
        <button class="btn-action" @click="resetTxFilters">重置</button>
        <button class="btn-action promote" :disabled="txExporting" @click="exportTransactions">
          {{ txExporting ? '导出中...' : '导出 JSON' }}
        </button>
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
            <th>积分</th>
            <th>现金</th>
            <th>类别</th>
            <th>余额</th>
            <th>备注</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="txLoading"><td colspan="10" class="audit-loading">加载中...</td></tr>
          <tr v-else-if="visibleTxList.length === 0"><td colspan="10" class="audit-empty">暂无流水数据</td></tr>
          <tr v-for="tx in visibleTxList" :key="tx.id">
            <td>{{ tx.id }}</td>
            <td>{{ tx.userId || '全站' }}</td>
            <td>{{ txTypeTextLabel(tx.type) }}</td>
            <td><span class="dir-badge" :class="(tx.direction || '').toLowerCase()">{{ tx.direction === 'IN' ? '收入' : '支出' }}</span></td>
            <td :class="tx.direction === 'IN' ? 'credits-cell' : 'spent-cell'">
              {{ tx.amount ? (tx.direction === 'IN' ? '+' : '-') + Math.abs(tx.amount) : '—' }}
            </td>
            <td :class="cashClass(tx)">{{ tx.cashAmount ? (Number(tx.cashAmount) > 0 ? '+' : '-') + '¥' + money(Math.abs(Number(tx.cashAmount))) : '—' }}</td>
            <td>
              <span v-if="tx.cashCategory" class="cash-tag">{{ tx.cashCategoryLabel || tx.cashCategory }}</span>
              <span v-else class="muted">—</span>
            </td>
            <td>{{ tx.balanceAfter }}</td>
            <td class="audit-detail">{{ tx.remark || '-' }}</td>
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

    <!-- 手工记账弹窗（模拟） -->
    <div v-if="showCashModal" class="cash-modal-mask" @click="showCashModal = false">
      <div class="cash-modal" @click.stop>
        <div class="cash-modal-head">
          <strong>手工记一笔（模拟现金）</strong>
          <button class="cash-modal-close" @click="showCashModal = false">&times;</button>
        </div>
        <div class="cash-modal-body">
          <div class="cash-field">
            <label>方向</label>
            <div class="cash-dir-group">
              <button class="dir-btn" :class="{ active: cashForm.direction === 'IN' }" @click="cashForm.direction = 'IN'">收入</button>
              <button class="dir-btn" :class="{ active: cashForm.direction === 'OUT' }" @click="cashForm.direction = 'OUT'">支出</button>
            </div>
          </div>
          <div class="cash-field">
            <label>金额（元）</label>
            <input v-model="cashForm.amount" type="number" min="0.01" step="0.01" class="tx-filter-input" placeholder="如 30.00" />
          </div>
          <div class="cash-field">
            <label>类别</label>
            <select v-model="cashForm.category" class="audit-action-select">
              <option v-for="c in cashCategories" :key="c.value" :value="c.value">{{ c.label }}</option>
            </select>
          </div>
          <div class="cash-field">
            <label>关联用户（可选）</label>
            <input v-model="cashForm.userId" type="text" class="tx-filter-input" placeholder="留空 = 全站级记账" />
          </div>
          <div class="cash-field">
            <label>备注</label>
            <input v-model="cashForm.remark" type="text" class="tx-filter-input" placeholder="如：线下赞助 / 服务器月付" />
          </div>
          <p class="cash-modal-tip">
            说明：这是<b>模拟</b>记账，不改动任何人的积分，只进现金账用于对账与图表。
          </p>
        </div>
        <div class="cash-modal-foot">
          <button class="btn-action" @click="showCashModal = false">取消</button>
          <button class="btn-action promote" :disabled="cashEntrySaving || !cashForm.amount" @click="submitCashEntry">
            {{ cashEntrySaving ? '记账中…' : '确认记账' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { inject, ref, computed, onMounted } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import VChart from 'vue-echarts';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';

use([CanvasRenderer, BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent]);

const PRESETS = [
  { value: 'today', label: '今天' },
  { value: '7', label: '近 7 天' },
  { value: '30', label: '近 30 天' },
  { value: '90', label: '近 90 天' },
  { value: 'custom', label: '按筛选日期' },
];

const PALETTE = ['#4caf50', '#f44336', '#2196f3', '#ff9800', '#9c27b0', '#00bcd4', '#795548', '#607d8b'];

export default {
  name: 'AdminTransactions',
  components: { Icon, AdminPageHeader, VChart },
  setup() {
    const adminTx = inject('adminTx');
    const txTypeOptions = inject('adminTxTypeOptions');
    const txDirectionOptions = inject('adminTxDirectionOptions');
    const txTypeTextLabel = inject('adminTxTypeTextLabel');
    const cashCategories = inject('adminCashCategories');
    const formatDate = inject('adminFormatDate');

    const showCashModal = ref(false);
    const cashForm = ref({ direction: 'IN', amount: '', category: 'MANUAL', userId: '', remark: '' });
    /** 只在表格层过滤「有现金变动」，不动后端分页参数（避免与分页总数打架） */
    const cashOnly = ref(false);

    const money = (v) => Number(v ?? 0).toFixed(2);
    const signed = (v) => {
      const n = Number(v ?? 0);
      return (n > 0 ? '+' : '') + n;
    };

    const cashSummary = computed(() => adminTx.cashSummary.value);
    const cashLoading = computed(() => adminTx.cashLoading.value);
    const cashPreset = computed(() => adminTx.cashPreset.value);
    const cashEntrySaving = computed(() => adminTx.cashEntrySaving.value);

    const cashRangeLabel = computed(() => {
      const s = cashSummary.value;
      if (!s || !s.from) return '';
      return s.from === s.to ? s.from : `${s.from} ~ ${s.to}`;
    });

    const netClass = computed(() => {
      const n = Number(cashSummary.value?.net ?? 0);
      return n > 0 ? 'up' : (n < 0 ? 'down' : '');
    });

    const visibleTxList = computed(() => {
      const rows = adminTx.txList.value || [];
      if (!cashOnly.value) return rows;
      return rows.filter((tx) => tx.cashAmount && Number(tx.cashAmount) !== 0);
    });

    const cashClass = (tx) => {
      const v = Number(tx.cashAmount ?? 0);
      if (!v) return 'muted';
      return v > 0 ? 'cash-in-cell' : 'cash-out-cell';
    };

    // ==================== 图表 ====================
    const axisLabelColor = () => getComputedStyle(document.documentElement)
      .getPropertyValue('--text-secondary').trim() || '#888';
    const splitLineColor = () => getComputedStyle(document.documentElement)
      .getPropertyValue('--border-color').trim() || '#eee';

    const trendOption = computed(() => {
      const series = cashSummary.value?.series || [];
      if (!series.length) return null;
      return {
        color: PALETTE,
        tooltip: { trigger: 'axis', valueFormatter: (v) => (typeof v === 'number' ? `¥${v.toFixed(2)}` : v) },
        legend: { data: ['现金收入', '现金支出', '净现金流'], textStyle: { color: axisLabelColor() }, top: 0 },
        grid: { left: 8, right: 8, top: 34, bottom: 4, containLabel: true },
        xAxis: {
          type: 'category',
          data: series.map((s) => s.date.slice(5)),
          axisLabel: { color: axisLabelColor(), fontSize: 11 },
          axisLine: { lineStyle: { color: splitLineColor() } },
        },
        yAxis: {
          type: 'value',
          name: '元',
          nameTextStyle: { color: axisLabelColor(), fontSize: 11 },
          axisLabel: { color: axisLabelColor(), fontSize: 11 },
          splitLine: { lineStyle: { color: splitLineColor() } },
        },
        series: [
          { name: '现金收入', type: 'bar', stack: 'cash', barMaxWidth: 18, data: series.map((s) => Number(s.income || 0)) },
          { name: '现金支出', type: 'bar', stack: 'cash', barMaxWidth: 18, data: series.map((s) => Number(s.expense || 0)) },
          {
            name: '净现金流', type: 'line', smooth: true, symbolSize: 6,
            data: series.map((s) => Number(s.net || 0)),
            lineStyle: { width: 2 },
          },
        ],
      };
    });

    const categoryOption = computed(() => {
      const rows = cashSummary.value?.byCategory || [];
      if (!rows.length) return null;
      return {
        color: PALETTE,
        tooltip: { trigger: 'item', valueFormatter: (v) => `¥${Number(v).toFixed(2)}` },
        legend: { bottom: 0, textStyle: { color: axisLabelColor(), fontSize: 11 } },
        series: [{
          type: 'pie',
          radius: ['42%', '68%'],
          center: ['50%', '44%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: 'transparent', borderWidth: 2 },
          label: { color: axisLabelColor(), fontSize: 11, formatter: '{b}\n¥{c}' },
          data: rows.map((r) => ({ name: r.label, value: Math.abs(Number(r.amount || 0)) })),
        }],
      };
    });

    const typeOption = computed(() => {
      // 按积分规模排序取前 6：按类型数排序会让 20 万积分的订阅把其他条压成看不见
      const rows = [...(cashSummary.value?.byType || [])]
        .sort((a, b) => Number(b.points || 0) - Number(a.points || 0))
        .slice(0, 6);
      if (!rows.length) return null;
      const compact = (v) => (v >= 10000 ? (v / 10000).toFixed(1) + '万' : String(v));
      return {
        color: ['#2196f3'],
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: 8, right: 48, top: 8, bottom: 4, containLabel: true },
        xAxis: {
          type: 'value',
          axisLabel: { color: axisLabelColor(), fontSize: 11, formatter: compact },
          splitLine: { lineStyle: { color: splitLineColor() } },
        },
        yAxis: {
          type: 'category',
          data: rows.map((r) => txTypeTextLabel(r.type)),
          axisLabel: { color: axisLabelColor(), fontSize: 11 },
          axisLine: { lineStyle: { color: splitLineColor() } },
        },
        series: [{
          type: 'bar',
          barMaxWidth: 14,
          data: rows.map((r) => Number(r.points || 0)),
          itemStyle: { borderRadius: [0, 4, 4, 0] },
          label: {
            show: true, position: 'right', fontSize: 11,
            color: axisLabelColor(), formatter: (p) => compact(p.value),
          },
        }],
      };
    });

    // ==================== 交互 ====================
    const loadCashSummary = () => adminTx.loadCashSummary();
    const setCashPreset = (p) => adminTx.setCashPreset(p);

    const openCashModal = () => {
      cashForm.value = { direction: 'IN', amount: '', category: 'MANUAL', userId: '', remark: '' };
      showCashModal.value = true;
    };

    const submitCashEntry = async () => {
      const amount = Number(cashForm.value.amount);
      if (!(amount > 0)) return;
      try {
        await adminTx.createCashEntry({
          direction: cashForm.value.direction,
          amount,
          category: cashForm.value.category,
          userId: cashForm.value.userId || undefined,
          remark: cashForm.value.remark,
        });
        showCashModal.value = false;
      } catch (e) {
        // 错误提示已由 composable 统一弹出
      }
    };

    const refreshAll = async () => {
      await Promise.all([loadCashSummary(), adminTx.loadTransactions(adminTx.txPage.value)]);
    };

    onMounted(() => {
      loadCashSummary();
    });

    return {
      presets: PRESETS,
      cashCategories,
      cashSummary, cashLoading, cashPreset, cashEntrySaving, cashRangeLabel, netClass,
      showCashModal, cashForm, cashOnly, visibleTxList,
      money, signed, cashClass,
      trendOption, categoryOption, typeOption,
      txList: adminTx.txList,
      txLoading: adminTx.txLoading,
      txPage: adminTx.txPage,
      txTotalElements: adminTx.txTotalElements,
      txTotalPages: adminTx.txTotalPages,
      txExporting: adminTx.txExporting,
      txFilters: adminTx.filters,
      txTotalSummary: adminTx.txTotalSummary,
      txTypeOptions,
      txDirectionOptions,
      txTypeTextLabel,
      searchTransactions: adminTx.searchTransactions,
      resetTxFilters: adminTx.resetFilters,
      goToTxPage: adminTx.goToTxPage,
      exportTransactions: adminTx.exportTransactions,
      setCashPreset,
      openCashModal,
      submitCashEntry,
      refreshAll,
      formatDate,
    };
  },
};
</script>

<style scoped>
.panel-subtitle { margin-left: 10px; font-size: 12px; color: var(--text-muted, #888); }
.panel-subtitle b { color: var(--accent-color, #3498db); font-weight: 600; }

/* 时间范围工具条 */
.cash-toolbar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  flex-wrap: wrap; margin-bottom: 12px;
}
.cash-presets { display: inline-flex; gap: 6px; padding: 3px; border-radius: 9px; background: var(--bg-tertiary, #f5f5f5); border: 1px solid var(--border-color, #e8e8e8); }
.preset-btn {
  padding: 5px 12px; font-size: 12px; border: none; border-radius: 7px;
  background: transparent; color: var(--text-secondary, #666); cursor: pointer; transition: all .18s;
}
.preset-btn:hover { color: var(--accent-color, #3498db); }
.preset-btn.active { background: var(--card-bg, #fff); color: var(--accent-color, #3498db); font-weight: 600; box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.cash-toolbar-right { display: flex; align-items: center; gap: 8px; }
.cash-loading { font-size: 12px; color: var(--text-muted, #888); }

/* KPI */
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin-bottom: 14px; }
.kpi-card { padding: 14px 16px; border-radius: 10px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-left: 3px solid #90a4ae; }
.kpi-card.in { border-left-color: #4caf50; }
.kpi-card.out { border-left-color: #f44336; }
.kpi-card.net { border-left-color: #2196f3; }
.kpi-card.points { border-left-color: #9c27b0; }
.kpi-card.count { border-left-color: #ff9800; }
.kpi-label { font-size: 12px; color: var(--text-muted, #888); }
.kpi-value { font-size: 22px; font-weight: 700; margin: 4px 0 2px; color: var(--text-primary, #333); font-variant-numeric: tabular-nums; }
.kpi-value.up { color: #4caf50; }
.kpi-value.down { color: #f44336; }
.kpi-sub { font-size: 11px; color: var(--text-muted, #999); }

/* 图表 */
.chart-grid { display: grid; grid-template-columns: 2fr 1fr 1fr; gap: 12px; margin-bottom: 14px; }
@media (max-width: 1200px) { .chart-grid { grid-template-columns: 1fr 1fr; } .chart-card.wide { grid-column: 1 / -1; } }
.chart-card { background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 10px; padding: 12px 14px; display: flex; flex-direction: column; }
.chart-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 4px; }
.chart-title { font-size: 13px; font-weight: 600; color: var(--text-primary, #333); }
.chart-hint { font-size: 11px; color: var(--text-muted, #999); }
.chart-body { height: 220px; width: 100%; }
.chart-empty { height: 220px; display: flex; align-items: center; justify-content: center; font-size: 12px; color: var(--text-muted, #999); }
.cash-warn { margin-bottom: 12px; padding: 8px 12px; border-radius: 6px; font-size: 12px; color: #b7950b; background: rgba(241, 196, 15, .15); }

/* 筛选 */
.tx-filters { display: flex; flex-direction: column; gap: 10px; padding: 14px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; margin-bottom: 16px; }
.filter-title { font-size: 13px; font-weight: 600; color: var(--text-primary, #333); }
.tx-filter-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.tx-filter-input { padding: 6px 10px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 4px; font-size: 13px; min-width: 140px; background: var(--card-bg, #fff); color: var(--text-primary, #333); }
.tx-filter-input:focus { outline: none; border-color: var(--accent-color, #3498db); }
.cash-only-toggle { font-size: 12px; color: var(--text-secondary, #666); display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }

/* 表格里的现金列 */
.cash-in-cell { color: #4caf50; font-weight: 600; font-variant-numeric: tabular-nums; }
.cash-out-cell { color: #f44336; font-weight: 600; font-variant-numeric: tabular-nums; }
.muted { color: var(--text-muted, #aaa); }
.cash-tag { display: inline-block; padding: 1px 7px; border-radius: 10px; font-size: 11px; background: rgba(33, 150, 243, .12); color: #2980b9; }

/* 记账弹窗 */
.cash-modal-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .45); z-index: 3200; display: flex; align-items: center; justify-content: center; padding: 20px; }
.cash-modal { width: min(460px, 100%); background: var(--card-bg, #fff); border-radius: 10px; box-shadow: 0 12px 40px rgba(0, 0, 0, .3); }
.cash-modal-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--border-color, #eee); color: var(--text-primary, #333); }
.cash-modal-close { border: none; background: transparent; font-size: 20px; line-height: 1; cursor: pointer; color: var(--text-muted, #888); }
.cash-modal-body { padding: 14px 16px; display: flex; flex-direction: column; gap: 12px; }
.cash-field { display: flex; flex-direction: column; gap: 6px; }
.cash-field label { font-size: 12px; color: var(--text-secondary, #666); }
.cash-dir-group { display: inline-flex; gap: 6px; }
.dir-btn { flex: 1; padding: 7px 0; font-size: 13px; border-radius: 6px; border: 1px solid var(--border-color, #d9d9d9); background: var(--card-bg, #fff); color: var(--text-secondary, #666); cursor: pointer; }
.dir-btn.active { border-color: var(--accent-color, #3498db); color: var(--accent-color, #3498db); font-weight: 600; background: rgba(52, 152, 219, .08); }
.cash-modal-tip { font-size: 11px; color: var(--text-muted, #999); line-height: 1.6; margin: 0; }
.cash-modal-foot { display: flex; justify-content: flex-end; gap: 10px; padding: 12px 16px; border-top: 1px solid var(--border-color, #eee); }
</style>
