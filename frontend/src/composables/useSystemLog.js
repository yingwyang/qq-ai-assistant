import { computed, ref } from 'vue';
import { adminApi } from '../services/api';
import logger from '../utils/logger';

/**
 * 系统日志管理 composable
 * - 应用日志：级别 / 关键字 / 时间范围过滤、分页、自动刷新、导出
 * - 审计日志：操作人 / 操作类型 / 目标 / 结果 / 时间范围过滤、分页、导出 CSV
 * - 两个子标签切换
 *
 * 注意：应用日志由后端按行读取文件，只检索「最近 5000 行」窗口，
 * 页面上的说明文案与导出行为都与该上限保持一致，避免管理员误以为能搜到全部历史。
 */
export const APP_LOG_MAX_LINES = 5000;

export function useSystemLog({ showSystemMsg } = {}) {
  // 子标签：app / audit
  const logSubTab = ref('app');

  // ===== 应用日志 =====
  const appLogs = ref([]);
  const appLogsLoading = ref(false);
  const appLogsError = ref('');
  const appLogLevel = ref('ALL');
  const appLogKeyword = ref('');
  const appLogFrom = ref('');
  const appLogTo = ref('');
  const appLogPage = ref(0);
  const appLogSize = ref(50);
  const appLogTotal = ref(0);
  const appLogTotalPages = ref(0);
  const appLogNote = ref('');

  const appLogLevelOptions = [
    { value: 'ALL', label: '全部' },
    { value: 'INFO', label: 'INFO' },
    { value: 'WARN', label: 'WARN' },
    { value: 'ERROR', label: 'ERROR' },
    { value: 'DEBUG', label: 'DEBUG' },
  ];

  const appLogHasFilter = computed(() =>
    appLogLevel.value !== 'ALL' || !!appLogKeyword.value.trim() || !!appLogFrom.value || !!appLogTo.value);

  const loadAppLogs = async () => {
    appLogsLoading.value = true;
    appLogsError.value = '';
    try {
      const res = await adminApi.getLogs({
        level: appLogLevel.value,
        keyword: appLogKeyword.value.trim() || undefined,
        from: appLogFrom.value || undefined,
        to: appLogTo.value || undefined,
        page: appLogPage.value,
        size: appLogSize.value,
      });
      const data = res || {};
      appLogs.value = data.content || [];
      appLogTotal.value = data.totalElements || 0;
      appLogTotalPages.value = data.totalPages || 0;
      appLogNote.value = data.message || '';
    } catch (error) {
      logger.error('加载应用日志失败:', error);
      appLogsError.value = error.message || '加载应用日志失败';
      appLogs.value = [];
      appLogTotal.value = 0;
      appLogTotalPages.value = 0;
    } finally {
      appLogsLoading.value = false;
    }
  };

  const setAppLogLevel = (level) => {
    appLogLevel.value = level || 'ALL';
    appLogPage.value = 0;
    loadAppLogs();
  };

  const setAppLogRange = ({ from, to }) => {
    if (from !== undefined) appLogFrom.value = from;
    if (to !== undefined) appLogTo.value = to;
    appLogPage.value = 0;
    loadAppLogs();
  };

  let appSearchTimer = null;
  const onAppLogKeywordInput = () => {
    if (appSearchTimer) clearTimeout(appSearchTimer);
    appSearchTimer = setTimeout(() => {
      appLogPage.value = 0;
      loadAppLogs();
    }, 350);
  };

  const resetAppLogFilters = () => {
    appLogLevel.value = 'ALL';
    appLogKeyword.value = '';
    appLogFrom.value = '';
    appLogTo.value = '';
    appLogPage.value = 0;
    loadAppLogs();
  };

  const goToAppLogPage = (page) => {
    if (page < 0 || (appLogTotalPages.value > 0 && page >= appLogTotalPages.value)) return;
    appLogPage.value = page;
    loadAppLogs();
  };

  const refreshAppLogs = () => loadAppLogs();

  const appLogExportUrl = computed(() => adminApi.exportLogsUrl({
    level: appLogLevel.value,
    keyword: appLogKeyword.value.trim() || undefined,
    from: appLogFrom.value || undefined,
    to: appLogTo.value || undefined,
  }));

  // ===== 自动刷新（仅当前子标签可见时） =====
  const autoRefresh = ref(false);
  let autoTimer = null;
  const AUTO_REFRESH_MS = 5000;

  const stopAutoRefresh = () => {
    if (autoTimer) {
      clearInterval(autoTimer);
      autoTimer = null;
    }
  };

  const startAutoRefresh = () => {
    stopAutoRefresh();
    autoTimer = setInterval(() => {
      if (document.hidden) return;
      if (logSubTab.value === 'app') loadAppLogs();
      else loadAuditLogs();
    }, AUTO_REFRESH_MS);
  };

  const toggleAutoRefresh = () => {
    autoRefresh.value = !autoRefresh.value;
    if (autoRefresh.value) startAutoRefresh();
    else stopAutoRefresh();
  };

  // ===== 审计日志 =====
  const auditLogs = ref([]);
  const auditLogsLoading = ref(false);
  const auditLogsError = ref('');
  const auditKeyword = ref('');
  const auditAction = ref('');
  const auditTarget = ref('');
  const auditResult = ref('');
  const auditFrom = ref('');
  const auditTo = ref('');
  const auditPage = ref(0);
  const auditSize = ref(20);
  const auditTotal = ref(0);
  const auditTotalPages = ref(0);

  // 操作类型选项（与后端审计日志里真实出现的 action 对齐；未列出的会原样显示）
  const auditActionOptions = [
    { value: '', label: '全部' },
    { value: 'ADMIN_LIST_USERS', label: '查看用户列表' },
    { value: 'ROLE_CHANGE', label: '角色变更' },
    { value: 'USER_ACTIVE_CHANGE', label: '用户启停' },
    { value: 'USER_DELETE', label: '用户删除' },
    { value: 'COMPONENT_START', label: '组件启动' },
    { value: 'COMPONENT_STOP', label: '组件停止' },
    { value: 'MESSAGE_DELETE_BATCH', label: '批量删除消息' },
    { value: 'MESSAGE_DELETE_BY_TYPES', label: '按类型删除消息' },
    { value: 'GROUP_DELETE_CONVERSATION', label: '删除群会话' },
    { value: 'GROUP_SEND', label: '发送群消息' },
    { value: 'GROUP_SEND_MEDIA', label: '发送群媒体' },
    { value: 'FILE_PURGE', label: '文件清理' },
    { value: 'FILE_DELETE', label: '文件删除' },
    { value: 'BACKUP_DELETE', label: '备份删除' },
    { value: 'CREDIT_ADJUST', label: '积分调整' },
    { value: 'CREDIT_RULE_UPDATE', label: '积分规则变更' },
    { value: 'CASH_MANUAL_ENTRY', label: '手工记账' },
    { value: 'SUBSCRIPTION_PURCHASE', label: '订阅购买' },
    { value: 'USER_REFUND_REQUEST', label: '用户申请退款' },
    { value: 'ADMIN_APPROVE_REFUND', label: '管理员处理退款' },
    { value: 'ADMIN_APPROVE_PAYMENT', label: '管理员确认收款' },
    { value: 'AI_SUMMARY_BATCH', label: 'AI 摘要批量投递' },
    { value: 'AI_SUMMARY_BATCH_STOP', label: 'AI 摘要停止队列' },
    { value: 'AI_SUMMARY_CONFIG_UPDATE', label: 'AI 摘要配置变更' },
    { value: 'AI_SUMMARY_DIGEST_PUSH', label: 'AI 日报推送' },
  ];

  const auditResultOptions = [
    { value: '', label: '全部' },
    { value: 'SUCCESS', label: '成功' },
    { value: 'FAILURE', label: '失败' },
  ];

  let auditSearchTimer = null;

  const loadAuditLogs = async () => {
    auditLogsLoading.value = true;
    auditLogsError.value = '';
    try {
      const res = await adminApi.getAuditLogs({
        keyword: auditKeyword.value.trim() || undefined,
        action: auditAction.value || undefined,
        target: auditTarget.value.trim() || undefined,
        result: auditResult.value || undefined,
        from: auditFrom.value || undefined,
        to: auditTo.value || undefined,
        page: auditPage.value,
        size: auditSize.value,
      });
      const data = res || {};
      auditLogs.value = data.content || [];
      auditTotal.value = data.totalElements || 0;
      auditTotalPages.value = data.totalPages || 0;
    } catch (error) {
      logger.error('加载审计日志失败:', error);
      auditLogsError.value = error.message || '加载审计日志失败';
      auditLogs.value = [];
      auditTotal.value = 0;
      auditTotalPages.value = 0;
    } finally {
      auditLogsLoading.value = false;
    }
  };

  const setAuditAction = (action) => {
    auditAction.value = action || '';
    auditPage.value = 0;
    loadAuditLogs();
  };

  const setAuditResult = (result) => {
    auditResult.value = result || '';
    auditPage.value = 0;
    loadAuditLogs();
  };

  const setAuditRange = ({ from, to }) => {
    if (from !== undefined) auditFrom.value = from;
    if (to !== undefined) auditTo.value = to;
    auditPage.value = 0;
    loadAuditLogs();
  };

  const onAuditKeywordInput = () => {
    if (auditSearchTimer) clearTimeout(auditSearchTimer);
    auditSearchTimer = setTimeout(() => {
      auditPage.value = 0;
      loadAuditLogs();
    }, 350);
  };

  const onAuditTargetInput = () => {
    if (auditSearchTimer) clearTimeout(auditSearchTimer);
    auditSearchTimer = setTimeout(() => {
      auditPage.value = 0;
      loadAuditLogs();
    }, 350);
  };

  const resetAuditFilters = () => {
    auditKeyword.value = '';
    auditAction.value = '';
    auditTarget.value = '';
    auditResult.value = '';
    auditFrom.value = '';
    auditTo.value = '';
    auditPage.value = 0;
    loadAuditLogs();
  };

  const goToAuditPage = (page) => {
    if (page < 0 || (auditTotalPages.value > 0 && page >= auditTotalPages.value)) return;
    auditPage.value = page;
    loadAuditLogs();
  };

  const refreshAuditLogs = () => loadAuditLogs();

  const auditExportUrl = computed(() => adminApi.exportAuditLogsUrl({
    keyword: auditKeyword.value.trim() || undefined,
    action: auditAction.value || undefined,
    target: auditTarget.value.trim() || undefined,
    result: auditResult.value || undefined,
    from: auditFrom.value || undefined,
    to: auditTo.value || undefined,
  }));

  // ===== 子标签切换时按需加载 =====
  const switchSubTab = (tab) => {
    logSubTab.value = tab;
    if (tab === 'app' && appLogs.value.length === 0 && !appLogsLoading.value) {
      loadAppLogs();
    } else if (tab === 'audit' && auditLogs.value.length === 0 && !auditLogsLoading.value) {
      loadAuditLogs();
    }
  };

  // ===== 辅助方法 =====
  const formatTimestamp = (ts) => {
    if (!ts) return '-';
    const date = new Date(ts);
    return date.toLocaleString('zh-CN');
  };

  const actionLabel = (action) => {
    const found = auditActionOptions.find((o) => o.value === action);
    return found ? found.label : action || '-';
  };

  const resultBadgeClass = (result) => {
    if (!result) return '';
    return result.toUpperCase() === 'SUCCESS' ? 'audit-success' : 'audit-failure';
  };

  const levelClass = (level) => {
    if (!level) return '';
    const l = level.toUpperCase();
    if (l === 'ERROR') return 'log-error';
    if (l === 'WARN') return 'log-warn';
    if (l === 'INFO') return 'log-info';
    if (l === 'DEBUG' || l === 'TRACE') return 'log-debug';
    return '';
  };

  /** 从日志行里提取 traceId，便于定位同一次请求的全部日志 */
  const extractTraceId = (line) => {
    const m = /traceId=([0-9a-fA-F-]{8,})/.exec(line || '');
    return m ? m[1] : '';
  };

  const copyText = async (text, label = '内容') => {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      if (showSystemMsg) showSystemMsg(`${label}已复制到剪贴板`);
    } catch (e) {
      if (showSystemMsg) showSystemMsg('复制失败，请手动选中复制', 'error');
    }
  };

  const cleanup = () => {
    if (appSearchTimer) clearTimeout(appSearchTimer);
    if (auditSearchTimer) clearTimeout(auditSearchTimer);
    stopAutoRefresh();
  };

  return {
    logSubTab,
    // 应用日志
    appLogs,
    appLogsLoading,
    appLogsError,
    appLogLevel,
    appLogKeyword,
    appLogFrom,
    appLogTo,
    appLogPage,
    appLogSize,
    appLogTotal,
    appLogTotalPages,
    appLogNote,
    appLogLevelOptions,
    appLogHasFilter,
    appLogExportUrl,
    loadAppLogs,
    setAppLogLevel,
    setAppLogRange,
    onAppLogKeywordInput,
    resetAppLogFilters,
    goToAppLogPage,
    refreshAppLogs,
    // 审计日志
    auditLogs,
    auditLogsLoading,
    auditLogsError,
    auditKeyword,
    auditAction,
    auditTarget,
    auditResult,
    auditFrom,
    auditTo,
    auditPage,
    auditSize,
    auditTotal,
    auditTotalPages,
    auditActionOptions,
    auditResultOptions,
    auditExportUrl,
    loadAuditLogs,
    setAuditAction,
    setAuditResult,
    setAuditRange,
    onAuditKeywordInput,
    onAuditTargetInput,
    resetAuditFilters,
    goToAuditPage,
    refreshAuditLogs,
    // 自动刷新
    autoRefresh,
    toggleAutoRefresh,
    stopAutoRefresh,
    // 通用
    switchSubTab,
    formatTimestamp,
    actionLabel,
    resultBadgeClass,
    levelClass,
    extractTraceId,
    copyText,
    cleanup,
  };
}
