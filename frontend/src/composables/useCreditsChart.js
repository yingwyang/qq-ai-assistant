import { computed } from 'vue';
import { chartColors } from '../config/echarts';

/**
 * 积分趋势 ECharts 图表封装。
 * 双折线（收入 earned / 支出 spent）+ 净额 net 柱状图。
 *
 * @param {import('vue').Ref<Array<{date:string, earned:number, spent:number, net:number}>>} trendData 响应式趋势数据
 * @returns {{ trendChartOption: import('vue').ComputedRef<object> }}
 */
export function useCreditsChart(trendData) {
  const trendChartOption = computed(() => {
    const list = trendData.value || [];
    const dates = list.map((d) => d.date);
    const earned = list.map((d) => Number(d.earned) || 0);
    const spent = list.map((d) => Number(d.spent) || 0);
    const netArr = list.map((d) =>
      (Number(d.net) !== undefined ? Number(d.net) : (Number(d.earned) || 0) - (Number(d.spent) || 0))
    );

    return {
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(255,255,255,0.95)',
        borderColor: '#eee',
        borderWidth: 1,
        textStyle: { color: '#333' },
      },
      legend: {
        data: ['收入', '支出', '净额'],
        top: 0,
        textStyle: { color: '#666', fontSize: 12 },
      },
      grid: { left: '3%', right: '4%', bottom: '3%', top: '16%', containLabel: true },
      xAxis: {
        type: 'category',
        data: dates,
        axisLine: { lineStyle: { color: '#ddd' } },
        axisLabel: { color: '#999', fontSize: 11 },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#f5f5f5', type: 'dashed' } },
        axisLabel: { color: '#999', fontSize: 11 },
      },
      series: [
        {
          name: '收入',
          type: 'line',
          data: earned,
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { width: 2.5, color: chartColors.success },
          itemStyle: { color: chartColors.success },
        },
        {
          name: '支出',
          type: 'line',
          data: spent,
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { width: 2.5, color: chartColors.danger },
          itemStyle: { color: chartColors.danger },
        },
        {
          name: '净额',
          type: 'bar',
          data: netArr,
          barWidth: '30%',
          itemStyle: {
            borderRadius: [4, 4, 0, 0],
            color: 'rgba(149, 165, 166, 0.6)',
          },
        },
      ],
    };
  });

  return { trendChartOption };
}

export default useCreditsChart;
