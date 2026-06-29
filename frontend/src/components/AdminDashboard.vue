<template>
  <div class="admin-dashboard">
    <div class="dashboard-header">
      <h2>系统管理</h2>
      <button class="close-btn" @click="$emit('close')">&times;</button>
    </div>

    <div class="dashboard-body">
      <!-- 加载状态指示器 -->
      <div v-if="isLoading" class="loading-overlay">
        <div class="loading-spinner"></div>
        <span class="loading-text">加载中...</span>
      </div>

      <!-- 组件状态与控制 -->
      <div class="section-card">
        <h4>组件状态与控制</h4>
        <div class="component-grid">
          <!-- AstrBot -->
          <div class="component-card">
            <div class="component-header">
              <div class="component-status-dot" :class="{ active: componentStatus.astrbot?.running }"></div>
              <span class="component-title">AstrBot</span>
              <span class="component-status-text">{{ componentStatus.astrbot?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="component-actions">
              <button
                class="btn-start"
                :disabled="isStartingAstrBot || componentStatus.astrbot?.running"
                @click="startAstrBot"
              >
                {{ isStartingAstrBot ? '启动中...' : '启动' }}
              </button>
              <button
                class="btn-stop"
                :disabled="isStoppingAstrBot || !componentStatus.astrbot?.running"
                @click="stopAstrBot"
              >
                {{ isStoppingAstrBot ? '停止中...' : '停止' }}
              </button>
            </div>
            <a href="http://localhost:6185" target="_blank" class="webui-link">
              <Icon name="globe" :size="14" /> 打开 AstrBot WebUI
            </a>
          </div>

          <!-- NapCat -->
          <div class="component-card">
            <div class="component-header">
              <div class="component-status-dot" :class="{ active: componentStatus.napcat?.running }"></div>
              <span class="component-title">NapCat</span>
              <span class="component-status-text">{{ componentStatus.napcat?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="component-actions">
              <button
                class="btn-start"
                :disabled="isStartingNapCat || componentStatus.napcat?.running"
                @click="startNapCat"
              >
                {{ isStartingNapCat ? '启动中...' : '启动' }}
              </button>
              <button
                class="btn-stop"
                :disabled="isStoppingNapCat || !componentStatus.napcat?.running"
                @click="stopNapCat"
              >
                {{ isStoppingNapCat ? '停止中...' : '停止' }}
              </button>
            </div>
            <a
              href="http://127.0.0.1:6099/webui?token=***REMOVED***"
              target="_blank"
              class="webui-link"
            >
              <Icon name="globe" :size="14" /> 打开 NapCat WebUI
            </a>
          </div>

          <!-- GPT-SoVITS -->
          <div class="component-card">
            <div class="component-header">
              <div class="component-status-dot" :class="{ active: componentStatus.gptsovits?.running }"></div>
              <span class="component-title">GPT-SoVITS</span>
              <span class="component-status-text">{{ componentStatus.gptsovits?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="component-actions">
              <button
                class="btn-start"
                :disabled="isStartingGptSovits || componentStatus.gptsovits?.running"
                @click="startGptSovits"
              >
                {{ isStartingGptSovits ? '启动中...' : '启动' }}
              </button>
              <button
                class="btn-stop"
                :disabled="isStoppingGptSovits || !componentStatus.gptsovits?.running"
                @click="stopGptSovits"
              >
                {{ isStoppingGptSovits ? '停止中...' : '停止' }}
              </button>
            </div>
            <a
              href="http://localhost:8000"
              target="_blank"
              class="webui-link"
            >
              <Icon name="globe" :size="14" /> 打开 GPT-SoVITS WebUI
            </a>
          </div>
        </div>
      </div>

      <!-- 媒体文件管理 -->
      <div class="section-card">
        <div class="section-card-header">
          <h4>媒体文件管理</h4>
          <button class="collapse-toggle" @click="isMediaCollapsed = !isMediaCollapsed">
            <Icon :name="isMediaCollapsed ? 'expand' : 'collapse'" :size="14" />
            <span>{{ isMediaCollapsed ? '展开' : '收起' }}</span>
          </button>
        </div>
        <div v-show="!isMediaCollapsed" class="media-manager-body">
          <div class="media-manager-card">
            <div class="media-summary">
            <span>共 {{ mediaFilesTotal }} 个文件，占用 {{ formatBytes(currentFilesTotalSize) }}</span>
            <span v-if="selectedMediaFilesCount > 0" class="media-selected">
              已选 {{ selectedMediaFilesCount }} 个，{{ formatBytes(selectedMediaFilesTotalSize) }}
            </span>
            <span v-else class="media-selected">已选 0 个，0 B</span>
          </div>

          <div class="media-filter">
            <button
              v-for="type in ['ALL', 'IMAGE', 'VIDEO', 'AUDIO']"
              :key="type"
              class="chart-btn"
              :class="{ active: mediaFileFilter === type }"
              @click="setMediaFileFilter(type)"
            >
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
                  <input
                    type="checkbox"
                    :checked="selectedMediaFilesCount === mediaFiles.length && mediaFiles.length > 0"
                    :disabled="mediaFilesLoading || isPurging"
                    @change="selectedMediaFilesCount === mediaFiles.length ? clearMediaFileSelection() : selectAllMediaFiles()"
                  />
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
                  <input
                    type="checkbox"
                    :checked="selectedMediaFileIds.has(file.id)"
                    :disabled="mediaFilesLoading || isPurging"
                    @change="toggleMediaFileSelection(file.id)"
                  />
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
            <button
              :disabled="mediaFilePage <= 0 || mediaFilesLoading || isPurging"
              @click="mediaFilePage--; loadMediaFiles()"
            >
              上一页
            </button>
            <span>{{ mediaFilePage + 1 }} / {{ totalPages }}</span>
            <button
              :disabled="mediaFilePage >= totalPages - 1 || mediaFilesLoading || isPurging"
              @click="mediaFilePage++; loadMediaFiles()"
            >
              下一页
            </button>
          </div>

          <div v-if="purgeResult" class="purge-result">
            成功清理 {{ purgeResult.totalDeleted }} 个文件（约 {{ purgeResult.freedMB }}）
          </div>

          <div class="media-actions">
            <button
              class="btn-preview"
              :disabled="mediaFilesLoading || isPurging || mediaFiles.length === 0"
              @click="previewAllFiles"
            >
              {{ mediaFilesLoading ? '加载中...' : '预览全部' }}
            </button>
            <button
              class="btn-delete"
              :disabled="selectedMediaFileIds.size === 0 || mediaFilesLoading || isPurging"
              @click="confirmDeleteSelected"
            >
              {{ isPurging ? '处理中...' : '删除选中' }}
            </button>
            <button
              class="btn-purge"
              :disabled="isPurging || mediaFilesLoading"
              @click="confirmPurgeByFilter"
            >
              {{ isPurging ? '清理中...' : '全部清理' }}
            </button>
          </div>
        </div>
        </div>
      </div>

      <!-- NapCat 登录 -->
      <div class="section-card">
        <h4>NapCat 登录</h4>
        <div class="login-area">
          <div v-if="qrCode" class="qrcode-box">
            <img :src="qrCode" alt="NapCat登录二维码" />
            <p>请使用QQ扫码登录</p>
            <button class="btn-refresh" @click="refreshQrCode">刷新二维码</button>
            <label class="auto-login-label">
              <input type="checkbox" v-model="autoLogin" @change="onAutoLoginChange" />
              下次自动登录
            </label>
          </div>
          <div v-else class="loading-box">
            <p>获取登录二维码中...</p>
          </div>
        </div>
      </div>

      <!-- 系统消息 -->
      <div v-if="systemMessage" class="system-message" :class="systemMessageType">
        {{ systemMessage }}
      </div>
    </div>
  </div>

  <!-- 媒体文件预览弹窗（相册风格） -->
  <div v-if="showPreviewModal" class="preview-modal" @click.self="closePreview">
    <div class="preview-content" :class="{ 'gallery-mode': previewMode === 'gallery' }">
      <button class="preview-close" @click="closePreview">×</button>

      <!-- 相册网格模式 -->
      <div v-if="previewMode === 'gallery'" class="preview-gallery">
        <div class="preview-gallery-header">
          <span class="preview-gallery-title">媒体文件预览</span>
          <small>{{ previewFiles.length }} 个文件 · 已选 {{ selectedMediaFileIds.size }} 个</small>
        </div>

        <div class="preview-gallery-body">
          <div v-if="previewFiles.length === 0" class="preview-empty">暂无文件</div>
          <div v-else class="preview-grid">
            <div
              v-for="(file, index) in previewFiles"
              :key="file.id"
              class="preview-grid-item"
              @click="enterSingleView(index)"
            >
              <div
                class="preview-select-circle"
                :class="{ selected: selectedMediaFileIds.has(file.id) }"
                @click.stop="togglePreviewSelection(file.id)"
              >
                <span v-if="selectedMediaFileIds.has(file.id)">✓</span>
              </div>

              <div class="preview-thumbnail">
                <img
                  v-if="file.fileType === 'IMAGE' && file.url && !isMediaError(file.id)"
                  :src="file.url"
                  :alt="file.fileName"
                  loading="lazy"
                  @error="markMediaError(file.id)"
                />
                <img
                  v-else-if="file.fileType === 'IMAGE'"
                  src="/deleted-image.svg"
                  :alt="file.fileName + '（已删除）'"
                  class="preview-thumbnail-placeholder"
                  style="width: 64px; height: 64px; object-fit: contain;"
                />
                <video
                  v-else-if="file.fileType === 'VIDEO' && file.url && !isMediaError(file.id)"
                  :src="file.url"
                  preload="metadata"
                  @error="markMediaError(file.id)"
                ></video>
                <img
                  v-else-if="file.fileType === 'VIDEO'"
                  src="/deleted-video.svg"
                  :alt="file.fileName + '（已删除）'"
                  class="preview-thumbnail-placeholder"
                  style="width: 64px; height: 64px; object-fit: contain;"
                />
                <div v-else-if="file.fileType === 'AUDIO'" class="preview-thumbnail-audio">
                  <span>🎵</span>
                </div>
                <div v-else class="preview-thumbnail-file">
                  <span>📄</span>
                </div>
              </div>

              <div class="preview-grid-info">
                <span class="preview-grid-name" :title="file.fileName">{{ file.fileName }}</span>
                <small>{{ formatBytes(file.fileSize) }}</small>
              </div>
            </div>
          </div>
        </div>

        <div class="preview-gallery-footer">
          <button class="preview-nav-btn" @click="toggleSelectAllInPreview">
            {{ isAllPreviewSelected ? '取消全选' : '全选' }}
          </button>
          <button class="preview-nav-btn" @click="backToList">关闭预览</button>
        </div>
      </div>

      <!-- 单张查看模式 -->
      <div v-else-if="currentPreviewFile" class="preview-single">
        <div class="preview-title">
          <span>{{ currentPreviewFile.fileName }}</span>
          <small>{{ formatBytes(currentPreviewFile.fileSize) }} · {{ currentPreviewIndex + 1 }} / {{ previewFiles.length }}</small>
        </div>

        <div class="preview-media" @click.self="backToGallery">
          <img
            v-if="currentPreviewFile.fileType === 'IMAGE' && currentPreviewFile.url && !isMediaError(currentPreviewFile.id)"
            :src="currentPreviewFile.url"
            :alt="currentPreviewFile.fileName"
            class="preview-img"
            @error="markMediaError(currentPreviewFile.id)"
          />
          <img
            v-else-if="currentPreviewFile.fileType === 'IMAGE'"
            src="/deleted-image.svg"
            :alt="currentPreviewFile.fileName + '（已删除）'"
            class="preview-img"
          />
          <video
            v-else-if="currentPreviewFile.fileType === 'VIDEO' && currentPreviewFile.url && !isMediaError(currentPreviewFile.id)"
            :src="currentPreviewFile.url"
            controls
            class="preview-video"
            @error="markMediaError(currentPreviewFile.id)"
          ></video>
          <img
            v-else-if="currentPreviewFile.fileType === 'VIDEO'"
            src="/deleted-video.svg"
            :alt="currentPreviewFile.fileName + '（已删除）'"
            class="preview-video"
          />
          <audio
            v-else-if="currentPreviewFile.fileType === 'AUDIO'"
            :src="currentPreviewFile.url"
            controls
            class="preview-audio"
          ></audio>
          <div v-else class="preview-unsupported">
            暂不支持预览该类型文件
          </div>
        </div>

        <div class="preview-nav">
          <button
            class="preview-nav-btn"
            :disabled="currentPreviewIndex <= 0"
            @click="previewPrev"
          >
            ← 上一个
          </button>
          <button class="preview-nav-btn" @click="backToGallery">
            返回相册
          </button>
          <button
            class="preview-nav-btn"
            :disabled="currentPreviewIndex >= previewFiles.length - 1"
            @click="previewNext"
          >
            下一个 →
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { computed, onMounted, ref } from 'vue';
import Icon from './Icon.vue';
import { useAdminDashboard } from '../composables/useAdminDashboard';

export default {
  name: 'AdminDashboard',
  components: { Icon },
  emits: ['close'],
  setup() {
    const dashboard = useAdminDashboard();

    const currentFilesTotalSize = computed(() =>
      dashboard.mediaFiles.value.reduce((sum, file) => sum + (file.fileSize || 0), 0)
    );

    const totalPages = computed(() =>
      Math.ceil(dashboard.mediaFilesTotal.value / dashboard.mediaFileSize.value) || 1
    );

    const formatDate = (value) => {
      if (!value) return '-';
      const d = new Date(value);
      return isNaN(d.getTime()) ? value : d.toLocaleString('zh-CN');
    };

    const confirmDeleteSelected = () => {
      if (!window.confirm(`确定删除选中的 ${dashboard.selectedMediaFileIds.value.size} 个文件吗？`)) return;
      dashboard.deleteSelectedMediaFiles();
    };

    const confirmPurgeByFilter = () => {
      const filter = dashboard.mediaFileFilter.value;
      const labels = { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' };
      if (!window.confirm(`确定清理${labels[filter] || ''}媒体文件吗？清理后不可恢复。`)) return;
      dashboard.purgeTypes.value = {
        IMAGE: filter === 'ALL' || filter === 'IMAGE',
        VIDEO: filter === 'ALL' || filter === 'VIDEO',
        AUDIO: filter === 'ALL' || filter === 'AUDIO',
      };
      dashboard.confirmPurgeMedia();
    };

    // 媒体管理区域收起状态
    const isMediaCollapsed = ref(false);

    // 预览相关状态
    const showPreviewModal = ref(false);
    const previewFiles = ref([]);
    const currentPreviewIndex = ref(0);
    const previewMode = ref('single'); // 'gallery' | 'single'

    // 跨页选中的文件：从缓存中读取，使翻页后仍保留选择并可一起预览
    const selectedMediaFiles = computed(() =>
      Array.from(dashboard.selectedMediaFileIds.value)
        .map((id) => dashboard.mediaFileCache.value.get(id))
        .filter(Boolean)
    );

    const currentPreviewFile = computed(() => previewFiles.value[currentPreviewIndex.value] || null);

    const openPreview = (startFile) => {
      // 如果当前有选中文件，则预览选中的文件；否则预览当前页全部文件
      const list = selectedMediaFiles.value.length > 0 ? selectedMediaFiles.value : dashboard.mediaFiles.value;
      if (!list.length) return;
      previewFiles.value = list;
      const startIndex = list.findIndex((f) => f.id === startFile.id);
      currentPreviewIndex.value = startIndex >= 0 ? startIndex : 0;
      previewMode.value = 'single';
      showPreviewModal.value = true;
    };

    /**
     * 大批量预览：加载当前筛选条件下的全部文件并打开相册网格视图。
     * 适合一次性预览上百上千个文件的场景。
     */
    const previewAllFiles = async () => {
      const allFiles = await dashboard.loadAllMediaFilesForPreview();
      if (!allFiles.length) return;
      previewFiles.value = allFiles;
      currentPreviewIndex.value = 0;
      previewMode.value = 'gallery';
      showPreviewModal.value = true;
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
      dashboard.toggleMediaFileSelection(id);
    };

    const isAllPreviewSelected = computed(() => {
      if (!previewFiles.value.length) return false;
      return previewFiles.value.every((file) => dashboard.selectedMediaFileIds.value.has(file.id));
    });

    const toggleSelectAllInPreview = () => {
      const newSet = new Set(dashboard.selectedMediaFileIds.value);
      const newCache = new Map(dashboard.mediaFileCache.value);
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
      dashboard.selectedMediaFileIds.value = newSet;
      dashboard.mediaFileCache.value = newCache;
    };

    const closePreview = () => {
      showPreviewModal.value = false;
      previewMode.value = 'single';
    };

    const previewNext = () => {
      if (currentPreviewIndex.value < previewFiles.value.length - 1) {
        currentPreviewIndex.value++;
      }
    };

    const previewPrev = () => {
      if (currentPreviewIndex.value > 0) {
        currentPreviewIndex.value--;
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

    onMounted(() => {
      dashboard.loadMediaFiles();
      window.addEventListener('keydown', onPreviewKeydown);
    });

    return {
      ...dashboard,
      currentFilesTotalSize,
      totalPages,
      formatDate,
      confirmDeleteSelected,
      confirmPurgeByFilter,
      isMediaCollapsed,
      showPreviewModal,
      previewFiles,
      currentPreviewIndex,
      currentPreviewFile,
      previewMode,
      openPreview,
      previewAllFiles,
      closePreview,
      previewNext,
      previewPrev,
      enterSingleView,
      backToGallery,
      backToList,
      togglePreviewSelection,
      isAllPreviewSelected,
      toggleSelectAllInPreview,
    };
  },
};
</script>

<style scoped>
.admin-dashboard {
  background: #fff;
  border-radius: 8px;
  max-height: 85vh;
  overflow-y: auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e8e8e8;
  position: sticky;
  top: 0;
  background: #fff;
  z-index: 10;
}

.dashboard-header h2 {
  margin: 0;
  font-size: 18px;
  color: #2c3e50;
}

.close-btn {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: #95a5a6;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}

.close-btn:hover {
  background: #f0f0f0;
}

.dashboard-body {
  padding: 20px;
}

/* 统计卡片 */
.stats-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #2c3e50;
}

.stat-label {
  font-size: 12px;
  color: #888;
  margin-top: 2px;
}

.disk-card {
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.disk-card .stat-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.disk-bar {
  width: 100%;
}

.disk-bar-track {
  width: 100%;
  height: 8px;
  background: #e8e8e8;
  border-radius: 4px;
  overflow: hidden;
}

.disk-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #4caf50, #ff9800, #f44336);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.disk-bar-label {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 11px;
  color: #999;
}

/* 图表行 */
.charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}

.chart-card {
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  padding: 16px;
}

.chart-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: #2c3e50;
}

.chart-large {
  grid-column: 1 / -1;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.chart-header h4 {
  margin: 0;
}

.chart-controls {
  display: flex;
  gap: 4px;
}

.chart-btn {
  padding: 4px 10px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}

.chart-btn:hover {
  border-color: #3498db;
  color: #3498db;
}

.chart-btn.active {
  background: #3498db;
  color: #fff;
  border-color: #3498db;
}

/* 柱状图 */
.bar-chart {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  height: 160px;
  gap: 8px;
}

.bar-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.bar-wrapper {
  width: 100%;
  height: 120px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.bar {
  width: 60%;
  border-radius: 4px 4px 0 0;
  transition: height 0.5s ease;
}

.bar-label {
  font-size: 11px;
  color: #888;
}

.bar-value {
  font-size: 11px;
  font-weight: 600;
  color: #3498db;
}

/* 线状图 */
.line-chart {
  display: flex;
  height: 200px;
  gap: 8px;
}

.line-chart-yaxis {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: flex-end;
  padding-right: 4px;
  font-size: 10px;
  color: #aaa;
  width: 28px;
  flex-shrink: 0;
}

.line-chart-scroll-wrapper {
  flex: 1;
  overflow-x: auto;
  overflow-y: hidden;
  cursor: grab;
  position: relative;
}

.line-chart-scroll-wrapper:active {
  cursor: grabbing;
}

.line-chart-scroll-wrapper::-webkit-scrollbar {
  display: none;
}

.line-chart-scroll {
  position: relative;
  height: 100%;
}

.line-chart-scroll svg {
  width: 100%;
  height: calc(100% - 24px);
  overflow: visible;
}

.data-points-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: calc(100% - 24px);
  pointer-events: none;
}

.data-point-css {
  position: absolute;
  width: 5px;
  height: 5px;
  background: #3498db;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  cursor: pointer;
  pointer-events: auto;
  transition: transform 0.2s, box-shadow 0.2s;
}

.data-point-css:hover {
  transform: translate(-50%, -50%) scale(1.8);
  box-shadow: 0 0 6px rgba(52, 152, 219, 0.5);
}

.chart-tooltip {
  position: fixed;
  background: rgba(0, 0, 0, 0.8);
  color: #fff;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  pointer-events: none;
  z-index: 1000;
  white-space: nowrap;
}

.tooltip-date {
  font-size: 11px;
  color: #ccc;
  margin-bottom: 2px;
}

.tooltip-value {
  font-weight: 600;
}

.line-chart-labels {
  display: flex;
  justify-content: space-between;
  padding-top: 4px;
}

.line-chart-labels span {
  font-size: 11px;
  color: #888;
  text-align: center;
  flex: 1;
  white-space: nowrap;
}

.line-chart-labels.hour-labels span {
  font-size: 8px;
}

/* 排行 */
.ranking-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rank-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rank-num {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #666;
  flex-shrink: 0;
}

.rank-item:nth-child(1) .rank-num {
  background: #e74c3c;
  color: #fff;
}
.rank-item:nth-child(2) .rank-num {
  background: #e67e22;
  color: #fff;
}
.rank-item:nth-child(3) .rank-num {
  background: #f1c40f;
  color: #fff;
}

.rank-name {
  width: 90px;
  font-size: 12px;
  color: #444;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 0;
}

.rank-bar-wrapper {
  flex: 1;
  height: 10px;
  background: #eee;
  border-radius: 5px;
  overflow: hidden;
}

.rank-bar {
  height: 100%;
  border-radius: 5px;
  transition: width 0.5s ease;
}

.rank-count {
  width: 40px;
  font-size: 12px;
  color: #666;
  text-align: right;
  flex-shrink: 0;
}

/* 区块卡片 */
.section-card {
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  padding: 16px;
  margin-bottom: 16px;
}

.section-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: #2c3e50;
}

.section-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-card-header h4 {
  margin: 0;
}

.collapse-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}

.collapse-toggle:hover {
  border-color: #3498db;
  color: #3498db;
}

/* 组件网格 */
.component-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.component-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 14px;
}

.component-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.component-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #e74c3c;
  transition: background 0.3s;
}

.component-status-dot.active {
  background: #27ae60;
}

.component-title {
  flex: 1;
  font-weight: 600;
  font-size: 14px;
  color: #2c3e50;
}

.component-status-text {
  font-size: 12px;
  color: #888;
}

.component-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.component-actions button {
  flex: 1;
  padding: 6px 0;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-start:not(:disabled) {
  background: #3498db;
  color: #fff;
}
.btn-start:not(:disabled):hover {
  background: #2980b9;
}

.btn-stop:not(:disabled) {
  background: #e74c3c;
  color: #fff;
}
.btn-stop:not(:disabled):hover {
  background: #c0392b;
}

.btn-webui {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  background: #f0f7ff;
  border-radius: 4px;
  color: #1976d2;
  font-size: 12px;
  cursor: pointer;
  border: none;
  text-decoration: none;
  transition: background 0.2s;
}

.btn-webui:hover {
  background: #d6e9ff;
}

.btn-webui:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: #f0f7ff;
}



.webui-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  background: #f0f7ff;
  border-radius: 4px;
  color: #1976d2;
  text-decoration: none;
  border: none;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.2s;
}

.webui-link.disabled,
.webui-link:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: #f0f7ff;
  color: #1976d2;
  font-size: 12px;
  transition: background 0.2s;
  pointer-events: none;
}

.webui-link:hover {
  background: #d6e9ff;
}

/* 媒体文件管理 */
.media-manager-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 16px;
}

.media-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #666;
}

