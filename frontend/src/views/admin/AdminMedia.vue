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
            <span class="media-select-actions">
              <button
                v-if="mediaFilesTotal > mediaFiles.length"
                class="btn-action"
                :disabled="mediaSelectingAll || mediaFilesLoading || isPurging"
                :title="'把当前筛选下的全部 ' + mediaFilesTotal + ' 个文件都选中（跨页）'"
                @click="onSelectAllMatching"
              >
                {{ mediaSelectingAll ? '选择中…' : '全选全部（' + mediaFilesTotal + '）' }}
              </button>
              <button
                v-if="selectedMediaFilesCount > 0"
                class="btn-action"
                :disabled="mediaSelectingAll || isPurging"
                @click="clearMediaFileSelection"
              >
                取消选择
              </button>
            </span>
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

    <!-- 媒体保留策略：超过 N 天自动删除。默认关闭；打开前先用 dry-run 预览看清影响面 -->
    <div class="section-card retention-card">
      <div class="section-card-header">
        <h4>媒体保留策略</h4>
        <span class="page-header-meta">超过指定天数的图片 / 视频 / 语音由定时任务自动清理（默认关闭）</span>
      </div>

      <div v-if="retentionError" class="state-box is-error">
        <span class="state-title">保留策略加载失败</span>
        <span>{{ retentionError }}</span>
        <div class="state-actions"><button class="btn-action promote" @click="loadRetention">重试</button></div>
      </div>

      <div v-else class="media-manager-card">
        <div class="retention-row">
          <label class="retention-switch">
            <input
              type="checkbox"
              :checked="retentionForm.enabled"
              :disabled="retentionLoading || retentionSaving"
              @change="retentionForm.enabled = $event.target.checked"
            />
            <span>启用自动清理</span>
          </label>

          <label class="retention-field">
            保留最近
            <input
              v-model.number="retentionForm.days"
              type="number"
              min="1"
              max="3650"
              :disabled="retentionLoading || retentionSaving"
              @change="onRetentionDaysChanged"
            />
            天
          </label>

          <button class="btn-action promote" :disabled="retentionSaving || retentionLoading || !retentionDirty" @click="saveRetention">
            {{ retentionSaving ? '保存中...' : '保存' }}
          </button>
          <button class="btn-action" :disabled="retentionPreviewLoading || retentionLoading" @click="loadRetentionPreview()">
            {{ retentionPreviewLoading ? '统计中...' : '刷新预览' }}
          </button>
          <button class="btn-action danger" :disabled="retentionRunning || retentionLoading" @click="confirmRunRetention">
            {{ retentionRunning ? '清理中...' : '立即清理一次' }}
          </button>
          <span v-if="retentionDirty" class="retention-dirty">有未保存的改动</span>
        </div>

        <div v-if="retentionPreview" class="purge-preview retention-preview">
          <span class="purge-preview-title">清理预览（不会真的删除）</span>
          <span class="purge-preview-item">
            将删除 <b>{{ retentionPreview.totalFiles }}</b> 个文件 / {{ formatBytes(retentionPreview.totalBytes) }}
          </span>
          <span v-if="retentionCutoffText" class="purge-preview-item">早于 {{ retentionCutoffText }}</span>
          <span v-for="t in ['IMAGE', 'VIDEO', 'AUDIO']" :key="t" class="purge-preview-item">
            {{ labels[t] }}：<b>{{ previewCountOf(t) }}</b> 个 / {{ formatBytes(previewBytesOf(t)) }}
          </span>
        </div>
        <div v-else-if="retentionPreviewError" class="retention-preview-error">{{ retentionPreviewError }}</div>
        <div v-else class="retention-hint">
          保留策略按文件「修改时间」计算年龄；到点由后端定时任务清理（默认每天 4:00），
          并记入审计日志（操作项 FILE_RETENTION_PURGE）。
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { inject, computed, onMounted, ref } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { showConfirm } from '../../components/ConfirmDialog.vue';
import { adminApi } from '../../services/api';

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

  /** 全选「当前筛选下的全部文件」（跨页），完成后给出明确反馈 */
  const onSelectAllMatching = async () => {
    const n = await media.selectAllMatchingMediaFiles();
    showMsg(n > 0 ? `已选中全部 ${n} 个文件` : '没有可选择的文件', n > 0 ? 'success' : 'warning');
  };
    const formatDate = inject('adminFormatDate');
    const formatFileSize = inject('adminFormatFileSize');
    const openMediaImagePreview = inject('adminOpenMediaImagePreview');
    const isMediaCollapsed = inject('adminIsMediaCollapsed');
    const showMsg = inject('adminShowMsg');

    // ===== 媒体保留策略（超过 N 天自动删除）=====
    // retention = 服务端已生效的值；retentionForm = 表单里待保存的值。
    // 两者分开是为了「改了天数先看影响面、确认了再保存」——预览按表单值算。
    const retention = ref({ enabled: false, days: 90, types: [] });
    const retentionForm = ref({ enabled: false, days: 90 });
    const retentionPreview = ref(null);
    const retentionPreviewError = ref('');
    const retentionError = ref('');
    const retentionLoading = ref(false);
    const retentionSaving = ref(false);
    const retentionPreviewLoading = ref(false);
    const retentionRunning = ref(false);

    const retentionDirty = computed(() =>
      retentionForm.value.enabled !== retention.value.enabled
      || Number(retentionForm.value.days) !== Number(retention.value.days));

    const retentionCutoffText = computed(() => {
      const c = retentionPreview.value && retentionPreview.value.cutoff;
      return c ? String(c).replace('T', ' ').slice(0, 16) : '';
    });

    const previewCountOf = (t) => {
      const b = retentionPreview.value && retentionPreview.value.byType
        && retentionPreview.value.byType[t];
      return b ? b.count : 0;
    };
    const previewBytesOf = (t) => {
      const b = retentionPreview.value && retentionPreview.value.byType
        && retentionPreview.value.byType[t];
      return b ? b.bytes : 0;
    };

    const clampRetentionDays = (value) => {
      const n = Number(value);
      if (!Number.isFinite(n)) return 90;
      return Math.max(1, Math.min(3650, Math.round(n)));
    };

    /** dry-run 预检：只统计影响面，不删除任何文件 */
    const loadRetentionPreview = async (days) => {
      retentionPreviewLoading.value = true;
      retentionPreviewError.value = '';
      try {
        const d = days != null ? days : retentionForm.value.days;
        const res = await adminApi.previewMediaRetention(d);
        retentionPreview.value = res || null;
      } catch (e) {
        retentionPreview.value = null;
        retentionPreviewError.value = e.message || '清理预览失败';
      } finally {
        retentionPreviewLoading.value = false;
      }
    };

    const loadRetention = async () => {
      retentionLoading.value = true;
      retentionError.value = '';
      try {
        const cfg = await adminApi.getMediaRetentionConfig();
        retention.value = {
          enabled: !!(cfg && cfg.enabled),
          days: clampRetentionDays(cfg && cfg.days),
          types: (cfg && cfg.types) || [],
        };
        retentionForm.value = { enabled: retention.value.enabled, days: retention.value.days };
        await loadRetentionPreview(retentionForm.value.days);
      } catch (e) {
        retentionError.value = e.message || '加载保留策略失败';
      } finally {
        retentionLoading.value = false;
      }
    };

    /** 改天数时立刻按新天数预检（不保存），方便「先看影响面再决定」 */
    const onRetentionDaysChanged = () => {
      const d = clampRetentionDays(retentionForm.value.days);
      retentionForm.value.days = d;
      loadRetentionPreview(d);
    };

    /**
     * 立即执行一次清理（一次性，不改动已保存的开关设置）。
     * 定时任务每天 4:00 才跑，验收/救急时需要能当场触发一次。
     */
    const confirmRunRetention = async () => {
      const days = clampRetentionDays(retentionForm.value.days);
      const count = retentionPreview.value ? retentionPreview.value.totalFiles : '?';
      const size = retentionPreview.value ? media.formatBytes(retentionPreview.value.totalBytes) : '未知';
      const ok = await showConfirm({
        title: '立即执行一次媒体清理',
        message: '将删除修改时间早于「现在 - ' + days + ' 天」的图片 / 视频 / 语音，共 '
          + count + ' 个（约 ' + size + '）。删除后不可恢复，关联消息会被软删除，头像不会被清理。'
          + '本操作不影响已保存的开关设置。',
        type: 'warning',
        confirmText: '立即清理',
      });
      if (!ok) return;
      retentionRunning.value = true;
      try {
        const res = await adminApi.runMediaRetention(days);
        if (showMsg) showMsg((res && res.message) ? res.message : '清理完成', 'success');
        await loadRetentionPreview(days);
        await media.loadMediaFiles();
        await media.loadMediaSummary();
      } catch (e) {
        if (showMsg) showMsg('清理失败: ' + e.message, 'error');
      } finally {
        retentionRunning.value = false;
      }
    };

    const saveRetention = async () => {
      retentionSaving.value = true;
      try {
        const saved = await adminApi.updateMediaRetentionConfig({
          enabled: !!retentionForm.value.enabled,
          days: clampRetentionDays(retentionForm.value.days),
        });
        retention.value = {
          enabled: !!(saved && saved.enabled),
          days: clampRetentionDays(saved && saved.days),
          types: (saved && saved.types) || [],
        };
        retentionForm.value = { enabled: retention.value.enabled, days: retention.value.days };
        if (showMsg) showMsg('媒体保留策略已保存并立即生效', 'success');
        await loadRetentionPreview(retention.value.days);
      } catch (e) {
        if (showMsg) showMsg('保存保留策略失败: ' + e.message, 'error');
      } finally {
        retentionSaving.value = false;
      }
    };

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
      // 保留策略：配置 + dry-run 预览各取一次（普通用户不会进这个页面，接口本身也是 ADMIN-only）
      loadRetention();
    });

    return {
      onSelectAllMatching,
      mediaSelectingAll: media.mediaSelectingAll,
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
      labels,
      retentionForm,
      retentionPreview,
      retentionPreviewError,
      retentionError,
      retentionLoading,
      retentionSaving,
      retentionPreviewLoading,
      retentionRunning,
      retentionDirty,
      retentionCutoffText,
      loadRetention,
      loadRetentionPreview,
      onRetentionDaysChanged,
      saveRetention,
      confirmRunRetention,
      previewCountOf,
      previewBytesOf,
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
.media-select-actions { display: inline-flex; gap: 8px; margin-left: auto; }
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

/* 媒体保留策略面板 */
.retention-card { margin-top: 16px; }
.retention-row { display: flex; flex-wrap: wrap; gap: 14px; align-items: center; }
.retention-switch { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-primary, #333); cursor: pointer; }
.retention-switch input { width: 16px; height: 16px; cursor: pointer; }
.retention-field { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-secondary, #666); }
.retention-field input { width: 96px; padding: 4px 8px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 4px; background: var(--card-bg, #fff); color: var(--text-primary, #333); }
.retention-dirty { font-size: 12px; color: #f39c12; }
.retention-preview { margin-top: 12px; margin-bottom: 0; }
.retention-preview-error { margin-top: 12px; font-size: 12.5px; color: #e74c3c; }
.retention-hint { margin-top: 12px; font-size: 12.5px; line-height: 1.7; color: var(--text-secondary, #666); }
</style>
