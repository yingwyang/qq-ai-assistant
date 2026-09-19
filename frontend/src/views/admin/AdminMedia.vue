<template>
  <div class="tab-panel admin-media">
    <AdminPageHeader title="媒体管理" subtitle="上传目录中的图片 / 视频 / 音频 / 文件">
      <template #meta>
        <span v-if="mediaSummary" class="page-header-meta">
          磁盘共 {{ summaryOf('TOTAL').count }} 个文件 · {{ formatBytes(summaryOf('TOTAL').bytes) }}
        </span>
      </template>
      <button class="btn-action" :disabled="mediaSummaryLoading" @click="loadMediaSummary">
        {{ mediaSummaryLoading ? '统计中...' : '刷新统计' }}
      </button>
      <button class="collapse-toggle" @click="isMediaCollapsed = !isMediaCollapsed">
        <Icon :name="isMediaCollapsed ? 'expand' : 'collapse'" :size="14" />
        <span>{{ isMediaCollapsed ? '展开' : '收起' }}</span>
      </button>
    </AdminPageHeader>

    <div class="section-card">
      <div class="section-card-header">
        <h4>媒体文件列表</h4>
        <span class="page-header-meta">缩略图滚动到可见位置才加载</span>
      </div>
      <div v-show="!isMediaCollapsed" class="media-manager-body">
        <div class="media-manager-card">
          <div class="filter-bar">
            <div class="filter-field">
              <label>类型</label>
              <div class="media-filter">
                <button v-for="type in ['ALL', 'IMAGE', 'VIDEO', 'AUDIO']" :key="type" class="chart-btn" :class="{ active: mediaFileFilter === type }" @click="setMediaFileFilter(type)">
                  {{ { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' }[type] }}
                </button>
              </div>
            </div>
            <div class="filter-field is-grow">
              <label>文件名关键字</label>
              <input v-model="mediaKeyword" type="text" placeholder="按文件名过滤..." @change="setMediaKeyword(mediaKeyword)" />
            </div>
            <div class="filter-field">
              <label>起始日期</label>
              <input type="date" v-model="mediaFrom" @change="setMediaRange({ from: mediaFrom })" />
            </div>
            <div class="filter-field">
              <label>结束日期</label>
              <input type="date" v-model="mediaTo" @change="setMediaRange({ to: mediaTo })" />
            </div>
            <div class="filter-field">
              <label>排序</label>
              <select :value="mediaSort" @change="setMediaSort($event.target.value)">
                <option v-for="opt in mediaSortOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>
            <div class="filter-field">
              <label>每页</label>
              <select :value="String(mediaFileSize)" @change="setMediaPageSize($event.target.value)">
                <option value="20">20</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </div>
            <button v-if="mediaFilterActive" class="btn-action" @click="resetMediaFilters">清空筛选</button>
          </div>

          <div class="media-summary">
            <span>共 {{ mediaFilesTotal }} 个文件（本页占用 {{ formatBytes(currentFilesTotalSize) }}）</span>
            <span v-if="selectedMediaFilesCount > 0" class="media-selected">已选 {{ selectedMediaFilesCount }} 个，{{ formatBytes(selectedMediaFilesTotalSize) }}</span>
            <span v-else class="media-selected">已选 0 个，0 B</span>
          </div>

          <div v-if="mediaFilesLoading" class="media-loading">
            <div class="loading-spinner"></div>
            <span>加载中...</span>
          </div>

          <div v-else-if="mediaFilesError" class="state-box is-error">
            <span class="state-title">加载媒体文件失败</span>
            <span>{{ mediaFilesError }}</span>
            <div class="state-actions"><button class="btn-action promote" @click="loadMediaFiles">重试</button></div>
          </div>

          <div v-else-if="mediaFiles.length === 0" class="state-box">
            <span class="state-title">{{ mediaFilterActive ? '当前筛选没有文件' : '暂无文件' }}</span>
            <span>upload-media 目录为空，或筛选条件过窄</span>
          </div>

          <table v-else class="media-table">
            <thead>
              <tr>
                <th class="col-checkbox">
                  <input type="checkbox" :checked="selectedMediaFilesCount === mediaFiles.length && mediaFiles.length > 0" :disabled="mediaFilesLoading || isPurging" @change="selectedMediaFilesCount === mediaFiles.length ? clearMediaFileSelection() : selectAllMediaFiles()" />
                </th>
                <th class="col-thumb">预览</th>
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
                <td class="col-thumb">
                  <img
                    v-if="file.fileType === 'IMAGE' && file.url && !isMediaError(file.id)"
                    v-lazy-src="file.url"
                    :alt="file.fileName"
                    class="media-thumb"
                    loading="lazy"
                    @error="markMediaError(file.id)"
                    @click="openPreview(file)"
                  />
                  <span v-else class="media-thumb media-thumb-placeholder">
                    <Icon :name="file.fileType === 'VIDEO' ? 'video' : (file.fileType === 'AUDIO' ? 'audio' : 'file')" :size="18" />
                  </span>
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

          <div v-if="mediaFiles.length > 0" class="pager">
            <span class="pager-info">第 {{ mediaFilePage + 1 }} / {{ totalMediaPages }} 页</span>
            <button class="btn-page" :disabled="mediaFilePage <= 0 || mediaFilesLoading || isPurging" @click="goToMediaPage(mediaFilePage - 1)">上一页</button>
            <button class="btn-page" :disabled="mediaFilePage >= totalMediaPages - 1 || mediaFilesLoading || isPurging" @click="goToMediaPage(mediaFilePage + 1)">下一页</button>
          </div>

          <div v-if="purgeResult" class="purge-result">成功清理 {{ purgeResult.totalDeleted }} 个文件（约 {{ purgeResult.freedMB }}）</div>

          <!-- 清理预览：执行前先看清影响面 -->
          <div v-if="mediaSummary" class="purge-preview">
            <span class="purge-preview-title">清理预览</span>
            <span v-for="t in ['IMAGE', 'VIDEO', 'AUDIO']" :key="t" class="purge-preview-item">
              {{ { IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' }[t] }}：<b>{{ summaryOf(t).count }}</b> 个 / {{ formatBytes(summaryOf(t).bytes) }}
            </span>
          </div>

          <div class="media-actions">
            <button class="btn-preview" :disabled="mediaFilesLoading || isPurging || mediaFiles.length === 0" @click="previewAllFiles">{{ mediaFilesLoading ? '加载中...' : '预览全部' }}</button>
            <button class="btn-delete" :disabled="selectedMediaFileIds.size === 0 || mediaFilesLoading || isPurging" @click="confirmDeleteSelected">{{ isPurging ? '处理中...' : `删除选中${selectedMediaFilesCount ? '（' + selectedMediaFilesCount + '）' : ''}` }}</button>
            <button class="btn-purge" :disabled="isPurging || mediaFilesLoading" @click="confirmPurgeByFilter">{{ isPurging ? '清理中...' : `清理${filterLabel}` }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { inject, computed, onMounted } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { showConfirm } from '../../components/ConfirmDialog.vue';

/**
 * 缩略图懒加载指令：进入视口才真正请求图片，
 * 避免一页 100 个 <img> 同时打满 uploads 静态资源请求。
 *
 * 注意用 unobserve(el) 而不是 disconnect()：后者会一次性停掉所有元素的观察，
 * 结果只有第一个进入视口的图片能加载（其余永远空着）。
 */
const lazySrc = {
  mounted(el, binding) {
    const src = binding.value;
    if (!src) return;
    if (typeof IntersectionObserver === 'undefined') {
      el.src = src;
      return;
    }
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        el.src = src;
        observer.unobserve(el);
      });
    }, { rootMargin: '160px' });
    observer.observe(el);
    el._lazyObserver = observer;
  },
  updated(el, binding) {
    if (binding.value !== binding.oldValue && binding.value) {
      el.src = binding.value;
    }
  },
  unmounted(el) {
    if (el._lazyObserver) {
      el._lazyObserver.unobserve(el);
      el._lazyObserver = null;
    }
  },
};

export default {
  name: 'AdminMedia',
  components: { Icon, AdminPageHeader },
  directives: { lazySrc },
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

    const labels = { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' };
    const filterLabel = computed(() => labels[media.mediaFileFilter.value] || '全部');

    const confirmDeleteSelected = async () => {
      const count = media.selectedMediaFileIds.value.size;
      const ok = await showConfirm({
        title: '删除媒体文件',
        message: `确定删除选中的 ${count} 个文件吗？删除后关联消息会被软删除，文件不可恢复。`,
        type: 'warning',
        confirmText: '删除',
      });
      if (!ok) return;
      media.deleteSelectedMediaFiles();
    };

    const confirmPurgeByFilter = async () => {
      const filter = media.mediaFileFilter.value;
      const types = filter === 'ALL' ? ['IMAGE', 'VIDEO', 'AUDIO'] : [filter];
      const detail = types
        .map((t) => `${labels[t] || t} ${media.summaryOf(t).count} 个（${media.formatBytes(media.summaryOf(t).bytes)}）`)
        .join('、');
      const ok = await showConfirm({
        title: '清理媒体文件',
        message: `将清理：${detail}。清理后不可恢复（头像文件不会被清理）。`,
        type: 'warning',
        confirmText: '立即清理',
      });
      if (!ok) return;
      media.purgeTypes.value = {
        IMAGE: types.includes('IMAGE'),
        VIDEO: types.includes('VIDEO'),
        AUDIO: types.includes('AUDIO'),
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

    onMounted(() => {
      // 清理预览需要各类型的数量与体积，进页面时取一次
      if (!media.mediaSummary.value) media.loadMediaSummary();
    });

    return {
      mediaFiles: media.mediaFiles,
      mediaFilesLoading: media.mediaFilesLoading,
      mediaFilesError: media.mediaFilesError,
      mediaFileFilter: media.mediaFileFilter,
      mediaFilePage: media.mediaFilePage,
      mediaFileSize: media.mediaFileSize,
      mediaFilesTotal: media.mediaFilesTotal,
      mediaKeyword: media.mediaKeyword,
      mediaFrom: media.mediaFrom,
      mediaTo: media.mediaTo,
      mediaSort: media.mediaSort,
      mediaSortOptions: media.mediaSortOptions,
      mediaFilterActive: media.mediaFilterActive,
      mediaSummary: media.mediaSummary,
      mediaSummaryLoading: media.mediaSummaryLoading,
      loadMediaSummary: media.loadMediaSummary,
      summaryOf: media.summaryOf,
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
      setMediaKeyword: media.setMediaKeyword,
      setMediaRange: media.setMediaRange,
      setMediaSort: media.setMediaSort,
      setMediaPageSize: media.setMediaPageSize,
      resetMediaFilters: media.resetMediaFilters,
      goToMediaPage: media.goToMediaPage,
      toggleMediaFileSelection: media.toggleMediaFileSelection,
      selectAllMediaFiles: media.selectAllMediaFiles,
      clearMediaFileSelection: media.clearMediaFileSelection,
      confirmDeleteSelected,
      confirmPurgeByFilter,
      filterLabel,
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
.media-selected { color: var(--accent-color, #3498db); font-weight: 500; }
.media-filter { display: flex; gap: 8px; }
.media-loading { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 32px; gap: 10px; font-size: 13px; color: var(--text-muted, #888); }
.media-table { width: 100%; border-collapse: collapse; margin-bottom: 14px; font-size: 13px; }
.media-table th, .media-table td { padding: 8px; border-bottom: 1px solid var(--border-color, #f0f0f0); text-align: left; color: var(--text-primary, #333); }
.media-table th { font-weight: 600; color: var(--text-primary, #333); background: var(--bg-tertiary, #fafafa); }
.media-table tbody tr:hover { background: var(--bg-tertiary, #fafafa); }
.media-table .col-checkbox { width: 36px; text-align: center; }
.media-table .col-thumb { width: 56px; }
.media-table input[type="checkbox"] { width: 14px; height: 14px; cursor: pointer; }
.media-thumb { width: 40px; height: 40px; object-fit: cover; border-radius: 4px; background: var(--bg-tertiary, #f0f0f0); cursor: pointer; display: inline-block; }
.media-thumb-placeholder { display: inline-flex; align-items: center; justify-content: center; color: var(--text-muted, #999); }
.purge-result { margin-bottom: 12px; padding: 10px 12px; background-color: rgba(22, 119, 255, 0.1); border-radius: 6px; font-size: 13px; color: #1677ff; text-align: center; }
.purge-preview { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 12px; padding: 10px 12px; background: var(--bg-tertiary, #f8f9fa); border-radius: 6px; font-size: 12.5px; color: var(--text-secondary, #666); }
.purge-preview-title { font-weight: 600; color: var(--text-primary, #333); }
.purge-preview-item b { color: var(--text-primary, #333); }
.media-actions { display: flex; gap: 10px; }
.media-actions button { flex: 1; padding: 10px 0; border: none; border-radius: 6px; font-size: 14px; font-weight: 500; cursor: pointer; transition: background-color 0.2s; }
.btn-delete:not(:disabled) { background-color: #e74c3c; color: #fff; }
.btn-delete:not(:disabled):hover { background-color: #c0392b; }
.btn-delete:disabled { background-color: var(--border-color, #e0e0e0); color: var(--text-muted, #999); cursor: not-allowed; }
.btn-purge:not(:disabled) { background-color: #f39c12; color: #fff; }
.btn-purge:not(:disabled):hover { background-color: #e67e22; }
/* 暗色下橙底白字只有 2.2:1，改为橙底深字 */
.theme-dark .btn-purge:not(:disabled) { background-color: #f0a33a; color: #1a1a2e; }
.theme-dark .btn-purge:not(:disabled):hover { background-color: #ffb74d; }
.theme-dark .btn-delete:disabled { color: var(--text-muted, #8c8c8c); }
.btn-purge:disabled { background-color: var(--border-color, #e0e0e0); color: var(--text-muted, #999); cursor: not-allowed; }
.file-name-cell { cursor: pointer; color: var(--accent-color, #3498db); max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-name-cell:hover { text-decoration: underline; }
.col-action { width: 60px; text-align: center; }
.btn-preview { padding: 4px 10px; font-size: 12px; color: var(--accent-color, #3498db); background: var(--bg-tertiary, #f0f9ff); border: 1px solid var(--border-color, #b7d8f7); border-radius: 4px; cursor: pointer; transition: all 0.2s; }
.btn-preview:hover:not(:disabled) { background: var(--bg-tertiary, #e0f2ff); border-color: var(--accent-color, #3498db); }
.btn-preview:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