.media-selected {
  color: #3498db;
  font-weight: 500;
}

.media-filter {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.media-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;
  gap: 10px;
  font-size: 13px;
  color: #888;
}

.media-empty {
  padding: 32px;
  text-align: center;
  font-size: 13px;
  color: #999;
}

.media-table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 14px;
  font-size: 13px;
}

.media-table th,
.media-table td {
  padding: 10px 8px;
  border-bottom: 1px solid #f0f0f0;
  text-align: left;
  color: #444;
}

.media-table th {
  font-weight: 600;
  color: #333;
  background: #fafafa;
}

.media-table tbody tr:hover {
  background: #fafafa;
}

.media-table .col-checkbox {
  width: 36px;
  text-align: center;
}

.media-table input[type="checkbox"] {
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.media-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #666;
}

.media-pagination button {
  padding: 6px 12px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: #666;
  transition: all 0.2s;
}

.media-pagination button:hover:not(:disabled) {
  border-color: #3498db;
  color: #3498db;
}

.media-pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.purge-result {
  margin-bottom: 12px;
  padding: 10px 12px;
  background-color: #f0f9ff;
  border-radius: 6px;
  font-size: 13px;
  color: #1677ff;
  text-align: center;
}

.media-actions {
  display: flex;
  gap: 10px;
}

