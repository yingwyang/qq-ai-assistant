import { ref } from 'vue';
import { dashboardApi, systemApi } from '../services/api';

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
  });
  const messageTrend = ref([]);
  const trendDays = ref(7);
  const trendInterval = ref('day');
  const groupRanking = ref([]);
  const qqRanking = ref([]);
  const messageTypeDistribution = ref([]);
  const diskUsage = ref({
    uploadsSizeFormatted: '0 B',
    totalSpaceFormatted: '0 B',
    usedSpaceFormatted: '0 B',
    freeSpaceFormatted: '0 B',
    usagePercent: 0,
  });
  const tooltipVisible = ref(false);
  const tooltipData = ref({ date: '', count: 0 });
  const tooltipStyle = ref({ left: '0px', top: '0px' });
  const scrollWrapper = ref(null);

  // ===== 加载方法 =====
  const loadStats = async () => {
    try {
      stats.value = await dashboardApi.getStats();
    } catch (error) {
      console.error('加载统计数据失败:', error);
    }
  };

  const loadTrend = async () => {
    try {
      messageTrend.value = await dashboardApi.getMessageTrend(trendDays.value, trendInterval.value);
    } catch (error) {
      console.error('加载消息趋势失败:', error);
    }
  };

  const setTrendDays = (days, interval = 'day') => {
    trendDays.value = days;
    trendInterval.value = interval;
    loadTrend();
  };

  const loadRanking = async () => {
    try {
      groupRanking.value = await dashboardApi.getGroupRanking();
    } catch (error) {
      console.error('加载群聊排行失败:', error);
    }
  };

  const loadQQRanking = async () => {
    try {
      qqRanking.value = await dashboardApi.getQQRanking();
    } catch (error) {
      console.error('加载QQ排行失败:', error);
    }
  };

  const loadDiskUsage = async () => {
    try {
      diskUsage.value = await systemApi.getDiskUsage();
    } catch (error) {
      console.error('加载磁盘使用情况失败:', error);
    }
  };

  const loadDistribution = async () => {
    try {
      messageTypeDistribution.value = await dashboardApi.getMessageTypeDistribution();
    } catch (error) {
      console.error('加载消息分布失败:', error);
    }
  };

  // ===== 图表计算方法 =====
  const getBarHeight = (count) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    return Math.max((count / max) * 100, 10);
  };

  const getLinePoints = () => {
    if (!messageTrend.value || messageTrend.value.length === 0) return '';
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    const len = messageTrend.value.length;
    return messageTrend.value.map((item, index) => {
      const x = len > 1 ? (index / (len - 1)) * 100 : 50;
      const y = 60 - (item.count / max) * 50 - 5;
      return `${x},${y}`;
    }).join(' ');
  };

  const getPointX = (index) => {
    const len = messageTrend.value.length;
    return len > 1 ? (index / (len - 1)) * 100 : 50;
  };

  const getPointY = (count) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    return 60 - (count / max) * 50 - 5;
  };

  const getPointXPercent = (index) => {
    const len = messageTrend.value.length;
    return len > 1 ? (index / (len - 1)) * 100 : 50;
  };

  const getPointYPercent = (count) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    const y = 60 - (count / max) * 50 - 5;
    return (y / 60) * 100;
  };

  const getAreaPoints = () => {
    if (!messageTrend.value || messageTrend.value.length === 0) return '';
    const len = messageTrend.value.length;
    const linePoints = getLinePoints();
    const firstX = len > 1 ? 0 : 50;
    const lastX = len > 1 ? 100 : 50;
    const bottomY = 55;
    return `${firstX},${bottomY} ${linePoints} ${lastX},${bottomY}`;
  };

  const getYAxisLabel = (index) => {
    const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
    const step = max / 4;
    return Math.round(step * (4 - index));
  };

  const getRankWidth = (count) => {
    const max = Math.max(...groupRanking.value.map((i) => i.messageCount), 1);
    return Math.max((count / max) * 100, 5);
  };

  const getQQRankWidth = (count) => {
    const max = Math.max(...qqRanking.value.map((i) => i.messageCount), 1);
    return Math.max((count / max) * 100, 5);
  };

  const getRankColor = (index) => {
    const colors = ['#e74c3c', '#e67e22', '#f1c40f', '#3498db', '#9b59b6'];
    return colors[index % colors.length];
  };

  const getDistWidth = (count) => {
    const max = Math.max(...messageTypeDistribution.value.map((i) => i.count), 1);
    return Math.max((count / max) * 100, 2);
  };

  const getPieGradient = () => {
    if (!messageTypeDistribution.value || messageTypeDistribution.value.length === 0) {
      return 'conic-gradient(#ccc 0% 100%)';
    }
    const total = messageTypeDistribution.value.reduce((sum, item) => sum + item.count, 0);
    if (total === 0) return 'conic-gradient(#ccc 0% 100%)';
    let current = 0;
    const segments = messageTypeDistribution.value.map((item) => {
      const start = current;
      const percent = (item.count / total) * 100;
      current += percent;
      return `${getDistColor(item.type)} ${start}% ${current}%`;
    });
    return `conic-gradient(${segments.join(', ')})`;
  };

  const getDistPercent = (count) => {
    const total = messageTypeDistribution.value.reduce((sum, item) => sum + item.count, 0);
    return total > 0 ? Math.round((count / total) * 100) : 0;
  };

  const getDistColor = (type) => {
    const colors = {
      '文本': '#3498db',
      '图片': '#e74c3c',
      '视频': '#2ecc71',
      '文件': '#f39c12',
      '音频': '#9b59b6',
      '语音': '#1abc9c',
      '其他': '#95a5a6',
    };
    return colors[type] || '#3498db';
  };

  // ===== 提示框 =====
  const showTooltip = (event, item) => {
    tooltipData.value = item;
    tooltipVisible.value = true;
    const rect = event.target.getBoundingClientRect();
    tooltipStyle.value = {
      left: rect.left + rect.width / 2 - 40 + 'px',
      top: rect.top - 50 + 'px',
    };
  };

  const hideTooltip = () => {
    tooltipVisible.value = false;
  };

  // ===== 名称截断 =====
  const truncateName = (name) => {
    if (!name) return '未知群聊';
    return name.length > 12 ? name.substring(0, 12) + '...' : name;
  };

  const truncateQQName = (name, qq) => {
    if (!name) return qq || '未知用户';
    return name.length > 10 ? name.substring(0, 10) + '...' : name;
  };

  // ===== 拖拽滚动 =====
  let isDown = false;
  let startX;
  let scrollLeftVal;
  let removeDragListeners = null;

  const setupDragScroll = () => {
    const el = scrollWrapper.value;
    if (!el) return;

    const onMouseDown = (e) => {
      isDown = true;
      el.classList.add('dragging');
      startX = e.pageX - el.offsetLeft;
      scrollLeftVal = el.scrollLeft;
    };
    const onMouseLeave = () => { isDown = false; el.classList.remove('dragging'); };
    const onMouseUp = () => { isDown = false; el.classList.remove('dragging'); };
    const onMouseMove = (e) => {
      if (!isDown) return;
      e.preventDefault();
      const x = e.pageX - el.offsetLeft;
      const walk = (x - startX) * 1.5;
      el.scrollLeft = scrollLeftVal - walk;
    };

    el.addEventListener('mousedown', onMouseDown);
    el.addEventListener('mouseleave', onMouseLeave);
    el.addEventListener('mouseup', onMouseUp);
    el.addEventListener('mousemove', onMouseMove);

    removeDragListeners = () => {
      el.removeEventListener('mousedown', onMouseDown);
      el.removeEventListener('mouseleave', onMouseLeave);
      el.removeEventListener('mouseup', onMouseUp);
      el.removeEventListener('mousemove', onMouseMove);
    };
  };

  const cleanupDragScroll = () => {
    if (removeDragListeners) {
      removeDragListeners();
      removeDragListeners = null;
    }
  };

  // ===== 一次性加载所有面板数据 =====
  const loadAll = async () => {
    await Promise.all([
      loadStats(),
      loadTrend(),
      loadRanking(),
      loadQQRanking(),
      loadDiskUsage(),
      loadDistribution(),
    ]);
  };

  return {
    stats, messageTrend, trendDays, trendInterval,
    groupRanking, qqRanking, messageTypeDistribution, diskUsage,
    tooltipVisible, tooltipData, tooltipStyle, scrollWrapper,
    loadStats, loadTrend, setTrendDays,
    loadRanking, loadQQRanking, loadDiskUsage, loadDistribution,
    getBarHeight, getLinePoints, getPointX, getPointY,
    getPointXPercent, getPointYPercent, getAreaPoints, getYAxisLabel,
    getRankWidth, getQQRankWidth, getRankColor,
    getDistWidth, getPieGradient, getDistPercent, getDistColor,
    showTooltip, hideTooltip,
    truncateName, truncateQQName,
    setupDragScroll, cleanupDragScroll,
    loadAll,
  };
}
