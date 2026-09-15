import { ref, computed } from 'vue';
import { userDashboardApi } from '../services/api';
import { chartColors } from '../config/echarts';
import logger from '../utils/logger';

export function useUserDashboardData({ showSystemMsg }) {
  const stats = ref({
    totalMessages: 0,
    todayMessages: 0,
    totalGroups: 0,
    activeGroups: 0,
    totalConversations: 0,
    totalFiles: 0,
    filesSize: 0,
    filesSizeFormatted: '0 B',
  });
  const messageTrend = ref([]);
  const trendDays = ref(7);
  const trendInterval = ref('day');
  const groupRanking = ref([]);
  const messageTypeDistribution = ref([]);
  const aiTrend = ref([]);

  const isLoading = ref(false);
  const trendLoading = ref(false);

  const loadStats = async () => {
    try {
      stats.value = await userDashboardApi.getStats();
    } catch (error) {
      logger.error('加载用户统计数据失败:', error);
    }
  };

  const loadTrend = async () => {
    trendLoading.value = true;
    try {
      messageTrend.value = await userDashboardApi.getMessageTrend(trendDays.value, trendInterval.value);
    } catch (error) {
      logger.error('加载用户消息趋势失败:', error);
    } finally {
      trendLoading.value = false;
    }
  };

  const setTrendDays = (days, interval = 'day') => {
    trendDays.value = days;
    trendInterval.value = interval;
    loadTrend();
  };

  const loadGroupRanking = async () => {
    try {
      groupRanking.value = await userDashboardApi.getGroupRanking();
    } catch (error) {
      logger.error('加载用户群聊排行失败:', error);
    }
  };

  const loadTypeDistribution = async () => {
    try {
      messageTypeDistribution.value = await userDashboardApi.getMessageTypeDistribution();
    } catch (error) {
      logger.error('加载用户消息分布失败:', error);
    }
  };

  const loadAiTrend = async () => {
    try {
      aiTrend.value = await userDashboardApi.getAiTrend();
    } catch (error) {
      logger.error('加载用户AI趋势失败:', error);
    }
  };

  const loadAll = async () => {
    isLoading.value = true;
    try {
      await Promise.all([
        loadStats(),
        loadTrend(),
        loadGroupRanking(),
        loadTypeDistribution(),
        loadAiTrend(),
      ]);
    } finally {
      isLoading.value = false;
    }
  };

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

  const groupRankingOption = computed(() => {
    const data = groupRanking.value;
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

  return {
    stats, messageTrend, trendData: messageTrend,
    groupRanking, messageTypeDistribution, typeDistribution: messageTypeDistribution,
    aiTrend,
    isLoading, trendLoading,
    trendDays, trendInterval,
    trendChartOption, groupRankingOption,
    distributionChartOption, aiTrendChartOption,
    loadAll, loadStats, loadTrend,
    loadGroupRanking, loadTypeDistribution, loadAiTrend,
    setTrendDays,
  };
}
