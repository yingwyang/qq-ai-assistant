<template>
  <div class="tab-panel admin-backup">
    <div class="panel-title">
      <Icon name="file" :size="20" />
      <h2>数据维护</h2>
    </div>

    <!-- 数据库备份 -->
    <div class="section-card">
      <div class="maintenance-section-header">
        <h4>数据库备份</h4>
        <button class="btn-action promote" :disabled="isBackingUp" @click="triggerBackup">{{ isBackingUp ? '备份中...' : '立即备份' }}</button>
      </div>
      <div class="backup-list">
        <div v-if="backupList.length === 0" class="empty-backup"><p>暂无备份文件</p></div>
        <div v-for="file in backupList" :key="file.fileName" class="backup-item">
          <div class="backup-info">
            <span class="backup-name">{{ file.fileName }}</span>
            <span class="backup-meta">{{ formatFileSize(file.size) }} · {{ formatDate(new Date(file.lastModified)) }}</span>
          </div>
          <a :href="getDownloadUrl(file.fileName)" class="btn-action promote" download>下载</a>
        </div>
      </div>
    </div>

    <!-- 消息归档 -->
    <div class="section-card" style="margin-top: 16px;">
      <div class="maintenance-section-header"><h4>消息归档</h4></div>
      <p class="maintenance-desc">将指定天数之前的消息标记为已归档，归档后的消息不再在聊天界面中显示。</p>
      <div class="archive-form">
        <div class="archive-input-group">
          <label>归档多少天前的消息</label>
          <input type="number" v-model.number="archiveDays" min="1" max="3650" class="config-input archive-input" />
          <span class="archive-unit">天</span>
        </div>
        <button class="btn-action promote" :disabled="isArchiving" @click="triggerArchive">{{ isArchiving ? '归档中...' : '执行归档' }}</button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';

export default {
  name: 'AdminBackup',
  components: { Icon },
  setup() {
    const maintenance = inject('adminMaintenance');
    const formatDate = inject('adminFormatDate');
    const formatFileSize = inject('adminFormatFileSize');
    return {
      isBackingUp: maintenance.isBackingUp,
      backupList: maintenance.backupList,
      isArchiving: maintenance.isArchiving,
      archiveDays: maintenance.archiveDays,
      triggerBackup: maintenance.triggerBackup,
      getDownloadUrl: maintenance.getDownloadUrl,
      triggerArchive: maintenance.triggerArchive,
      formatDate,
      formatFileSize,
    };
  },
};
</script>

<style scoped>
.maintenance-section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.maintenance-section-header h4 { margin: 0; }
.maintenance-desc { font-size: 13px; color: #888; margin: 0 0 14px 0; }
.backup-list { display: flex; flex-direction: column; gap: 8px; }
.empty-backup { text-align: center; padding: 30px; color: #aaa; font-size: 13px; }
.backup-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; background: var(--bg-tertiary, #f8f9fa); border-radius: 6px; transition: background 0.2s; }
.backup-item:hover { background: #f0f1f3; }
.backup-info { display: flex; flex-direction: column; gap: 2px; }
.backup-name { font-size: 13px; font-weight: 500; color: var(--text-primary, #333); }
.backup-meta { font-size: 11px; color: #999; }
.archive-form { display: flex; align-items: flex-end; gap: 14px; }
.archive-input-group { display: flex; flex-direction: column; gap: 4px; }
.archive-input-group label { font-size: 12px; color: #888; }
.archive-input { width: 100px; }
.archive-unit { font-size: 13px; color: #666; align-self: flex-end; margin-bottom: 6px; }
</style>