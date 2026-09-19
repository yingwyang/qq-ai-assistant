import { ref, computed } from 'vue';
import { messageApi } from '../services/api';
import logger from '../utils/logger';

export function useMediaManager({ showSystemMsg, loadDiskUsage } = {}) {
  // 媒体文件管理状态
  const mediaFiles = ref([]);
  const mediaFilesLoading = ref(false);
  const mediaFilesError = ref('');
  const mediaFileFilter = ref('ALL');
  const mediaKeyword = ref('');
  const mediaFrom = ref('');
  const mediaTo = ref('');
  const mediaSortField = ref('time');
  const mediaSortDir = ref('desc');
  const mediaFilePage = ref(0);
  const mediaFileSize = ref(20);
  const mediaFilesTotal = ref(0);
  const selectedMediaFileIds = ref(new Set());

  const mediaSortOptions = [
    { value: 'time,desc', label: '时间（新→旧）' },
    { value: 'time,asc', label: '时间（旧→新）' },
    { value: 'size,desc', label: '体积（大→小）' },
    { value: 'size,asc', label: '体积（小→大）' },
    { value: 'name,asc', label: '文件名（A→Z）' },
  ];

  const mediaSort = computed(() => `${mediaSortField.value},${mediaSortDir.value}`);
  const setMediaSort = (value) => {
    const [field, dir] = String(value || 'time,desc').split(',');
    mediaSortField.value = field || 'time';
    mediaSortDir.value = dir || 'desc';
    mediaFilePage.value = 0;
    loadMediaFiles();
  };

  /** 当前筛选条件（供列表、预览、全量加载共用） */
  const mediaQuery = computed(() => ({
    type: mediaFileFilter.value,
    kw: mediaKeyword.value.trim() || undefined,
    from: mediaFrom.value || undefined,
    to: mediaTo.value || undefined,
    sort: mediaSort.value,
  }));

  // 各类型媒体文件的数量与占用（清理预览用）
  const mediaSummary = ref(null);
  const mediaSummaryLoading = ref(false);
  const loadMediaSummary = async () => {
    mediaSummaryLoading.value = true;
    try {
      const res = await messageApi.getMediaSummary();
      mediaSummary.value = (res && res.byType) || null;
    } catch (error) {
      logger.warn('统计媒体文件失败:', error);
      mediaSummary.value = null;
    } finally {
      mediaSummaryLoading.value = false;
    }
  };
  const summaryOf = (type) => {
    const bucket = mediaSummary.value && mediaSummary.value[type];
    return bucket || { count: 0, bytes: 0 };
  };

  // 跨页文件缓存：用于预览已选中但不在当前页的文件
  const mediaFileCache = ref(new Map());

  // 媒体文件加载失败记录
  const mediaErrorIds = ref(new Set());

  // 媒体文件清理状态
  const isPurging = ref(false);
  const purgeTypes = ref({ IMAGE: false, VIDEO: false, AUDIO: false });
  const purgeResult = ref(null);
  const hasPurgeSelection = computed(() =>
    purgeTypes.value.IMAGE || purgeTypes.value.VIDEO || purgeTypes.value.AUDIO
  );

  const markMediaError = (id) => {
    if (id == null) return;
    mediaErrorIds.value = new Set(mediaErrorIds.value).add(id);
  };

  const isMediaError = (id) => mediaErrorIds.value.has(id);

  const selectedMediaFilesCount = computed(() => selectedMediaFileIds.value.size);

  const selectedMediaFilesTotalSize = computed(() => {
    const selectedIds = selectedMediaFileIds.value;
    let total = 0;
    selectedIds.forEach((id) => {
      const file = mediaFileCache.value.get(id);
      if (file) total += file.fileSize || 0;
    });
    return total;
  });

  const formatBytes = (bytes) => {
    if (bytes === 0 || bytes == null) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    const idx = Math.min(i, sizes.length - 1);
    return `${parseFloat((bytes / Math.pow(k, idx)).toFixed(2))} ${sizes[idx]}`;
  };

  const loadMediaFiles = async () => {
    mediaFilesLoading.value = true;
    mediaFilesError.value = '';
    try {
      const res = await messageApi.getMediaFiles({
        ...mediaQuery.value,
        page: mediaFilePage.value,
        size: mediaFileSize.value,
      });
      if (res) {
        const content = res.content || [];
        mediaFiles.value = content;
        mediaFilesTotal.value = res.totalElements || 0;
        const newCache = new Map(mediaFileCache.value);
        content.forEach((file) => {
          if (file.id != null) newCache.set(file.id, file);
        });
        mediaFileCache.value = newCache;
      } else {
        mediaFiles.value = [];
        mediaFilesTotal.value = 0;
      }
    } catch (error) {
      logger.error('加载媒体文件失败:', error);
      mediaFilesError.value = error.message || '加载媒体文件失败';
      mediaFiles.value = [];
      mediaFilesTotal.value = 0;
    } finally {
      mediaFilesLoading.value = false;
    }
  };

  const setMediaFileFilter = (type) => {
    mediaFileFilter.value = type || 'ALL';
    mediaFilePage.value = 0;
    selectedMediaFileIds.value = new Set();
    mediaFileCache.value = new Map();
    loadMediaFiles();
    loadMediaSummary();
  };

  const setMediaKeyword = (value) => {
    mediaKeyword.value = value || '';
    mediaFilePage.value = 0;
    loadMediaFiles();
  };

  const setMediaRange = ({ from, to }) => {
    if (from !== undefined) mediaFrom.value = from;
    if (to !== undefined) mediaTo.value = to;
    mediaFilePage.value = 0;
    loadMediaFiles();
  };

  const setMediaPageSize = (size) => {
    mediaFileSize.value = Number(size) || 20;
    mediaFilePage.value = 0;
    loadMediaFiles();
  };

  const resetMediaFilters = () => {
    mediaFileFilter.value = 'ALL';
    mediaKeyword.value = '';
    mediaFrom.value = '';
    mediaTo.value = '';
    mediaSortField.value = 'time';
    mediaSortDir.value = 'desc';
    mediaFilePage.value = 0;
    loadMediaFiles();
  };

  const goToMediaPage = (page) => {
    const totalPages = Math.max(1, Math.ceil(mediaFilesTotal.value / mediaFileSize.value));
    if (page < 0 || page >= totalPages) return;
    mediaFilePage.value = page;
    loadMediaFiles();
  };

  const mediaFilterActive = computed(() =>
    mediaFileFilter.value !== 'ALL' || !!mediaKeyword.value.trim() || !!mediaFrom.value || !!mediaTo.value);

  const toggleMediaFileSelection = (id) => {
    const set = new Set(selectedMediaFileIds.value);
    if (set.has(id)) {
      set.delete(id);
    } else {
      set.add(id);
    }
    selectedMediaFileIds.value = set;
  };

  const selectAllMediaFiles = () => {
    const newSet = new Set(selectedMediaFileIds.value);
    const newCache = new Map(mediaFileCache.value);
    mediaFiles.value.forEach((file) => {
      if (file.id != null) {
        newSet.add(file.id);
        newCache.set(file.id, file);
      }
    });
    selectedMediaFileIds.value = newSet;
    mediaFileCache.value = newCache;
  };

  const clearMediaFileSelection = () => {
    selectedMediaFileIds.value = new Set();
  };

  const deleteSelectedMediaFiles = async () => {
    if (selectedMediaFileIds.value.size === 0) return;
    const ids = Array.from(selectedMediaFileIds.value);
    try {
      const result = await messageApi.deleteMediaFiles(ids);
      if (showSystemMsg) showSystemMsg(result.message || `成功删除 ${result.totalDeleted} 个文件`, 'success');
      selectedMediaFileIds.value = new Set();
      mediaFileCache.value = new Map();
      await loadMediaFiles();
      if (loadDiskUsage) await loadDiskUsage();
    } catch (error) {
      logger.error('删除媒体文件失败:', error);
      if (showSystemMsg) showSystemMsg(`删除媒体文件失败: ${error.message}`, 'error');
    }
  };

  const loadAllMediaFilesForPreview = async () => {
    mediaFilesLoading.value = true;
    mediaFilesError.value = '';
    try {
      const size = 500;
      let page = 0;
      let total = 0;
      const allFiles = [];
      const newCache = new Map(mediaFileCache.value);
      do {
        const res = await messageApi.getMediaFiles({ ...mediaQuery.value, page, size });
        if (!res) break;
        const content = res.content || [];
        total = res.totalElements || 0;
        content.forEach((file) => {
          if (file.id != null) newCache.set(file.id, file);
        });
        allFiles.push(...content);
        if (content.length < size) break;
        page++;
      } while (allFiles.length < total);
      mediaFileCache.value = newCache;
      return allFiles;
    } catch (error) {
      logger.error('加载全部媒体文件失败:', error);
      mediaFilesError.value = error.message || '加载全部媒体文件失败';
      return [];
    } finally {
      mediaFilesLoading.value = false;
    }
  };

  const confirmPurgeMedia = async () => {
    if (!hasPurgeSelection.value) return;
    const types = [];
    if (purgeTypes.value.IMAGE) types.push('IMAGE');
    if (purgeTypes.value.VIDEO) types.push('VIDEO');
    if (purgeTypes.value.AUDIO) types.push('AUDIO');

    isPurging.value = true;
    purgeResult.value = null;
    try {
      const result = await messageApi.purgeMedia(types);
      purgeResult.value = result;
      if (showSystemMsg) showSystemMsg(`成功清理 ${result.totalDeleted} 个文件（约 ${result.freedMB}）`, 'success');
      selectedMediaFileIds.value = new Set();
      mediaFileCache.value = new Map();
      await loadMediaFiles();
      if (loadDiskUsage) await loadDiskUsage();
    } catch (error) {
      logger.error('清理媒体文件失败:', error);
      if (showSystemMsg) showSystemMsg(`清理失败: ${error.message}`, 'error');
    } finally {
      isPurging.value = false;
    }
  };

  // ===== 预览相关状态和方法 =====
  const showPreviewModal = ref(false);
  const previewFiles = ref([]);
  const currentPreviewIndex = ref(0);
  const previewMode = ref('single');

  // 预览分页状态
  const previewPage = ref(0);
  const previewPageSize = ref(40);
  const previewTotalElements = ref(0);
  /**
   * 预览数据来源：
   * - 'all'       → 全部媒体（按 previewPageSize 分页，可翻页/跨页连播）
   * - 'selection' → 只预览已勾选的文件（就是一屏，不翻页）
   * 改造前没有这个区分：相册永远只有第一页 40 张，翻不到第 2 页。
   */
  const previewSource = ref('all');
  const previewTotalPages = computed(() => {
    if (previewSource.value === 'selection') return 1;
    return Math.max(1, Math.ceil(previewTotalElements.value / previewPageSize.value));
  });
  const previewLoadingPage = ref(false);

  const selectedMediaFiles = computed(() =>
    Array.from(selectedMediaFileIds.value)
      .map((id) => mediaFileCache.value.get(id))
      .filter(Boolean)
  );

  const currentPreviewFile = computed(() => previewFiles.value[currentPreviewIndex.value] || null);

  const openPreview = (startFile) => {
    const list = selectedMediaFiles.value.length > 0 ? selectedMediaFiles.value : mediaFiles.value;
    if (!list.length) return;
    previewFiles.value = list;
    const startIndex = list.findIndex((f) => f.id === startFile.id);
    currentPreviewIndex.value = startIndex >= 0 ? startIndex : 0;
    previewMode.value = 'single';
    showPreviewModal.value = true;

    if (selectedMediaFiles.value.length > 0) {
      previewSource.value = 'selection';
      previewTotalElements.value = list.length;
      previewPage.value = 0;
    } else {
      // 从表格某一行进入：算出这一行在「全部媒体」里的全局序号，
      // 从而知道它属于第几个预览页，单张模式的「上一个/下一个」才能跨页继续。
      previewSource.value = 'all';
      previewTotalElements.value = mediaFilesTotal.value || list.length;
      const globalIndex = mediaFilePage.value * mediaFileSize.value
        + (startIndex >= 0 ? startIndex : 0);
      previewPage.value = Math.floor(globalIndex / previewPageSize.value);
    }
  };

  const loadPreviewPage = async (page) => {
    if (previewSource.value === 'selection') return;   // 勾选预览不翻页
    if (page < 0 || page >= previewTotalPages.value) return;
    previewLoadingPage.value = true;
    try {
      const res = await messageApi.getMediaFiles({ ...mediaQuery.value, page, size: previewPageSize.value });
      if (res) {
        const content = res.content || [];
        previewFiles.value = content;
        previewTotalElements.value = res.totalElements || 0;
        const newCache = new Map(mediaFileCache.value);
        content.forEach((file) => {
          if (file.id != null) newCache.set(file.id, file);
        });
        mediaFileCache.value = newCache;
        previewPage.value = page;
        currentPreviewIndex.value = 0;
      }
    } catch (error) {
      logger.error('加载预览页失败:', error);
    } finally {
      previewLoadingPage.value = false;
    }
  };

  const previewAllFiles = async () => {
    previewMode.value = 'gallery';
    previewSource.value = 'all';
    showPreviewModal.value = true;
    if (previewTotalElements.value !== mediaFilesTotal.value) {
      previewTotalElements.value = mediaFilesTotal.value;
    }
    await loadPreviewPage(0);
  };

  const previewGoToPage = async (page) => {
    if (page < 0 || page >= previewTotalPages.value || previewLoadingPage.value) return;
    await loadPreviewPage(page);
  };

  const setPreviewPageSize = async (size) => {
    const next = Number(size) || 40;
    if (next === previewPageSize.value) return;
    // 尽量停在当前这张图附近，而不是粗暴回到第一页
    const globalIndex = previewPage.value * previewPageSize.value + currentPreviewIndex.value;
    previewPageSize.value = next;
    const targetPage = Math.floor(globalIndex / next);
    await loadPreviewPage(Math.min(targetPage, previewTotalPages.value - 1));
    currentPreviewIndex.value = globalIndex - targetPage * next;
    if (currentPreviewIndex.value < 0 || currentPreviewIndex.value >= previewFiles.value.length) {
      currentPreviewIndex.value = 0;
    }
  };

  const enterSingleView = (index) => {
    currentPreviewIndex.value = index;
    previewMode.value = 'single';
  };

  const backToGallery = () => {
    previewMode.value = 'gallery';
  };

  const backToList = () => {
    closePreview();
  };

  const togglePreviewSelection = (id) => {
    toggleMediaFileSelection(id);
  };

  const isAllPreviewSelected = computed(() => {
    if (!previewFiles.value.length) return false;
    return previewFiles.value.every((file) => selectedMediaFileIds.value.has(file.id));
  });

  const toggleSelectAllInPreview = () => {
    const newSet = new Set(selectedMediaFileIds.value);
    const newCache = new Map(mediaFileCache.value);
    if (isAllPreviewSelected.value) {
      previewFiles.value.forEach((file) => {
        if (file.id != null) newSet.delete(file.id);
      });
    } else {
      previewFiles.value.forEach((file) => {
        if (file.id != null) {
          newSet.add(file.id);
          newCache.set(file.id, file);
        }
      });
    }
    selectedMediaFileIds.value = newSet;
    mediaFileCache.value = newCache;
  };

  const closePreview = () => {
    showPreviewModal.value = false;
    previewMode.value = 'single';
    previewFiles.value = [];
    previewPage.value = 0;
    previewTotalElements.value = 0;
    previewSource.value = 'all';
  };

  /**
   * 单张模式的上一个/下一个：走到当前页边界时自动载入相邻页，
   * 否则「下一个」在每页最后一张就停住了（40 张一页时尤其明显）。
   */
  const previewNext = async () => {
    if (currentPreviewIndex.value < previewFiles.value.length - 1) {
      currentPreviewIndex.value++;
      return;
    }
    if (previewSource.value === 'all' && previewPage.value < previewTotalPages.value - 1) {
      await loadPreviewPage(previewPage.value + 1);
    }
  };

  const previewPrev = async () => {
    if (currentPreviewIndex.value > 0) {
      currentPreviewIndex.value--;
      return;
    }
    if (previewSource.value === 'all' && previewPage.value > 0) {
      const targetPage = previewPage.value - 1;
      await loadPreviewPage(targetPage);
      currentPreviewIndex.value = Math.max(0, previewFiles.value.length - 1);
    }
  };

  const onPreviewKeydown = (e) => {
    if (!showPreviewModal.value) return;
    if (previewMode.value !== 'single') return;
    if (e.key === 'ArrowRight') previewNext();
    else if (e.key === 'ArrowLeft') previewPrev();
    else if (e.key === 'Escape') {
      if (previewMode.value === 'single' && previewFiles.value.length > 1) {
        backToGallery();
      } else {
        closePreview();
      }
    }
  };

  return {
    mediaFiles, mediaFilesLoading, mediaFilesError,
    mediaFileFilter, mediaFilePage, mediaFileSize, mediaFilesTotal,
    mediaKeyword, mediaFrom, mediaTo, mediaSort, mediaSortOptions, mediaFilterActive,
    mediaSummary, mediaSummaryLoading, loadMediaSummary, summaryOf,
    selectedMediaFileIds, selectedMediaFilesCount, selectedMediaFilesTotalSize,
    mediaFileCache, mediaErrorIds,
    isPurging, purgeTypes, purgeResult, hasPurgeSelection,
    markMediaError, isMediaError,
    formatBytes,
    loadMediaFiles, setMediaFileFilter, setMediaKeyword, setMediaRange, setMediaSort,
    setMediaPageSize, resetMediaFilters, goToMediaPage,
    toggleMediaFileSelection, selectAllMediaFiles, clearMediaFileSelection,
    deleteSelectedMediaFiles, loadAllMediaFilesForPreview, confirmPurgeMedia,
    // 预览
    showPreviewModal, previewFiles, currentPreviewIndex, currentPreviewFile, previewMode,
    previewPage, previewPageSize, previewTotalPages, previewTotalElements, previewLoadingPage,
    previewSource, setPreviewPageSize,
    openPreview, previewAllFiles, previewGoToPage, enterSingleView, backToGallery, backToList,
    togglePreviewSelection, isAllPreviewSelected, toggleSelectAllInPreview,
    closePreview, previewNext, previewPrev, onPreviewKeydown,
  };
}
