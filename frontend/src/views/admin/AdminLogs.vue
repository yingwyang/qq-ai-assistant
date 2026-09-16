<template>
  <div class="tab-panel admin-logs">
    <div class="panel-title">
      <Icon name="file" :size="20" />
      <h2>系统日志</h2>
    </div>

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
        <button class="btn-action promote" @click="refreshAppLogs">刷新</button>
      </div>

      <div v-if="appLogsError" class="log-error-msg">{{ appLogsError }}</div>

      <div class="log-viewer-wrapper">
        <div v-if="appLogsLoading" class="log-loading">加载中...</div>
        <div v-else-if="appLogs.length === 0" class="log-empty">暂无日志</div>
        <div v-else class="log-viewer">
          <div v-for="(entry, index) in appLogs" :key="index" class="log-line" :class="levelClass(entry.level)">
            <span class="log-level-tag">{{ entry.level }}</span>
            <span class="log-content">{{ entry.message }}</span>
          </div>
        </div>
      </div>

      <div v-if="appLogTotalPages > 1" class="log-pagination">
        <button class="btn-action promote" :disabled="appLogPage <= 0" @click="goToAppLogPage(appLogPage - 1)">上一页</button>
        <span class="page-info">第 {{ appLogPage + 1 }} / {{ appLogTotalPages }} 页（共 {{ appLogTotal }} 条）</span>
        <button class="btn-action promote" :disabled="appLogPage >= appLogTotalPages - 1" @click="goToAppLogPage(appLogPage + 1)">下一页</button>
      </div>
    </div>

    <!-- 审计日志 -->
    <div v-if="logSubTab === 'audit'" class="log-section">
      <div class="log-toolbar">
        <input type="text" class="audit-search-input" v-model="auditKeyword" placeholder="按操作人搜索..." @input="onAuditKeywordInput" />
        <select class="audit-action-select" :value="auditAction" @change="setAuditAction($event.target.value)">
          <option v-for="opt in auditActionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
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
            <tr v-if="auditLogsLoading"><td colspan="6" class="audit-loading">加载中...</td></tr>
            <tr v-else-if="auditLogs.length === 0"><td colspan="6" class="audit-empty">暂无审计日志</td></tr>
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

      <div v-if="auditTotalPages > 1" class="log-pagination">
        <button class="btn-action promote" :disabled="auditPage <= 0" @click="goToAuditPage(auditPage - 1)">上一页</button>
        <span class="page-info">第 {{ auditPage + 1 }} / {{ auditTotalPages }} 页（共 {{ auditTotal }} 条）</span>
        <button class="btn-action promote" :disabled="auditPage >= auditTotalPages - 1" @click="goToAuditPage(auditPage + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';

