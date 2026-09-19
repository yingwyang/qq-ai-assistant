<template>
  <div class="tab-panel admin-backup">
    <AdminPageHeader title="数据维护" subtitle="数据库备份、下载与历史消息归档">
      <template #meta>
        <span class="page-header-meta">共 {{ backupList.length }} 个备份文件</span>
      </template>
      <button class="btn-action promote" :disabled="isBackingUp" @click="triggerBackup">
        {{ isBackingUp ? '备份中...' : '立即备份' }}
      </button>
      <button class="btn-action" :disabled="backupListLoading" @click="loadBackupList">
        {{ backupListLoading ? '刷新中...' : '刷新列表' }}
      </button>
    </AdminPageHeader>

    <div class="notice-bar">
      备份写入后端 <code>backups/</code> 目录；归档只把消息标记为「已归档」，不会删除数据。
      AI 摘要的设置与批量补摘要已移到左侧「系统 → AI 摘要」。
    </div>

    <!-- 数据库备份 -->
    <div class="section-card">
      <div class="maintenance-section-header">
        <h4>数据库备份</h4>
      </div>
      <div v-if="backupListLoading && backupList.length === 0" class="state-box">加载备份列表...</div>
      <div v-else-if="backupList.length === 0" class="state-box">
        <span class="state-title">暂无备份文件</span>
        <span>点击右上角「立即备份」生成第一份</span>
      </div>
      <div v-else class="backup-list">
        <div v-for="file in backupList" :key="file.fileName" class="backup-item">
          <div class="backup-info">
            <span class="backup-name">{{ file.fileName }}</span>
            <span class="backup-meta">{{ formatFileSize(file.size) }} · {{ formatDate(new Date(file.lastModified)) }}</span>
          </div>
          <div class="backup-actions">
            <a :href="getDownloadUrl(file.fileName)" class="btn-action promote" download>下载</a>
            <button
              class="btn-action delete"
              :disabled="deletingBackup === file.fileName"
              @click="deleteBackup(file)"
            >{{ deletingBackup === file.fileName ? '删除中...' : '删除' }}</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 消息归档 -->
    <div class="section-card" style="margin-top: 16px;">
      <div class="maintenance-section-header">
        <h4>消息归档</h4>
        <button class="btn-action" :disabled="archivePreviewLoading" @click="previewArchive">
          {{ archivePreviewLoading ? '预检中...' : '预检' }}
        </button>
      </div>
      <p class="maintenance-desc">将指定天数之前的消息标记为已归档，归档后的消息不再在聊天界面中显示（数据不会删除）。</p>

      <div class="archive-form">
        <div class="archive-input-group">
          <label>归档多少天前的消息</label>
          <input
            type="number"
            :value="archiveDays"
            min="1"
            max="3650"
            class="config-input archive-input"
            @change="setArchiveDays($event.target.value)"
          />
          <span class="archive-unit">天</span>
        </div>
        <button class="btn-action promote" :disabled="isArchiving" @click="triggerArchive">{{ isArchiving ? '归档中...' : '执行归档' }}</button>
      </div>

      <div v-if="archivePreview" class="archive-preview">
        <div class="archive-preview-item">
          <span class="archive-preview-value">{{ archivePreview.messageCount }}</span>
          <span class="archive-preview-label">将被归档的消息</span>
        </div>
        <div class="archive-preview-item">
          <span class="archive-preview-value">{{ formatDate(archivePreview.cutoff) }}</span>
          <span class="archive-preview-label">时间分界点</span>
        </div>
        <div class="archive-preview-item">
          <span class="archive-preview-value">{{ archivePreview.existingArchiveFiles }}</span>
          <span class="archive-preview-label">已有归档文件</span>
        </div>
        <div class="archive-preview-item">
          <span class="archive-preview-value">{{ formatFileSize(archivePreview.existingArchiveBytes) }}</span>
          <span class="archive-preview-label">归档目录占用</span>
        </div>
      </div>
      <p v-else class="maintenance-desc archive-preview-hint">
        点击「预检」可先看到将被归档的消息条数与归档目录现状，再决定是否执行。
      </p>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';

export default {
  name: 'AdminBackup',
  components: { AdminPageHeader },
  setup() {
    const maintenance = inject('adminMaintenance');
    const formatDate = inject('adminFormatDate');
    const formatFileSize = inject('adminFormatFileSize');

    return {
      isBackingUp: maintenance.isBackingUp,
      backupList: maintenance.backupList,
      backupListLoading: maintenance.backupListLoading,
      deletingBackup: maintenance.deletingBackup,
      isArchiving: maintenance.isArchiving,
      archiveDays: maintenance.archiveDays,
      archivePreview: maintenance.archivePreview,
      archivePreviewLoading: maintenance.archivePreviewLoading,
      triggerBackup: maintenance.triggerBackup,
      loadBackupList: maintenance.loadBackupList,
      getDownloadUrl: maintenance.getDownloadUrl,
      deleteBackup: maintenance.deleteBackup,
      triggerArchive: maintenance.triggerArchive,
      previewArchive: maintenance.previewArchive,
      setArchiveDays: maintenance.setArchiveDays,
      formatDate,
      formatFileSize,
    };
  },
};
</script>

<style scoped>
.maintenance-section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.maintenance-section-header h4 { margin: 0; }
.maintenance-desc { font-size: 13px; color: var(--text-muted, #888); margin: 0 0 14px 0; }
.backup-list { display: flex; flex-direction: column; gap: 8px; }
.backup-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; background: var(--bg-tertiary, #f8f9fa); border-radius: 6px; gap: 12px; }
.backup-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.backup-name { font-size: 13px; font-weight: 500; color: var(--text-primary, #333); word-break: break-all; }
.backup-meta { font-size: 11px; color: var(--text-muted, #999); }
.backup-actions { display: flex; gap: 8px; flex-shrink: 0; }
.archive-form { display: flex; align-items: flex-end; gap: 14px; }
.archive-input-group { display: flex; flex-direction: column; gap: 4px; }
.archive-input-group label { font-size: 12px; color: var(--text-muted, #888); }
.archive-input { width: 100px; }
.archive-unit { font-size: 13px; color: var(--text-secondary, #666); align-self: flex-end; margin-bottom: 6px; }
.archive-preview { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-top: 16px; }
.archive-preview-item { display: flex; flex-direction: column; gap: 4px; padding: 12px 14px; background: var(--bg-tertiary, #f8f9fa); border: 1px solid var(--border-color, #eceff3); border-radius: 8px; }
.archive-preview-value { font-size: 16px; font-weight: 600; color: var(--text-primary, #333); }
.archive-preview-label { font-size: 11.5px; color: var(--text-muted, #888); }
.archive-preview-hint { margin-top: 12px; }
</style>
