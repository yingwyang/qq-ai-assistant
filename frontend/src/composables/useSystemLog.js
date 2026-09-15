import { ref } from 'vue';
import { adminApi } from '../services/api';
import logger from '../utils/logger';

/**
 * 系统日志管理 composable
 * - 应用日志：列表、级别过滤、分页
 * - 审计日志：列表、搜索、分页
 * - 两个子标签切换
 */
export function useSystemLog({ showSystemMsg } = {}) {
  // 子标签：app / audit
  const logSubTab = ref('app');

  // ===== 应用日志 =====
  const appLogs = ref([]);
  const appLogsLoading = ref(false);
  const appLogsError = ref('');
  const appLogLevel = ref('ALL');
  const appLogPage = ref(0);
  const appLogSize = ref(50);
  const appLogTotal = ref(0);
  const appLogTotalPages = ref(0);

  const appLogLevelOptions = [
    { value: 'ALL', label: '全部' },
    { value: 'INFO', label: 'INFO' },
    { value: 'WARN', label: 'WARN' },
    { value: 'ERROR', label: 'ERROR' },
    { value: 'DEBUG', label: 'DEBUG' },
  ];

  const loadAppLogs = async () => {
    appLogsLoading.value = true;
    appLogsError.value = '';
    try {
      const res = await adminApi.getLogs({
        level: appLogLevel.value,
        page: appLogPage.value,
        size: appLogSize.value,
      });
      const data = res || {};
      appLogs.value = data.content || [];
      appLogTotal.value = data.totalElements || 0;
      appLogTotalPages.value = data.totalPages || 0;
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

  const goToAppLogPage = (page) => {
    if (page < 0 || (appLogTotalPages.value > 0 && page >= appLogTotalPages.value)) return;
    appLogPage.value = page;
    loadAppLogs();
  };

  const refreshAppLogs = () => loadAppLogs();

  // ===== 审计日志 =====
  const auditLogs = ref([]);
  const auditLogsLoading = ref(false);
  const auditLogsError = ref('');
  const auditKeyword = ref('');
  const auditAction = ref('');
  const auditPage = ref(0);
  const auditSize = ref(20);
  const auditTotal = ref(0);
  const auditTotalPages = ref(0);

  // 操作类型选项（与后端约定）
  const auditActionOptions = [
    { value: '', label: '全部' },
    { value: 'ROLE_CHANGE', label: '角色变更' },
    { value: 'USER_ACTIVE_CHANGE', label: '用户启停' },
    { value: 'USER_DELETE', label: '用户删除' },
    { value: 'COMPONENT_START', label: '组件启动' },
    { value: 'COMPONENT_STOP', label: '组件停止' },
    { value: 'MESSAGE_DELETE_BATCH', label: '批量删除消息' },
    { value: 'MESSAGE_DELETE_BY_TYPES', label: '按类型删除消息' },
    { value: 'FILE_PURGE', label: '文件清理' },
    { value: 'FILE_DELETE', label: '文件删除' },
  ];

  let auditSearchTimer = null;

  const loadAuditLogs = async () => {
    auditLogsLoading.value = true;
    auditLogsError.value = '';
    try {
      const res = await adminApi.getAuditLogs({
        keyword: auditKeyword.value,
        action: auditAction.value,
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

  const onAuditKeywordInput = () => {
    if (auditSearchTimer) clearTimeout(auditSearchTimer);
    auditSearchTimer = setTimeout(() => {
      auditPage.value = 0;
      loadAuditLogs();
    }, 350);
  };

  const goToAuditPage = (page) => {
    if (page < 0 || (auditTotalPages.value > 0 && page >= auditTotalPages.value)) return;
    auditPage.value = page;
    loadAuditLogs();
  };

  const refreshAuditLogs = () => loadAuditLogs();

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

  return {
    logSubTab,
    // 应用日志
    appLogs,
    appLogsLoading,
    appLogsError,
    appLogLevel,
    appLogPage,
    appLogSize,
    appLogTotal,
    appLogTotalPages,
    appLogLevelOptions,
    loadAppLogs,
    setAppLogLevel,
    goToAppLogPage,
    refreshAppLogs,
    // 审计日志
    auditLogs,
    auditLogsLoading,
    auditLogsError,
    auditKeyword,
    auditAction,
    auditPage,
    auditSize,
    auditTotal,
    auditTotalPages,
    auditActionOptions,
    loadAuditLogs,
    setAuditAction,
    onAuditKeywordInput,
    goToAuditPage,
    refreshAuditLogs,
    // 通用
    switchSubTab,
    formatTimestamp,
    actionLabel,
    resultBadgeClass,
    levelClass,
  };
}
