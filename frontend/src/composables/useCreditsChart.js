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
        bottom: 0,
        textStyle: { color: '#666' },
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '12%',
        top: '5%',
        containLabel: true,
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: dates,
        axisLine: { lineStyle: { color: '#ddd' } },
        axisLabel: { color: '#666' },
      },
      yAxis: {
        type: 'value',
        axisLine: { lineStyle: { color: '#ddd' } },
        axisLabel: { color: '#666' },
        splitLine: { lineStyle: { color: '#f5f5f5' } },
      },
      series: [
        {
          name: '收入',
          type: 'line',
          data: earned,
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { width: 2, color: chartColors.earned || '#2ecc71' },
          itemStyle: { color: chartColors.earned || '#2ecc71' },
          areaStyle: {
            color: {
              type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(46, 204, 113, 0.25)' },
                { offset: 1, color: 'rgba(46, 204, 113, 0.01)' },
              ],
            },
          },
        },
        {
          name: '支出',
          type: 'line',
          data: spent,
          smooth: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { width: 2, color: chartColors.spent || '#e74c3c' },
          itemStyle: { color: chartColors.spent || '#e74c3c' },
          areaStyle: {
            color: {
              type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(231, 76, 60, 0.25)' },
                { offset: 1, color: 'rgba(231, 76, 60, 0.01)' },
              ],
            },
          },
        },
        {
          name: '净额',
          type: 'bar',
          data: netArr,
          barWidth: '30%',
          itemStyle: {
            color: (params) => {
              const v = params.value;
              return v >= 0
                ? (chartColors.earned || '#2ecc71')
                : (chartColors.spent || '#e74c3c');
            },
            borderRadius: [3, 3, 0, 0],
          },
        },
      ],
    };
  });

  return { trendChartOption };
}