.media-actions button {
  flex: 1;
  padding: 10px 0;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.2s;
}

.btn-delete:not(:disabled) {
  background-color: #e74c3c;
  color: #fff;
}

.btn-delete:not(:disabled):hover {
  background-color: #c0392b;
}

.btn-delete:disabled {
  background-color: #e0e0e0;
  color: #999;
  cursor: not-allowed;
}

.btn-purge:not(:disabled) {
  background-color: #f39c12;
  color: #fff;
}

.btn-purge:not(:disabled):hover {
  background-color: #e67e22;
}

.btn-purge:disabled {
  background-color: #e0e0e0;
  color: #999;
  cursor: not-allowed;
}

.file-name-cell {
  cursor: pointer;
  color: #3498db;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-cell:hover {
  text-decoration: underline;
}

.col-action {
  width: 60px;
  text-align: center;
}

.btn-preview {
  padding: 4px 10px;
  font-size: 12px;
  color: #3498db;
  background: #f0f9ff;
  border: 1px solid #b7d8f7;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-preview:hover {
  background: #e0f2ff;
  border-color: #3498db;
}

/* 预览弹窗 */
.preview-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.preview-content {
  background: #fff;
  border-radius: 8px;
  max-width: 90vw;
  max-height: 90vh;
  width: 720px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.preview-close {
  position: absolute;
  top: 10px;
  right: 14px;
  background: none;
  border: none;
  font-size: 28px;
  color: #666;
  cursor: pointer;
  z-index: 10;
}

.preview-close:hover {
  color: #333;
}

.preview-body {
  padding: 20px;
  overflow: auto;
  flex: 1;
}

.preview-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 12px;
}

