<template>
  <div class="tab-panel admin-dashboard">
    <AdminPageHeader title="数据概览" subtitle="系统运行状态、消息趋势与收支概况">
      <template #meta>
        <span class="page-header-meta">{{ lastLoadedAt ? `数据更新于 ${lastLoadedAt}` : '加载中...' }}</span>
      </template>
      <div class="range-picker">
        <button
          v-for="d in [7, 30, 90]"
          :key="d"
          class="chart-btn"
          :class="{ active: rangeDays === d }"
          @click="setRangeDays(d)"
        >近 {{ d }} 天</button>
      </div>
      <button class="btn-action" :disabled="loading" @click="loadAll">{{ loading ? '刷新中...' : '刷新' }}</button>
    </AdminPageHeader>

    <p class="range-note">时间范围同时作用于下方统计卡与全部图表；「总计 / 总数」类指标始终为全量。</p>

    <!-- 业务 KPI：现金与积分（来自资金流水汇总） -->
    <div class="kpi-grid">
      <div class="kpi-card is-clickable" @click="goTransactions">
        <span class="kpi-label">区间现金收入</span>
        <span class="kpi-value is-in">¥{{ money(cashSummary.income) }}</span>
        <span class="kpi-hint">{{ rangeLabel }} · 订阅与手工记账</span>
      </div>
      <div class="kpi-card is-clickable" @click="goTransactions">
        <span class="kpi-label">区间现金支出</span>
        <span class="kpi-value is-out">¥{{ money(cashSummary.expense) }}</span>
        <span class="kpi-hint">{{ rangeLabel }} · 退款与模型调用成本</span>
      </div>
      <div class="kpi-card is-clickable" @click="goTransactions">
        <span class="kpi-label">区间净现金</span>
        <span class="kpi-value" :class="Number(cashSummary.net) >= 0 ? 'is-in' : 'is-out'">¥{{ money(cashSummary.net) }}</span>
        <span class="kpi-hint">收入 − 支出</span>
      </div>
      <div class="kpi-card is-clickable" @click="goTransactions">
        <span class="kpi-label">区间积分消耗</span>
        <span class="kpi-value">{{ cashSummary.pointsSpent ?? 0 }}</span>
        <span class="kpi-hint">AI 对话 / 分析与语音扣费合计</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">区间消息数</span>
        <span class="kpi-value">{{ stats.rangeMessages ?? 0 }}</span>
        <span class="kpi-hint">{{ rangeLabel }} · 活跃用户 {{ stats.rangeActiveUsers ?? 0 }}</span>
      </div>
      <div class="kpi-card">
        <span class="kpi-label">区间 AI 消息</span>
        <span class="kpi-value">{{ stats.rangeAiMessages ?? 0 }}</span>
        <span class="kpi-hint">近 7 天 {{ stats.weekAiMessages ?? 0 }}</span>
      </div>
    </div>

    <div v-if="cashError" class="notice-bar is-warn">
      现金收支汇总加载失败：{{ cashError }}（其余数据不受影响）
      <button class="btn-link" @click="loadCashSummary">重试</button>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-cards">
      <div class="stat-card">
        <div class="stat-icon blue"><Icon name="chat" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalMessages }}</div>
          <div class="stat-label">总消息数</div>
          <div class="stat-today">今日 {{ stats.todayMessages || 0 }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><Icon name="group" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalGroups }}</div>
          <div class="stat-label">群聊总数</div>
          <div class="stat-today">活跃 {{ stats.activeGroups || 0 }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><Icon name="user" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalUsers }}</div>
          <div class="stat-label">用户总数</div>
          <div class="stat-today">今日活跃 {{ stats.todayActiveUsers || 0 }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><Icon name="robot" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalConversations }}</div>
          <div class="stat-label">AI 对话数</div>
          <div class="stat-today">活跃 {{ stats.activeConversations || 0 }}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon teal"><Icon name="file" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalFiles }}</div>
          <div class="stat-label">文件总数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><Icon name="sparkles" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalAiMessages || 0 }}</div>
          <div class="stat-label">AI消息总数</div>
          <div class="stat-today">近 7 天 {{ stats.weekAiMessages || 0 }}</div>
        </div>
      </div>
      <div class="stat-card disk-card">
        <div class="stat-icon red"><Icon name="disk" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ diskUsage.uploadsSizeFormatted }}</div>
          <div class="stat-label">上传文件占用</div>
          <div class="stat-today">磁盘已用 {{ diskUsage.usagePercent }}%</div>
        </div>
        <div class="disk-bar">
          <div class="disk-bar-track">
            <div class="disk-bar-fill" :style="{ width: diskUsage.usagePercent + '%' }"></div>
          </div>
          <div class="disk-bar-label">
            <span>已用 {{ diskUsage.usedSpaceFormatted }}</span>
            <span>总计 {{ diskUsage.totalSpaceFormatted }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 图表区域：每块独立处理加载失败 -->
    <div class="charts-row">
      <div class="chart-card chart-large">
        <div class="chart-header">
          <h4>消息趋势（{{ rangeLabel }}）</h4>
          <div class="chart-controls">
            <button class="chart-btn" :class="{ active: trendInterval === 'hour' }" @click="setInterval('hour')">24小时</button>
            <button class="chart-btn" :class="{ active: trendInterval === 'day' }" @click="setInterval('day')">按天</button>
          </div>
        </div>
        <div v-if="errorOf('trend')" class="chart-error">
          加载失败：{{ errorOf('trend') }}
          <button class="btn-link" @click="retrySection('trend')">重试</button>
        </div>
        <v-chart v-else class="line-chart-echarts" :option="trendChartOption" autoresize />
      </div>

      <div class="chart-card">
        <h4>活跃群聊排行 TOP5（{{ rangeLabel }}）</h4>
        <div v-if="errorOf('groupRanking')" class="chart-error">
          加载失败：{{ errorOf('groupRanking') }}
          <button class="btn-link" @click="retrySection('groupRanking')">重试</button>
        </div>
        <div v-else-if="groupRanking.length === 0" class="chart-empty">该区间暂无群聊消息</div>
        <v-chart v-else class="ranking-chart" :option="groupRankingOption" autoresize />
      </div>

      <div class="chart-card">
        <h4>活跃QQ账号排行 TOP5（{{ rangeLabel }}）</h4>
        <div v-if="errorOf('qqRanking')" class="chart-error">
          加载失败：{{ errorOf('qqRanking') }}
          <button class="btn-link" @click="retrySection('qqRanking')">重试</button>
        </div>
        <div v-else-if="qqRanking.length === 0" class="chart-empty">该区间暂无发言记录</div>
        <v-chart v-else class="ranking-chart" :option="qqRankingOption" autoresize />
      </div>
    </div>

    <!-- 消息类型分布 -->
    <div class="chart-card distribution-card">
      <h4>消息类型分布（{{ rangeLabel }}）</h4>
      <div v-if="errorOf('distribution')" class="chart-error">
        加载失败：{{ errorOf('distribution') }}
        <button class="btn-link" @click="retrySection('distribution')">重试</button>
      </div>
      <v-chart v-else class="pie-chart-echarts" :option="distributionChartOption" autoresize />
    </div>

    <!-- 时段分布 + AI趋势 -->
    <div class="charts-row">
      <div class="chart-card">
        <h4>{{ rangeDays === 7 ? '今日' : rangeLabel + '内' }}消息时段分布</h4>
        <div v-if="errorOf('hourly')" class="chart-error">
          加载失败：{{ errorOf('hourly') }}
          <button class="btn-link" @click="retrySection('hourly')">重试</button>
        </div>
        <v-chart v-else class="bar-chart-echarts" :option="hourlyChartOption" autoresize />
      </div>

      <div class="chart-card">
        <h4>{{ rangeLabel }} AI 对话趋势</h4>
        <div v-if="errorOf('aiTrend')" class="chart-error">
          加载失败：{{ errorOf('aiTrend') }}
          <button class="btn-link" @click="retrySection('aiTrend')">重试</button>
        </div>
        <v-chart v-else class="line-chart-echarts" :option="aiTrendChartOption" autoresize />
      </div>
    </div>
  </div>
</template>

<script>
import { computed, inject, onMounted, ref } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { VChart } from '../../config/echarts';
import { adminCreditsApi } from '../../services/api';

export default {
  name: 'AdminDashboard',
  components: { Icon, AdminPageHeader, VChart },
  setup() {
    const dashboard = inject('adminDashboard');
    const setActiveTab = inject('adminSetActiveTab', null);

    // 现金/积分 KPI：复用资金流水页的汇总接口（days 与概览时间范围一致）
    const cashSummary = ref({ income: 0, expense: 0, net: 0, pointsSpent: 0 });
    const cashError = ref('');
    const loadCashSummary = async () => {
      cashError.value = '';
      try {
        const data = (await adminCreditsApi.getCashSummary({ days: dashboard.rangeDays.value })) || {};
        cashSummary.value = {
          income: data.income ?? 0,
          expense: data.expense ?? 0,
          net: data.net ?? 0,
          pointsSpent: data.pointsOut ?? 0,
        };
      } catch (e) {
        cashError.value = e.message || '加载失败';
        cashSummary.value = { income: 0, expense: 0, net: 0, pointsSpent: 0 };
      }
    };

    const loadAll = async () => {
      await Promise.all([dashboard.loadAll(), loadCashSummary()]);
    };

    const setRangeDays = async (days) => {
      await dashboard.setRangeDays(days);
      await loadCashSummary();
    };

    const setInterval = (interval) => {
      dashboard.trendInterval.value = interval;
      dashboard.loadTrend();
    };

    const money = (v) => Number(v || 0).toFixed(2);
    const rangeLabel = computed(() => `近 ${dashboard.rangeDays.value} 天`);
    const goTransactions = () => {
      if (setActiveTab) setActiveTab('credit-transactions');
    };

    onMounted(loadCashSummary);

    return {
      stats: dashboard.stats,
      rangeDays: dashboard.rangeDays,
      rangeLabel,
      trendChartOption: dashboard.trendChartOption,
      groupRankingOption: dashboard.groupRankingOption,
      qqRankingOption: dashboard.qqRankingOption,
      distributionChartOption: dashboard.distributionChartOption,
      hourlyChartOption: dashboard.hourlyChartOption,
      aiTrendChartOption: dashboard.aiTrendChartOption,
      groupRanking: dashboard.groupRanking,
      qqRanking: dashboard.qqRanking,
      trendInterval: dashboard.trendInterval,
      diskUsage: dashboard.diskUsage,
      loading: dashboard.loading,
      lastLoadedAt: dashboard.lastLoadedAt,
      errorOf: dashboard.errorOf,
      retrySection: dashboard.retrySection,
      loadAll,
      setRangeDays,
      setInterval,
      cashSummary,
      cashError,
      loadCashSummary,
      money,
      goTransactions,
    };
  },
};
</script>

<style scoped>
.range-picker { display: flex; gap: 4px; }
.range-note { font-size: 12px; color: var(--text-muted, #888); margin: 0 0 14px 0; }
.chart-error { display: flex; align-items: center; justify-content: center; gap: 10px; height: 180px; font-size: 13px; color: var(--danger-color, #e74c3c); }
.chart-empty { display: flex; align-items: center; justify-content: center; height: 180px; font-size: 13px; color: var(--text-muted, #888); }
.stats-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px;
  background: var(--card-bg, #fff);
  border-radius: 8px;
  border: 1px solid var(--border-color, #e8e8e8);
  box-shadow: 0 2px 8px var(--card-shadow, rgba(0,0,0,0.08));
}
.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-icon.blue { background: #e3f2fd; color: #1976d2; }
.stat-icon.purple { background: #f3e5f5; color: #7b1fa2; }
.stat-icon.green { background: #e8f5e9; color: #388e3c; }
.stat-icon.orange { background: #fff3e0; color: #f57c00; }
.stat-icon.teal { background: #e0f2f1; color: #00796b; }
.stat-icon.red { background: #ffebee; color: #c62828; }
.stat-icon.amber { background: #fff8e1; color: #ff8f00; }
.theme-dark .stat-icon.blue { background: rgba(25, 118, 210, 0.22); color: #64b5f6; }
.theme-dark .stat-icon.purple { background: rgba(123, 31, 162, 0.22); color: #ce93d8; }
.theme-dark .stat-icon.green { background: rgba(56, 142, 60, 0.22); color: #a5d6a7; }
.theme-dark .stat-icon.orange { background: rgba(245, 124, 0, 0.2); color: #ffb74d; }
.theme-dark .stat-icon.teal { background: rgba(0, 121, 107, 0.22); color: #80cbc4; }
.theme-dark .stat-icon.red { background: rgba(198, 40, 40, 0.22); color: #ef9a9a; }
.theme-dark .stat-icon.amber { background: rgba(255, 143, 0, 0.2); color: #ffb74d; }
.disk-card { flex-direction: column; align-items: flex-start; gap: 10px; }
.disk-card .stat-info { display: flex; align-items: center; gap: 12px; }
.disk-bar { width: 100%; }
.disk-bar-track { width: 100%; height: 8px; background: var(--border-color, #e8e8e8); border-radius: 4px; overflow: hidden; }
.disk-bar-fill { height: 100%; background: linear-gradient(90deg, #4caf50, #ff9800, #f44336); border-radius: 4px; transition: width 0.3s ease; }
.disk-bar-label { display: flex; justify-content: space-between; margin-top: 6px; font-size: 11px; color: var(--text-muted, #999); }
.stat-value { font-size: 24px; font-weight: 700; color: var(--text-primary, #333); }
.stat-label { font-size: 13px; color: var(--text-secondary, #666); }
.stat-today { font-size: 12px; color: var(--accent-color, #3498db); margin-top: 2px; }

.charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
.chart-card { background: var(--card-bg, #fff); border-radius: 8px; border: 1px solid var(--border-color, #e8e8e8); padding: 18px; box-shadow: 0 2px 8px var(--card-shadow, rgba(0,0,0,0.08)); }
.chart-card h4 { margin: 0 0 16px 0; font-size: 14px; color: var(--text-primary, #333); }
.chart-large { grid-column: 1 / -1; }
.line-chart-echarts { height: 280px; width: 100%; }
.ranking-chart { height: 220px; width: 100%; }
.pie-chart-echarts { height: 320px; width: 100%; }
.bar-chart-echarts { height: 240px; width: 100%; }
.chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.chart-header h4 { margin: 0; }
.chart-controls { display: flex; gap: 4px; }
.distribution-card { margin-bottom: 20px; }

@media (max-width: 768px) {
  .charts-row { grid-template-columns: 1fr; }
  .stats-cards { grid-template-columns: repeat(2, 1fr); }
}
</style>
