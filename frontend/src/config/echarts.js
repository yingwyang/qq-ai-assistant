import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
  MarkPointComponent,
} from 'echarts/components'
import VChart from 'vue-echarts'

use([
  CanvasRenderer,
  LineChart,
  PieChart,
  BarChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
  MarkPointComponent,
])

export const chartColors = {
  primary: '#3498db',
  success: '#2ecc71',
  warning: '#f39c12',
  danger: '#e74c3c',
  purple: '#9b59b6',
  teal: '#1abc9c',
  gray: '#95a5a6',
  messageTypes: {
    '文本': '#3498db',
    '图片': '#e74c3c',
    '视频': '#2ecc71',
    '文件': '#f39c12',
    '音频': '#9b59b6',
    '语音': '#1abc9c',
    '其他': '#95a5a6',
  },
  ranking: ['#e74c3c', '#e67e22', '#f1c40f', '#3498db', '#9b59b6'],
}

export { VChart }