.preview-title span {
  font-size: 15px;
  font-weight: 500;
  color: #333;
  word-break: break-all;
}

.preview-title small {
  font-size: 12px;
  color: #888;
  white-space: nowrap;
}

.preview-media {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  background: #f8f8f8;
  border-radius: 6px;
  overflow: hidden;
}

.preview-img {
  max-width: 100%;
  max-height: 60vh;
  object-fit: contain;
}

.preview-video {
  max-width: 100%;
  max-height: 60vh;
}

.preview-audio {
  width: 100%;
  padding: 20px;
}

.preview-unsupported {
  padding: 60px 20px;
  color: #888;
  font-size: 14px;
}

.preview-nav {
  display: flex;
  justify-content: space-between;
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
  background: #fafafa;
}

.preview-nav-btn {
  padding: 8px 16px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  transition: all 0.2s;
}

.preview-nav-btn:hover:not(:disabled) {
  border-color: #3498db;
  color: #3498db;
}

.preview-nav-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 相册网格模式 */
.preview-content.gallery-mode {
  width: 90vw;
  max-width: 1100px;
}

.preview-gallery {
  display: flex;
  flex-direction: column;
  max-height: 90vh;
}

.preview-gallery-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
  background: #fafafa;
}

.preview-gallery-title {
  font-size: 16px;
  font-weight: 500;
  color: #333;
}

