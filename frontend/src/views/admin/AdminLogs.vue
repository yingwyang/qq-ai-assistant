<template>
  <div class="tab-panel admin-logs">
    <AdminPageHeader title="系统日志" subtitle="应用运行日志与管理员操作审计">
      <template #meta>
        <span v-if="autoRefresh" class="restart-badge">自动刷新中（5s）</span>
      </template>
      <button class="btn-action" @click="toggleAutoRefresh">
        {{ autoRefresh ? '停止自动刷新' : '自动刷新' }}
      </button>
      <a class="btn-action promote" :href="logSubTab === 'app' ? appLogExportUrl : auditExportUrl" download>导出</a>
    </AdminPageHeader>

    <!-- 子标签切换 -->
    <div class="log-sub-tabs">
      <button class="log-sub-tab" :class="{ active: logSubTab === 'app' }" @click="switchSubTab('app')">应用日志</button>
      <button class="log-sub-tab" :class="{ active: logSubTab === 'audit' }" @click="switchSubTab('audit')">审计日志</button>
    </div>

    <!-- 应用日志 -->
    <div v-if="logSubTab === 'app'" class="log-section">
      <div class="log-toolbar">
        <div class="log-level-filter">
          <button v-for="opt in appLogLevelOptions" :key="opt.value" class="log-level-btn" :class="[{ active: appLogLevel === opt.value }, 'level-' + opt.value.toLowerCase()]" @click="setAppLogLevel(opt.value)">{{ opt.label }}</button>
        </div>
        <input
          v-model="appLogKeyword"
          type="text"
          class="audit-search-input"
          placeholder="按关键字过滤（如 traceId / 类名 / 报错内容）"
          @input="onAppLogKeywordInput"
        />
        <label class="log-date-field">
          <span>起</span>
          <input type="date" v-model="appLogFrom" @change="setAppLogRange({ from: appLogFrom })" />
        </label>
        <label class="log-date-field">
          <span>止</span>
          <input type="date" v-model="appLogTo" @change="setAppLogRange({ to: appLogTo })" />
        </label>
        <button v-if="appLogHasFilter" class="btn-action" @click="resetAppLogFilters">清空筛选</button>
        <div class="log-toolbar-spacer"></div>
        <button class="btn-action promote" @click="refreshAppLogs">刷新</button>
      </div>

      <p class="log-limit-note">
        只检索日志文件的<strong>最近 {{ APP_LOG_MAX_LINES }} 行</strong>（导出同样受此上限约束），更早的历史请直接查看服务器上的日志文件。
      </p>

      <div v-if="appLogsError" class="log-error-msg">{{ appLogsError }}</div>
      <div v-else-if="appLogNote" class="log-error-msg is-info">{{ appLogNote }}</div>

      <div class="log-viewer-wrapper">
        <div v-if="appLogsLoading" class="log-loading">加载中...</div>
        <div v-else-if="appLogs.length === 0" class="log-empty">
          {{ appLogHasFilter ? '当前筛选条件下没有匹配的日志' : '暂无日志' }}
        </div>
        <div v-else class="log-viewer">
          <div v-for="(entry, index) in appLogs" :key="index" class="log-line" :class="levelClass(entry.level)">
            <span class="log-level-tag">{{ entry.level }}</span>
            <span class="log-content">{{ entry.message }}</span>
            <button class="log-copy-btn" title="复制这一行" @click="copyText(entry.message, '日志行')">复制</button>
          </div>
        </div>
      </div>

      <div class="log-pagination">
        <span class="page-info">共 {{ appLogTotal }} 条 · 第 {{ appLogPage + 1 }} / {{ Math.max(1, appLogTotalPages) }} 页</span>
        <button class="btn-action promote" :disabled="appLogPage <= 0" @click="goToAppLogPage(appLogPage - 1)">上一页</button>
        <button class="btn-action promote" :disabled="appLogPage >= appLogTotalPages - 1" @click="goToAppLogPage(appLogPage + 1)">下一页</button>
      </div>
    </div>

    <!-- 审计日志 -->
    <div v-if="logSubTab === 'audit'" class="log-section">
      <div class="log-toolbar">
        <input type="text" class="audit-search-input" v-model="auditKeyword" placeholder="按操作人搜索..." @input="onAuditKeywordInput" />
        <input type="text" class="audit-search-input" v-model="auditTarget" placeholder="按操作目标搜索..." @input="onAuditTargetInput" />
        <select class="audit-action-select" :value="auditAction" @change="setAuditAction($event.target.value)">
          <option v-for="opt in auditActionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <select class="audit-action-select" :value="auditResult" @change="setAuditResult($event.target.value)">
          <option v-for="opt in auditResultOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <label class="log-date-field">
          <span>起</span>
          <input type="date" v-model="auditFrom" @change="setAuditRange({ from: auditFrom })" />
        </label>
        <label class="log-date-field">
          <span>止</span>
          <input type="date" v-model="auditTo" @change="setAuditRange({ to: auditTo })" />
        </label>
        <button class="btn-action" @click="resetAuditFilters">清空筛选</button>
        <div class="log-toolbar-spacer"></div>
        <button class="btn-action promote" @click="refreshAuditLogs">刷新</button>
      </div>

      <div v-if="auditLogsError" class="log-error-msg">{{ auditLogsError }}</div>

      <div class="audit-table-wrapper">
        <table class="audit-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>操作人</th>
              <th>操作类型</th>
              <th>目标</th>
              <th>结果</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="auditLogsLoading"><td colspan="6"><StatePanel state="loading" compact /></td></tr>
            <tr v-else-if="auditLogs.length === 0"><td colspan="6"><StatePanel state="empty" title="暂无审计日志" compact /></td></tr>
            <tr v-for="log in auditLogs" :key="log.id">
              <td>{{ formatTimestamp(log.timestamp) }}</td>
              <td>{{ log.username || '-' }}</td>
              <td><span class="audit-action-tag">{{ actionLabel(log.action) }}</span></td>
              <td class="audit-target">{{ log.target || '-' }}</td>
              <td><span class="audit-result-badge" :class="resultBadgeClass(log.result)">{{ log.result || '-' }}</span></td>
              <td class="audit-detail">{{ log.detail || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="log-pagination">
        <span class="page-info">共 {{ auditTotal }} 条 · 第 {{ auditPage + 1 }} / {{ Math.max(1, auditTotalPages) }} 页</span>
        <button class="btn-action promote" :disabled="auditPage <= 0" @click="goToAuditPage(auditPage - 1)">上一页</button>
        <button class="btn-action promote" :disabled="auditPage >= auditTotalPages - 1" @click="goToAuditPage(auditPage + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject, onMounted, onUnmounted } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { APP_LOG_MAX_LINES } from '../../composables/useSystemLog';
import StatePanel from '../../components/common/StatePanel.vue';

export default {
  name: 'AdminLogs',
  components: { Icon, AdminPageHeader, StatePanel },
  setup() {
    const systemLog = inject('adminSystemLog');

    // 直接进入本页时也要拉一次数据：以前只有点击子标签才会加载，
    // 导致首次打开「系统日志」永远显示"暂无日志"（要先点一下子标签才出数据）。
    onMounted(() => {
      systemLog.switchSubTab(systemLog.logSubTab.value);
    });
    onUnmounted(() => {
      systemLog.stopAutoRefresh();
    });

    return {
      APP_LOG_MAX_LINES,
      logSubTab: systemLog.logSubTab,
      appLogs: systemLog.appLogs,
      appLogsLoading: systemLog.appLogsLoading,
      appLogsError: systemLog.appLogsError,
      appLogLevel: systemLog.appLogLevel,
      appLogKeyword: systemLog.appLogKeyword,
      appLogFrom: systemLog.appLogFrom,
      appLogTo: systemLog.appLogTo,
      appLogPage: systemLog.appLogPage,
      appLogTotal: systemLog.appLogTotal,
      appLogTotalPages: systemLog.appLogTotalPages,
      appLogNote: systemLog.appLogNote,
      appLogLevelOptions: systemLog.appLogLevelOptions,
      appLogHasFilter: systemLog.appLogHasFilter,
      appLogExportUrl: systemLog.appLogExportUrl,
      setAppLogLevel: systemLog.setAppLogLevel,
      setAppLogRange: systemLog.setAppLogRange,
      onAppLogKeywordInput: systemLog.onAppLogKeywordInput,
      resetAppLogFilters: systemLog.resetAppLogFilters,
      goToAppLogPage: systemLog.goToAppLogPage,
      refreshAppLogs: systemLog.refreshAppLogs,
      auditLogs: systemLog.auditLogs,
      auditLogsLoading: systemLog.auditLogsLoading,
      auditLogsError: systemLog.auditLogsError,
      auditKeyword: systemLog.auditKeyword,
      auditAction: systemLog.auditAction,
      auditTarget: systemLog.auditTarget,
      auditResult: systemLog.auditResult,
      auditFrom: systemLog.auditFrom,
      auditTo: systemLog.auditTo,
      auditPage: systemLog.auditPage,
      auditTotal: systemLog.auditTotal,
      auditTotalPages: systemLog.auditTotalPages,
      auditActionOptions: systemLog.auditActionOptions,
      auditResultOptions: systemLog.auditResultOptions,
      auditExportUrl: systemLog.auditExportUrl,
      setAuditAction: systemLog.setAuditAction,
      setAuditResult: systemLog.setAuditResult,
      setAuditRange: systemLog.setAuditRange,
      onAuditKeywordInput: systemLog.onAuditKeywordInput,
      onAuditTargetInput: systemLog.onAuditTargetInput,
      resetAuditFilters: systemLog.resetAuditFilters,
      goToAuditPage: systemLog.goToAuditPage,
      refreshAuditLogs: systemLog.refreshAuditLogs,
      autoRefresh: systemLog.autoRefresh,
      toggleAutoRefresh: systemLog.toggleAutoRefresh,
      switchSubTab: systemLog.switchSubTab,
      formatTimestamp: systemLog.formatTimestamp,
      actionLabel: systemLog.actionLabel,
      resultBadgeClass: systemLog.resultBadgeClass,
      levelClass: systemLog.levelClass,
      copyText: systemLog.copyText,
    };
  },
};
</script>

<style scoped>
.log-sub-tabs { display: flex; gap: 8px; margin-bottom: 16px; border-bottom: 1px solid var(--border-color, #e0e0e0); }
.log-sub-tab { padding: 8px 20px; background: transparent; border: none; border-bottom: 2px solid transparent; color: var(--text-secondary, #666); cursor: pointer; font-size: 14px; transition: all 0.2s; }
.log-sub-tab:hover { color: var(--text-primary, #333); }
.log-sub-tab.active { color: var(--text-primary, #333); border-bottom-color: var(--accent-color, #3498db); font-weight: 600; }
.log-section { background: var(--card-bg, #fff); border-radius: 8px; padding: 16px; box-shadow: 0 1px 3px var(--card-shadow, rgba(0, 0, 0, 0.06)); }
.log-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.log-toolbar-spacer { flex: 1; }
.log-level-filter { display: flex; gap: 6px; }
.log-level-btn { padding: 4px 12px; background: var(--bg-tertiary, #f5f6fa); border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; cursor: pointer; font-size: 12px; color: var(--text-secondary, #666); transition: all 0.2s; }
.log-level-btn:hover { background: var(--bg-tertiary, #e9ecef); }
.log-level-btn.active { color: #fff; border-color: var(--accent-color, #3498db); }
.log-level-btn.active.level-all { background: #2c3e50; }
.log-level-btn.active.level-info { background: #17a2b8; }
.log-level-btn.active.level-warn { background: #ffc107; color: var(--text-primary, #333); }
.log-level-btn.active.level-error { background: #dc3545; }
.log-level-btn.active.level-debug { background: #6c757d; }
.log-date-field { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-muted, #888); }
.log-date-field input { padding: 5px 8px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; background: var(--input-bg, #fff); color: var(--text-primary, #333); font-size: 12px; }
.log-limit-note { font-size: 12px; color: var(--text-muted, #888); margin: 0 0 12px 0; }
.log-viewer-wrapper { background: #1e1e1e; border-radius: 6px; min-height: 300px; max-height: 620px; overflow: auto; padding: 12px; }
.log-viewer { font-family: 'Consolas', 'Monaco', 'Courier New', monospace; font-size: 12px; line-height: 1.6; }
.log-line { display: flex; gap: 8px; align-items: flex-start; color: #d4d4d4; padding: 1px 0; word-break: break-all; }
.log-line:hover { background: rgba(255, 255, 255, 0.04); }
.log-line.log-error { color: #f48771; }
.log-line.log-warn { color: #cca700; }
.log-line.log-info { color: #75beff; }
.log-line.log-debug { color: #9a9a9a; }
.log-level-tag { flex-shrink: 0; min-width: 50px; font-weight: 600; text-align: center; padding: 0 4px; border-radius: 2px; background: rgba(255, 255, 255, 0.1); height: 18px; line-height: 18px; align-self: flex-start; margin-top: 1px; }
.log-content { flex: 1; white-space: pre-wrap; }
.log-copy-btn { flex-shrink: 0; opacity: 0; background: rgba(255, 255, 255, 0.12); border: none; color: #d4d4d4; font-size: 11px; padding: 1px 6px; border-radius: 3px; cursor: pointer; transition: opacity 0.15s; }
.log-line:hover .log-copy-btn { opacity: 1; }
.log-loading, .log-empty { color: var(--text-muted, #888); text-align: center; padding: 40px 0; }
.log-error-msg { color: #dc3545; padding: 8px 12px; background: #fff5f5; border-radius: 4px; margin-bottom: 12px; font-size: 13px; }
.log-error-msg.is-info { color: var(--text-secondary, #666); background: var(--bg-tertiary, #f5f6fa); }
.theme-dark .log-error-msg { color: #ef9a9a; background: rgba(198, 40, 40, 0.16); }
.theme-dark .log-error-msg.is-info { color: var(--text-secondary, #b0b0b0); background: rgba(255, 255, 255, 0.06); }
.log-pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 12px; }
.log-pagination .page-info { font-size: 13px; color: var(--text-secondary, #666); }
.audit-search-input, .audit-action-select { padding: 6px 10px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; background: var(--input-bg, #fff); color: var(--text-primary, #333); font-size: 13px; outline: none; }
.audit-search-input { flex: 1; min-width: 180px; max-width: 260px; }
.audit-action-select { min-width: 130px; cursor: pointer; }
.audit-table-wrapper { overflow-x: auto; border: 1px solid var(--border-color, #e0e0e0); border-radius: 6px; }
.audit-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.audit-table thead { background: var(--bg-tertiary, #f5f6fa); }
.audit-table th { padding: 10px 12px; text-align: left; font-weight: 600; color: var(--text-primary, #333); border-bottom: 1px solid var(--border-color, #e0e0e0); white-space: nowrap; }
.audit-table td { padding: 8px 12px; border-bottom: 1px solid var(--border-color, #f0f0f0); color: var(--text-primary, #333); vertical-align: top; }
.audit-table tbody tr:hover { background: var(--bg-tertiary, #fafbfc); }
.audit-action-tag { display: inline-block; padding: 2px 8px; background: #e3f2fd; color: #1976d2; border-radius: 10px; font-size: 12px; white-space: nowrap; }
.theme-dark .audit-action-tag { background: rgba(25, 118, 210, 0.22); color: #64b5f6; }
.audit-target { font-family: 'Consolas', 'Monaco', monospace; font-size: 12px; color: var(--text-secondary, #666); max-width: 200px; word-break: break-all; }
.audit-result-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 600; }
.audit-success { background: #e8f5e9; color: #2e7d32; }
.audit-failure { background: #ffebee; color: #c62828; }
.theme-dark .audit-success { background: rgba(46, 125, 50, 0.22); color: #a5d6a7; }
.theme-dark .audit-failure { background: rgba(198, 40, 40, 0.22); color: #ef9a9a; }
.audit-detail { max-width: 300px; word-break: break-all; color: var(--text-secondary, #666); }
</style>
