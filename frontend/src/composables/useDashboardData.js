import { ref, computed } from 'vue';
import { dashboardApi, systemApi } from '../services/api';
import { chartColors } from '../config/echarts';
import logger from '../utils/logger';

export function useDashboardData() {
  const stats = ref({
    totalMessages: 0,
    todayMessages: 0,
    totalGroups: 0,
    activeGroups: 0,
    totalUsers: 0,
    totalConversations: 0,
    activeConversations: 0,
    totalFiles: 0,
    todayActiveUsers: 0,
    todayAiConversations: 0,
    weekAiMessages: 0,
    totalAiMessages: 0,
    rangeMessages: 0,
    rangeActiveUsers: 0,
    rangeAiMessages: 0,
  });
  const messageTrend = ref([]);
  const trendInterval = ref('day');
  /** 概览页全局时间范围（7/30/90 天），驱动统计卡与全部图表 */
  const rangeDays = ref(7);
  const groupRanking = ref([]);
  const qqRanking = ref([]);
  const messageTypeDistribution = ref([]);
  const hourlyDistribution = ref([]);
  const aiTrend = ref([]);
  /** 分块加载失败信息：单个接口挂掉只让对应图显示错误，不整页白屏 */
  const sectionErrors = ref({});
  const loading = ref(false);
  const lastLoadedAt = ref('');

  const diskUsage = ref({
    uploadsSizeFormatted: '0 B',
    totalSpaceFormatted: '0 B',
    usedSpaceFormatted: '0 B',
    freeSpaceFormatted: '0 B',
    usagePercent: 0,
  });

  /** 包装单块加载：记录错误、供图表显示重试，不影响其它块 */
  const loadSection = async (key, loader) => {
    try {
      await loader();
      if (sectionErrors.value[key]) {
        sectionErrors.value = { ...sectionErrors.value, [key]: '' };
      }
      return true;
    } catch (error) {
      logger.error(`加载 ${key} 失败:`, error);
      sectionErrors.value = { ...sectionErrors.value, [key]: error.message || '加载失败' };
      return false;
    }
  };

  const loadStats = () => loadSection('stats', async () => {
    stats.value = await dashboardApi.getStats(rangeDays.value);
  });

  const loadTrend = () => loadSection('trend', async () => {
    messageTrend.value = await dashboardApi.getMessageTrend(rangeDays.value, trendInterval.value);
  });

  const setRangeDays = (days) => {
    rangeDays.value = [7, 30, 90].includes(Number(days)) ? Number(days) : 7;
    return loadAll();
  };

  const loadRanking = () => loadSection('groupRanking', async () => {
    groupRanking.value = await dashboardApi.getGroupRanking(rangeDays.value);
  });

  const loadQQRanking = () => loadSection('qqRanking', async () => {
    qqRanking.value = await dashboardApi.getQQRanking(rangeDays.value);
  });

  const loadDiskUsage = () => loadSection('diskUsage', async () => {
    diskUsage.value = await systemApi.getDiskUsage();
  });

  const loadDistribution = () => loadSection('distribution', async () => {
    messageTypeDistribution.value = await dashboardApi.getMessageTypeDistribution(rangeDays.value);
  });

  const loadHourlyDistribution = () => loadSection('hourly', async () => {
    hourlyDistribution.value = await dashboardApi.getHourlyDistribution(rangeDays.value);
  });

  const loadAiTrend = () => loadSection('aiTrend', async () => {
    aiTrend.value = await dashboardApi.getAiTrend(rangeDays.value);
  });

  const loadAll = async () => {
    loading.value = true;
    try {
      await Promise.all([
        loadStats(),
        loadTrend(),
        loadRanking(),
        loadQQRanking(),
        loadDiskUsage(),
        loadDistribution(),
        loadHourlyDistribution(),
        loadAiTrend(),
      ]);
      lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN');
    } finally {
      loading.value = false;
    }
  };

  const retrySection = (key) => {
    const map = {
      stats: loadStats,
      trend: loadTrend,
      groupRanking: loadRanking,
      qqRanking: loadQQRanking,
      diskUsage: loadDiskUsage,
      distribution: loadDistribution,
      hourly: loadHourlyDistribution,
      aiTrend: loadAiTrend,
    };
    return (map[key] || loadAll)();
  };

  const errorOf = (key) => sectionErrors.value[key] || '';

  const trendChartOption = computed(() => {
    const data = messageTrend.value.map(item => item.count);
    const dates = messageTrend.value.map(item => item.date);
    return {
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(255,255,255,0.95)',
        borderColor: '#eee',
        borderWidth: 1,
        textStyle: { color: '#333' },
        formatter: (params) => {
          const p = params[0];
          return `<div style="font-weight:600;margin-bottom:4px">${p.axisValue}</div>
                  <span style="color:${chartColors.primary}">●</span> ${p.value} 条消息`;
        }
      },
      grid: { left: '3%', right: '4%', bottom: '3%', top: '10%', containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: dates,
        axisLine: { lineStyle: { color: '#ddd' } },
        axisLabel: { color: '#999', fontSize: 11 },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#f5f5f5', type: 'dashed' } },
        axisLabel: { color: '#999', fontSize: 11 }
      },
      series: [{
        name: '消息数',
        type: 'line',
        data: data,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5, color: chartColors.primary },
        itemStyle: { color: chartColors.primary },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(52,152,219,0.3)' },
              { offset: 1, color: 'rgba(52,152,219,0.02)' }
            ]
          }
        },
        emphasis: {
          focus: 'series',
          itemStyle: { borderWidth: 2, borderColor: '#fff' }
        },
        animationDuration: 800,
        animationEasing: 'cubicInOut'
      }]
    };
  });

  const buildRankingOption = (data) => {
    const names = data.map(item => item.groupName || item.nickname || item.name || '未知');
    const values = data.map(item => item.messageCount || item.count || 0);
    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: 'rgba(255,255,255,0.95)',
        borderColor: '#eee',
        textStyle: { color: '#333' },
        formatter: (params) => {
          const p = params[0];
          return `<div style="font-weight:600">${p.name}</div>
                  消息数: <b>${p.value}</b>`;
        }
      },
      grid: { left: '2%', right: '12%', bottom: '2%', top: '8%', containLabel: true },
      xAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#f5f5f5', type: 'dashed' } },
        axisLabel: { color: '#999', fontSize: 11 }
      },
      yAxis: {
        type: 'category',
        data: names.reverse(),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#666', fontSize: 12, width: 80, overflow: 'truncate' }
      },
      series: [{
        type: 'bar',
        data: values.reverse(),
        barWidth: '60%',
        itemStyle: {
          borderRadius: [0, 4, 4, 0],
          color: (params) => {
            const colors = chartColors.ranking;
            return colors[params.dataIndex % colors.length];
          }
        },
        label: {
          show: true,
          position: 'right',
          formatter: '{c}',
          color: '#666',
          fontSize: 11
        },
        animationDuration: 600,
        animationEasing: 'cubicOut'
      }]
    };
  };

  const groupRankingOption = computed(() => buildRankingOption(groupRanking.value));
  const qqRankingOption = computed(() => buildRankingOption(qqRanking.value));

  const hourlyChartOption = computed(() => {
    const hours = hourlyDistribution.value.map(item => item.hour);
    const counts = hourlyDistribution.value.map(item => item.count);
    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: 'rgba(255,255,255,0.95)',
        borderColor: '#eee',
        textStyle: { color: '#333' },
        formatter: (params) => {
          const p = params[0];
          return `<div style="font-weight:600">${p.axisValue}:00</div>
                  消息数: <b>${p.value}</b>`;
        }
      },
      grid: { left: '3%', right: '4%', bottom: '3%', top: '12%', containLabel: true },
      xAxis: {
        type: 'category',
        data: hours,
        axisLine: { lineStyle: { color: '#ddd' } },
        axisLabel: { color: '#999', fontSize: 10, interval: 2 },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#f5f5f5', type: 'dashed' } },
        axisLabel: { color: '#999', fontSize: 10 }
      },
      series: [{
        type: 'bar',
        data: counts,
        barWidth: '65%',
        itemStyle: {
          borderRadius: [3, 3, 0, 0],
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: '#667eea' },
              { offset: 1, color: '#764ba2' }
            ]
          }
        },
        emphasis: {
          itemStyle: { shadowBlur: 10, shadowColor: 'rgba(102,126,234,0.3)' }
        },
        animationDuration: 600,
        animationEasing: 'cubicOut'
      }]
    };
  });

  const aiTrendChartOption = computed(() => {
    const dates = aiTrend.value.map(item => item.date);
    const counts = aiTrend.value.map(item => item.count);
    return {
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(255,255,255,0.95)',
        borderColor: '#eee',
        textStyle: { color: '#333' },
        formatter: (params) => {
          const p = params[0];
          return `<div style="font-weight:600;margin-bottom:4px">${p.axisValue}</div>
                  <span style="color:#f39c12">●</span> ${p.value} 条AI消息`;
        }
      },
      grid: { left: '3%', right: '4%', bottom: '3%', top: '12%', containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: dates,
        axisLine: { lineStyle: { color: '#ddd' } },
        axisLabel: { color: '#999', fontSize: 11 },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#f5f5f5', type: 'dashed' } },
        axisLabel: { color: '#999', fontSize: 11 }
      },
      series: [{
        type: 'line',
        data: counts,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: { width: 2.5, color: '#f39c12' },
        itemStyle: { color: '#f39c12' },
        areaStyle: {
          color: {
            type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(243,156,18,0.3)' },
              { offset: 1, color: 'rgba(243,156,18,0.02)' }
            ]
          }
        },
        animationDuration: 800,
        animationEasing: 'cubicInOut'
      }]
    };
  });

  const distributionChartOption = computed(() => {
    const data = messageTypeDistribution.value.map(item => ({
      name: item.type,
      value: item.count
    }));
    return {
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(255,255,255,0.95)',
        borderColor: '#eee',
        textStyle: { color: '#333' },
        formatter: (params) => {
          return `<div style="font-weight:600">${params.name}</div>
                  数量: <b>${params.value}</b><br/>
                  占比: <b>${params.percent}%</b>`;
        }
      },
      legend: {
        orient: 'vertical',
        right: '5%',
        top: 'center',
        itemWidth: 12,
        itemHeight: 12,
        itemGap: 10,
        textStyle: { color: '#666', fontSize: 12 },
        selectedMode: true
      },
      series: [{
        type: 'pie',
        radius: ['45%', '72%'],
        center: ['38%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: {
          borderRadius: 4,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: true,
          formatter: '{b}\n{d}%',
          fontSize: 11,
          color: '#666'
        },
        labelLine: {
          length: 10,
          length2: 15,
          smooth: true
        },
        data: data.map(item => ({
          ...item,
          itemStyle: { color: chartColors.messageTypes[item.name] || chartColors.primary }
        })),
        emphasis: {
          scale: true,
          scaleSize: 8,
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0,0,0,0.2)'
          }
        },
        animationType: 'scale',
        animationDuration: 800,
        animationEasing: 'cubicOut'
      }]
    };
  });

  return {
    stats, messageTrend, rangeDays, trendInterval,
    groupRanking, qqRanking, messageTypeDistribution, diskUsage,
    hourlyDistribution, aiTrend, sectionErrors, loading, lastLoadedAt,
    loadStats, loadTrend, setRangeDays, errorOf, retrySection,
    loadRanking, loadQQRanking, loadDiskUsage, loadDistribution,
    loadHourlyDistribution, loadAiTrend,
    trendChartOption, groupRankingOption, qqRankingOption,
    distributionChartOption, hourlyChartOption, aiTrendChartOption,
    loadAll,
  };
}