.preview-gallery-header small {
  font-size: 12px;
  color: #888;
}

.preview-gallery-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  background: #fff;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 12px;
}

.preview-grid-item {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  background: #f8f8f8;
  border: 1px solid #f0f0f0;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}

.preview-grid-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.preview-select-circle {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.9);
  background: rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 2;
  transition: all 0.15s;
}

.preview-select-circle:hover {
  background: rgba(0, 0, 0, 0.45);
}

.preview-select-circle.selected {
  background: #3498db;
  border-color: #fff;
}

.preview-select-circle span {
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.preview-thumbnail {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #f0f0f0;
}

.preview-thumbnail img,
.preview-thumbnail video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-thumbnail-audio,
.preview-thumbnail-file {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  font-size: 32px;
  background: #f8f8f8;
}

.preview-grid-info {
  padding: 8px;
  background: #fff;
}

.preview-grid-name {
  display: block;
  font-size: 12px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-grid-info small {
  font-size: 11px;
  color: #999;
}

.preview-empty {
  text-align: center;
  padding: 60px 20px;
  color: #888;
  font-size: 14px;
}

.preview-gallery-footer {
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
  background: #fafafa;
  display: flex;
  justify-content: center;
}

/* 登录区域 */
.login-area {
  display: flex;
  justify-content: center;
}

.qrcode-box {
  text-align: center;
}

.qrcode-box img {
  width: 180px;
  height: 180px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
}

.qrcode-box p {
  margin: 8px 0;
  font-size: 13px;
  color: #666;
}

.btn-refresh {
  padding: 6px 14px;
  background: #f8f9fa;
  border: 1px solid #dee2e6;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.auto-login-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
}

.auto-login-label input[type="checkbox"] {
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.loading-box {
  padding: 40px;
  color: #888;
}

/* 加载状态指示器 */
.loading-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  gap: 12px;
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e8e8e8;
  border-top-color: #3498db;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.loading-text {
  font-size: 14px;
  color: #666;
}

/* 系统消息 */
.system-message {
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 13px;
  margin-top: 10px;
}

.system-message.success {
  background: #d4edda;
  color: #155724;
  border: 1px solid #c3e6cb;
}

.system-message.error {
  background: #f8d7da;
  color: #721c24;
  border: 1px solid #f5c6cb;
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-cards {
    grid-template-columns: repeat(2, 1fr);
  }
  .charts-row {
    grid-template-columns: 1fr;
  }
  .component-grid {
    grid-template-columns: 1fr;
  }
}
</style>
