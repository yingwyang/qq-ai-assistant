<template>
  <div class="tab-panel admin-dashboard">
    <div class="panel-title">
      <Icon name="dashboard" :size="20" />
      <h2>数据概览</h2>
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
          <div class="stat-today">近7天 {{ stats.todayAiConversations || 0 }}</div>
        </div>
      </div>
      <div class="stat-card disk-card">
        <div class="stat-icon red"><Icon name="disk" :size="28" /></div>
        <div class="stat-info">
          <div class="stat-value">{{ diskUsage.uploadsSizeFormatted }}</div>
          <div class="stat-label">上传文件占用</div>
          <div class="stat-today">D盘已用 {{ diskUsage.usagePercent }}%</div>
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

    <!-- 图表区域 -->
    <div class="charts-row">
      <div class="chart-card chart-large">
        <div class="chart-header">
          <h4>消息趋势</h4>
          <div class="chart-controls">
            <button class="chart-btn" :class="{ active: trendInterval === 'hour' }" @click="setTrendDays(1, 'hour')">24小时</button>
            <button v-for="d in [7, 30, 90]" :key="d" class="chart-btn" :class="{ active: trendDays === d && trendInterval === 'day' }" @click="setTrendDays(d, 'day')">{{ d }}天</button>
          </div>
        </div>
        <v-chart class="line-chart-echarts" :option="trendChartOption" autoresize />
      </div>

      <div class="chart-card">
        <h4>活跃群聊排行 TOP5</h4>
        <v-chart class="ranking-chart" :option="groupRankingOption" autoresize />
      </div>

      <div class="chart-card">
        <h4>活跃QQ账号排行 TOP5</h4>
        <v-chart class="ranking-chart" :option="qqRankingOption" autoresize />
      </div>
    </div>

    <!-- 消息类型分布 -->
    <div class="chart-card distribution-card">
      <h4>消息类型分布</h4>
      <v-chart class="pie-chart-echarts" :option="distributionChartOption" autoresize />
    </div>

    <!-- 新增图表区域：时段分布 + AI趋势 -->
    <div class="charts-row">
      <div class="chart-card">
        <h4>今日消息时段分布</h4>
        <v-chart class="bar-chart-echarts" :option="hourlyChartOption" autoresize />
      </div>

      <div class="chart-card">
        <h4>近7天 AI 对话趋势</h4>
        <v-chart class="line-chart-echarts" :option="aiTrendChartOption" autoresize />
      </div>
    </div>
  </div>
</template>

<script>
import { inject, computed } from 'vue';
import Icon from '../../components/Icon.vue';
import { VChart } from '../../config/echarts';

export default {
  name: 'AdminDashboard',
  components: { Icon, VChart },
  setup() {
    const dashboard = inject('adminDashboard');
    return {
      stats: dashboard.stats,
      trendChartOption: dashboard.trendChartOption,
      groupRankingOption: dashboard.groupRankingOption,
      qqRankingOption: dashboard.qqRankingOption,
      distributionChartOption: dashboard.distributionChartOption,
      hourlyChartOption: dashboard.hourlyChartOption,
      aiTrendChartOption: dashboard.aiTrendChartOption,
      trendDays: dashboard.trendDays,
      trendInterval: dashboard.trendInterval,
      diskUsage: dashboard.diskUsage,
      setTrendDays: dashboard.setTrendDays,
    };
  },
};
</script>

<style scoped>
.admin-dashboard {
  /* container */
}
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
.disk-card { flex-direction: column; align-items: flex-start; gap: 10px; }
.disk-card .stat-info { display: flex; align-items: center; gap: 12px; }
.disk-bar { width: 100%; }
.disk-bar-track { width: 100%; height: 8px; background: var(--border-color, #e8e8e8); border-radius: 4px; overflow: hidden; }
.disk-bar-fill { height: 100%; background: linear-gradient(90deg, #4caf50, #ff9800, #f44336); border-radius: 4px; transition: width 0.3s ease; }
.disk-bar-label { display: flex; justify-content: space-between; margin-top: 6px; font-size: 11px; color: var(--text-muted, #999); }
.stat-value { font-size: 24px; font-weight: 700; color: var(--text-primary, #333); }
.stat-label { font-size: 13px; color: var(--text-secondary, #666); }
.stat-today { font-size: 12px; color: #3498db; margin-top: 2px; }

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