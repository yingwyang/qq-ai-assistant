<template>
  <div class="tab-panel admin-media">
    <div class="panel-title">
      <Icon name="image" :size="20" />
      <h2>媒体文件管理</h2>
    </div>
    <div class="section-card">
      <div class="section-card-header">
        <h4>媒体文件列表</h4>
        <button class="collapse-toggle" @click="isMediaCollapsed = !isMediaCollapsed">
          <Icon :name="isMediaCollapsed ? 'expand' : 'collapse'" :size="14" />
          <span>{{ isMediaCollapsed ? '展开' : '收起' }}</span>
        </button>
      </div>
      <div v-show="!isMediaCollapsed" class="media-manager-body">
        <div class="media-manager-card">
          <div class="media-summary">
            <span>共 {{ mediaFilesTotal }} 个文件，占用 {{ formatBytes(currentFilesTotalSize) }}</span>
            <span v-if="selectedMediaFilesCount > 0" class="media-selected">已选 {{ selectedMediaFilesCount }} 个，{{ formatBytes(selectedMediaFilesTotalSize) }}</span>
            <span v-else class="media-selected">已选 0 个，0 B</span>
          </div>

          <div class="media-filter">
            <button v-for="type in ['ALL', 'IMAGE', 'VIDEO', 'AUDIO']" :key="type" class="chart-btn" :class="{ active: mediaFileFilter === type }" @click="setMediaFileFilter(type)">
              {{ { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' }[type] }}
            </button>
          </div>

          <div v-if="mediaFilesLoading" class="media-loading">
            <div class="loading-spinner"></div>
            <span>加载中...</span>
          </div>

          <div v-else-if="mediaFiles.length === 0" class="media-empty">暂无文件</div>

          <table v-else class="media-table">
            <thead>
              <tr>
                <th class="col-checkbox">
                  <input type="checkbox" :checked="selectedMediaFilesCount === mediaFiles.length && mediaFiles.length > 0" :disabled="mediaFilesLoading || isPurging" @change="selectedMediaFilesCount === mediaFiles.length ? clearMediaFileSelection() : selectAllMediaFiles()" />
                </th>
                <th>文件名</th>
                <th>类型</th>
                <th>大小</th>
                <th>创建时间</th>
                <th class="col-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="file in mediaFiles" :key="file.id">
                <td class="col-checkbox">
                  <input type="checkbox" :checked="selectedMediaFileIds.has(file.id)" :disabled="mediaFilesLoading || isPurging" @change="toggleMediaFileSelection(file.id)" />
                </td>
                <td :title="file.fileName" class="file-name-cell" @click="openPreview(file)">{{ file.fileName }}</td>
                <td>{{ file.fileType }}</td>
                <td>{{ formatBytes(file.fileSize) }}</td>
                <td>{{ formatDate(file.createdAt) }}</td>
                <td class="col-action">
                  <button class="btn-preview" @click="openPreview(file)">预览</button>
                </td>
              </tr>
            </tbody>
          </table>

          <div v-if="mediaFiles.length > 0" class="media-pagination">
            <button :disabled="mediaFilePage <= 0 || mediaFilesLoading || isPurging" @click="mediaFilePage--; loadMediaFiles()">上一页</button>
            <span>{{ mediaFilePage + 1 }} / {{ totalMediaPages }}</span>
            <button :disabled="mediaFilePage >= totalMediaPages - 1 || mediaFilesLoading || isPurging" @click="mediaFilePage++; loadMediaFiles()">下一页</button>
          </div>

          <div v-if="purgeResult" class="purge-result">成功清理 {{ purgeResult.totalDeleted }} 个文件（约 {{ purgeResult.freedMB }}）</div>

          <div class="media-actions">
            <button class="btn-preview" :disabled="mediaFilesLoading || isPurging || mediaFiles.length === 0" @click="previewAllFiles">{{ mediaFilesLoading ? '加载中...' : '预览全部' }}</button>
            <button class="btn-delete" :disabled="selectedMediaFileIds.size === 0 || mediaFilesLoading || isPurging" @click="confirmDeleteSelected">{{ isPurging ? '处理中...' : '删除选中' }}</button>
            <button class="btn-purge" :disabled="isPurging || mediaFilesLoading" @click="confirmPurgeByFilter">{{ isPurging ? '清理中...' : '全部清理' }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { inject, computed } from 'vue';
import Icon from '../../components/Icon.vue';

export default {
  name: 'AdminMedia',
  components: { Icon },
  setup() {
    const media = inject('adminMedia');
    const formatDate = inject('adminFormatDate');
    const formatFileSize = inject('adminFormatFileSize');
    const openMediaImagePreview = inject('adminOpenMediaImagePreview');
    const isMediaCollapsed = inject('adminIsMediaCollapsed');

    const currentFilesTotalSize = computed(() =>
      media.mediaFiles.value.reduce((sum, f) => sum + (f.fileSize || 0), 0)
    );
    const totalMediaPages = computed(() =>
      Math.ceil(media.mediaFilesTotal.value / media.mediaFileSize.value) || 1
    );

    const confirmDeleteSelected = () => {
      if (!window.confirm(`确定删除选中的 ${media.selectedMediaFileIds.value.size} 个文件吗？`)) return;
      media.deleteSelectedMediaFiles();
    };
    const confirmPurgeByFilter = () => {
      const filter = media.mediaFileFilter.value;
      const labels = { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' };
      if (!window.confirm(`确定清理${labels[filter] || ''}媒体文件吗？清理后不可恢复。`)) return;
      media.purgeTypes.value = {
        IMAGE: filter === 'ALL' || filter === 'IMAGE',
        VIDEO: filter === 'ALL' || filter === 'VIDEO',
        AUDIO: filter === 'ALL' || filter === 'AUDIO',
      };
      media.confirmPurgeMedia();
    };

    // 图片预览：当前页图片 URL 列表
    const mediaGalleryUrls = computed(() =>
      (media.previewFiles.value || [])
        .filter(f => f && f.fileType === 'IMAGE' && f.url)
        .map(f => f.url)
    );
    const openImagePreview = (url) => {
      if (!url) return;
      openMediaImagePreview(url);
    };

    return {
      mediaFiles: media.mediaFiles,
      mediaFilesLoading: media.mediaFilesLoading,
      mediaFileFilter: media.mediaFileFilter,
      mediaFilePage: media.mediaFilePage,
      mediaFilesTotal: media.mediaFilesTotal,
      selectedMediaFileIds: media.selectedMediaFileIds,
      selectedMediaFilesCount: media.selectedMediaFilesCount,
      selectedMediaFilesTotalSize: media.selectedMediaFilesTotalSize,
      isPurging: media.isPurging,
      purgeResult: media.purgeResult,
      markMediaError: media.markMediaError,
      isMediaError: media.isMediaError,
      formatBytes: media.formatBytes,
      loadMediaFiles: media.loadMediaFiles,
      setMediaFileFilter: media.setMediaFileFilter,
      toggleMediaFileSelection: media.toggleMediaFileSelection,
      selectAllMediaFiles: media.selectAllMediaFiles,
      clearMediaFileSelection: media.clearMediaFileSelection,
      confirmDeleteSelected,
      confirmPurgeByFilter,
      openPreview: media.openPreview,
      previewAllFiles: media.previewAllFiles,
      isMediaCollapsed,
      formatDate,
      currentFilesTotalSize,
      totalMediaPages,
    };
  },
};
</script>

<style scoped>
.media-manager-body { /* wrapper */ }
.media-manager-card { background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; padding: 16px; }
.media-summary { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 14px; font-size: 13px; color: var(--text-secondary, #666); }
.media-selected { color: #3498db; font-weight: 500; }
.media-filter { display: flex; gap: 8px; margin-bottom: 14px; }
.media-loading { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 32px; gap: 10px; font-size: 13px; color: var(--text-muted, #888); }
.media-empty { padding: 32px; text-align: center; font-size: 13px; color: var(--text-muted, #999); }
.media-table { width: 100%; border-collapse: collapse; margin-bottom: 14px; font-size: 13px; }
.media-table th, .media-table td { padding: 10px 8px; border-bottom: 1px solid var(--border-color, #f0f0f0); text-align: left; color: var(--text-primary, #333); }
.media-table th { font-weight: 600; color: var(--text-primary, #333); background: var(--bg-tertiary, #fafafa); }
.media-table tbody tr:hover { background: var(--bg-tertiary, #fafafa); }
.media-table .col-checkbox { width: 36px; text-align: center; }
.media-table input[type="checkbox"] { width: 14px; height: 14px; cursor: pointer; }
.media-pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin-bottom: 14px; font-size: 13px; color: var(--text-secondary, #666); }
.media-pagination button { padding: 6px 12px; border: 1px solid var(--border-color, #e0e0e0); background: var(--card-bg, #fff); border-radius: 4px; cursor: pointer; font-size: 12px; color: var(--text-secondary, #666); transition: all 0.2s; }
.media-pagination button:hover:not(:disabled) { border-color: var(--accent-color, #3498db); color: var(--accent-color, #3498db); }
.media-pagination button:disabled { opacity: 0.5; cursor: not-allowed; }
.purge-result { margin-bottom: 12px; padding: 10px 12px; background-color: #f0f9ff; border-radius: 6px; font-size: 13px; color: #1677ff; text-align: center; }
.media-actions { display: flex; gap: 10px; }
.media-actions button { flex: 1; padding: 10px 0; border: none; border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer; transition: background-color 0.2s; }
.btn-delete:not(:disabled) { background-color: #e74c3c; color: #fff; }
.btn-delete:not(:disabled):hover { background-color: #c0392b; }
.btn-delete:disabled { background-color: var(--border-color, #e0e0e0); color: var(--text-muted, #999); cursor: not-allowed; }
.btn-purge:not(:disabled) { background-color: #f39c12; color: #fff; }
.btn-purge:not(:disabled):hover { background-color: #e67e22; }
.btn-purge:disabled { background-color: var(--border-color, #e0e0e0); color: var(--text-muted, #999); cursor: not-allowed; }
.file-name-cell { cursor: pointer; color: #3498db; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-name-cell:hover { text-decoration: underline; }
.col-action { width: 60px; text-align: center; }
.btn-preview { padding: 4px 10px; font-size: 12px; color: var(--accent-color, #3498db); background: var(--bg-tertiary, #f0f9ff); border: 1px solid var(--border-color, #b7d8f7); border-radius: 4px; cursor: pointer; transition: all 0.2s; }
.btn-preview:hover:not(:disabled) { background: var(--bg-tertiary, #e0f2ff); border-color: var(--accent-color, #3498db); }
.btn-preview:disabled { opacity: 0.5; cursor: not-allowed; }
</style>