export default {
  name: 'AdminLogs',
  components: { Icon },
  setup() {
    const systemLog = inject('adminSystemLog');
    return {
      logSubTab: systemLog.logSubTab,
      appLogs: systemLog.appLogs,
      appLogsLoading: systemLog.appLogsLoading,
      appLogsError: systemLog.appLogsError,
      appLogLevel: systemLog.appLogLevel,
      appLogPage: systemLog.appLogPage,
      appLogTotal: systemLog.appLogTotal,
      appLogTotalPages: systemLog.appLogTotalPages,
      appLogLevelOptions: systemLog.appLogLevelOptions,
      setAppLogLevel: systemLog.setAppLogLevel,
      goToAppLogPage: systemLog.goToAppLogPage,
      refreshAppLogs: systemLog.refreshAppLogs,
      auditLogs: systemLog.auditLogs,
      auditLogsLoading: systemLog.auditLogsLoading,
      auditLogsError: systemLog.auditLogsError,
      auditKeyword: systemLog.auditKeyword,
      auditAction: systemLog.auditAction,
      auditPage: systemLog.auditPage,
      auditTotal: systemLog.auditTotal,
      auditTotalPages: systemLog.auditTotalPages,
      auditActionOptions: systemLog.auditActionOptions,
      setAuditAction: systemLog.setAuditAction,
      onAuditKeywordInput: systemLog.onAuditKeywordInput,
      goToAuditPage: systemLog.goToAuditPage,
      refreshAuditLogs: systemLog.refreshAuditLogs,
      switchSubTab: systemLog.switchSubTab,
      formatTimestamp: systemLog.formatTimestamp,
      actionLabel: systemLog.actionLabel,
      resultBadgeClass: systemLog.resultBadgeClass,
      levelClass: systemLog.levelClass,
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
.log-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.log-level-filter { display: flex; gap: 6px; }
.log-level-btn { padding: 4px 12px; background: var(--bg-tertiary, #f5f6fa); border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; cursor: pointer; font-size: 12px; color: var(--text-secondary, #666); transition: all 0.2s; }
.log-level-btn:hover { background: var(--bg-tertiary, #e9ecef); }
.log-level-btn.active { color: #fff; border-color: var(--accent-color, #3498db); }
.log-level-btn.active.level-all { background: #2c3e50; }
.log-level-btn.active.level-info { background: #17a2b8; }
.log-level-btn.active.level-warn { background: #ffc107; color: var(--text-primary, #333); }
.log-level-btn.active.level-error { background: #dc3545; }
.log-level-btn.active.level-debug { background: #6c757d; }
.log-viewer-wrapper { background: #1e1e1e; border-radius: 6px; min-height: 300px; max-height: 600px; overflow: auto; padding: 12px; }
.log-viewer { font-family: 'Consolas', 'Monaco', 'Courier New', monospace; font-size: 12px; line-height: 1.6; }
.log-line { display: flex; gap: 8px; color: #d4d4d4; padding: 1px 0; word-break: break-all; }
.log-line.log-error { color: #f48771; }
.log-line.log-warn { color: #cca700; }
.log-line.log-info { color: #75beff; }
.log-line.log-debug { color: var(--text-muted, #888); }
.log-level-tag { flex-shrink: 0; min-width: 50px; font-weight: 600; text-align: center; padding: 0 4px; border-radius: 2px; background: rgba(255, 255, 255, 0.1); height: 18px; line-height: 18px; align-self: flex-start; margin-top: 1px; }
.log-content { flex: 1; white-space: pre-wrap; }
.log-loading, .log-empty { color: var(--text-muted, #888); text-align: center; padding: 40px 0; }
.log-error-msg { color: #dc3545; padding: 8px 12px; background: #fff5f5; border-radius: 4px; margin-bottom: 12px; font-size: 13px; }
.log-pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 12px; }
.log-pagination .page-info { font-size: 13px; color: var(--text-secondary, #666); }
.audit-search-input, .audit-action-select { padding: 6px 10px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; font-size: 13px; outline: none; }
.audit-search-input { flex: 1; min-width: 180px; max-width: 300px; }
.audit-action-select { min-width: 140px; cursor: pointer; }
.audit-table-wrapper { overflow-x: auto; border: 1px solid var(--border-color, #e0e0e0); border-radius: 6px; }
.audit-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.audit-table thead { background: var(--bg-tertiary, #f5f6fa); }
.audit-table th { padding: 10px 12px; text-align: left; font-weight: 600; color: var(--text-primary, #333); border-bottom: 1px solid var(--border-color, #e0e0e0); white-space: nowrap; }
.audit-table td { padding: 8px 12px; border-bottom: 1px solid var(--border-color, #f0f0f0); color: var(--text-primary, #333); vertical-align: top; }
.audit-table tbody tr:hover { background: var(--bg-tertiary, #fafbfc); }
.audit-loading, .audit-empty { text-align: center; color: var(--text-muted, #888); padding: 30px 0; }
.audit-action-tag { display: inline-block; padding: 2px 8px; background: #e3f2fd; color: #1976d2; border-radius: 10px; font-size: 12px; white-space: nowrap; }
.audit-target { font-family: 'Consolas', 'Monaco', monospace; font-size: 12px; color: var(--text-secondary, #666); max-width: 200px; word-break: break-all; }
.audit-result-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 600; }
.audit-success { background: #e8f5e9; color: #2e7d32; }
.audit-failure { background: #ffebee; color: #c62828; }
.audit-detail { max-width: 300px; word-break: break-all; color: var(--text-secondary, #666); }
</style>