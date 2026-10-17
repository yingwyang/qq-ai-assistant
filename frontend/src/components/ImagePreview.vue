<template>
  <Teleport to="body">
    <Transition name="lp-fade">
      <div
        v-if="visible"
        class="lp-lightbox"
        @click.self="close"
        @wheel.prevent="onWheel"
        role="dialog"
        aria-modal="true"
        aria-label="图片预览"
      >
        <!-- 关闭按钮 -->
        <button
          class="lp-btn lp-close"
          type="button"
          aria-label="关闭预览"
          @click.stop="close"
        >
          <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>

        <!-- 上一张 -->
        <button
          v-if="hasMultiple()"
          class="lp-btn lp-prev"
          type="button"
          aria-label="上一张"
          @click.stop="goPrev"
        >
          <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
        </button>

        <!-- 下一张 -->
        <button
          v-if="hasMultiple()"
          class="lp-btn lp-next"
          type="button"
          aria-label="下一张"
          @click.stop="goNext"
        >
          <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>

        <!-- 图片容器 -->
        <div class="lp-stage">
          <img
            v-if="currentUrl && !loadFailed"
            :key="currentUrl + '#' + reloadKey"
            :src="currentUrl"
            class="lp-image"
            :class="{ 'is-zoomed': scale !== 1, 'is-dragging': dragging }"
            :style="imageStyle"
            alt="预览"
            draggable="false"
            @click.stop
            @pointerdown="onPointerDown"
            @pointermove="onPointerMove"
            @pointerup="onPointerUp"
            @pointercancel="onPointerUp"
            @load="loadFailed = false"
            @error="onImgError"
          />
          <div v-else class="lp-fallback">
            <svg viewBox="0 0 64 64" width="72" height="72" stroke="currentColor" fill="none" stroke-width="2">
              <rect x="4" y="12" width="56" height="40" rx="4"/>
              <circle cx="20" cy="28" r="4"/>
              <path d="M52 50 L36 34 L24 46 L8 30 L8 52 L52 52 Z"/>
              <line x1="10" y1="10" x2="54" y2="54" stroke-linecap="round" opacity="0.7"/>
            </svg>
            <span>图片加载失败</span>
            <span class="lp-fallback-tip">可能已过期、未缓存，或本地媒体文件已被清理</span>
            <button class="lp-retry" type="button" @click.stop="retry">重试</button>
          </div>
        </div>

        <!-- 缩放工具条：小图看不清细节、长图一屏放不下，原来只能按窗口大小看 -->
        <div class="lp-zoom" role="group" aria-label="缩放控制">
          <button
            class="lp-zoom-btn"
            type="button"
            aria-label="缩小"
            :disabled="scale <= minScale"
            @click.stop="zoomOut"
          >−</button>
          <button
            class="lp-zoom-value"
            type="button"
            :aria-label="'当前缩放 ' + zoomPercent + '%，点击复位为原始大小'"
            :title="'复位为原始大小（快捷键 0）'"
            @click.stop="resetZoom"
          >{{ zoomPercent }}%</button>
          <button
            class="lp-zoom-btn"
            type="button"
            aria-label="放大"
            :disabled="scale >= maxScale"
            @click.stop="zoomIn"
          >+</button>
        </div>

        <!-- 页码 -->
        <div v-if="hasMultiple()" class="lp-counter">
          {{ currentIndex + 1 }} / {{ imageList.length }}
        </div>

        <!-- 提示 -->
        <div class="lp-hint">
          ESC 关闭 · ← → 切换 · + − 缩放 · 0 复位 · 滚轮缩放
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { useImagePreview } from '../composables/useImagePreview';

export default {
  name: 'ImagePreview',
  setup() {
    const { visible, currentUrl, imageList, currentIndex, close, goPrev, goNext, hasMultiple } = useImagePreview();
    const loadFailed = ref(false);
    const reloadKey = ref(0);   // 变化时强制重建 <img>（配合 key），用于"重试"

    const onImgError = () => { loadFailed.value = true; };
    const retry = () => { loadFailed.value = false; reloadKey.value += 1; };

    // ===== 缩放 =====
    // 此前 lightbox 只能按窗口大小看一张图：小图看不清细节、长图缩不到一屏、也不能局部看。
    const MIN_SCALE = 0.5;
    const MAX_SCALE = 5;
    const STEP = 1.25;
    const scale = ref(1);
    const offset = ref({ x: 0, y: 0 });
    const dragging = ref(false);
    let dragStart = null;

    const clampScale = (v) => Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.round(v * 100) / 100));
    const zoomPercent = computed(() => Math.round(scale.value * 100));

    const zoomIn = () => { scale.value = clampScale(scale.value * STEP); };
    const zoomOut = () => {
      scale.value = clampScale(scale.value / STEP);
      if (scale.value <= 1) offset.value = { x: 0, y: 0 };  // 回到一屏内时消除平移
    };
    const resetZoom = () => { scale.value = 1; offset.value = { x: 0, y: 0 }; };

    const imageStyle = computed(() => ({
      transform: 'translate(' + offset.value.x + 'px, ' + offset.value.y + 'px) scale(' + scale.value + ')',
      transition: dragging.value ? 'none' : 'transform 0.12s ease',
      cursor: scale.value > 1 ? (dragging.value ? 'grabbing' : 'grab') : 'default',
    }));

    /** 滚轮缩放（lightbox 是全屏模态，滚动本来也无处可滚，直接用于缩放） */
    const onWheel = (e) => {
      if (e.deltaY < 0) zoomIn();
      else if (e.deltaY > 0) zoomOut();
    };

    /** 放大后可拖动查看局部（pointer 事件，兼容鼠标与触摸） */
    const onPointerDown = (e) => {
      if (scale.value <= 1) return;
      dragging.value = true;
      dragStart = { x: e.clientX - offset.value.x, y: e.clientY - offset.value.y };
      if (e.target && e.target.setPointerCapture) {
        try { e.target.setPointerCapture(e.pointerId); } catch { /* 某些环境不支持捕获 */ }
      }
    };
    const onPointerMove = (e) => {
      if (!dragging.value || !dragStart) return;
      offset.value = { x: e.clientX - dragStart.x, y: e.clientY - dragStart.y };
    };
    const onPointerUp = (e) => {
      if (!dragging.value) return;
      dragging.value = false;
      dragStart = null;
      if (e.target && e.target.releasePointerCapture) {
        try { e.target.releasePointerCapture(e.pointerId); } catch { /* ignore */ }
      }
    };

    /** 快捷键 + / − / 0（与 composable 的 ESC、←→ 分工，互不覆盖） */
    const onZoomKey = (e) => {
      if (!visible.value) return;
      if (e.key === '+' || e.key === '=') { e.preventDefault(); zoomIn(); }
      else if (e.key === '-' || e.key === '_') { e.preventDefault(); zoomOut(); }
      else if (e.key === '0') { e.preventDefault(); resetZoom(); }
    };
    onMounted(() => window.addEventListener('keydown', onZoomKey));
    onBeforeUnmount(() => window.removeEventListener('keydown', onZoomKey));

    /**
     * 关键修复：切换图片时必须重置失败状态。
     * 原来 loadFailed 只在 @load 里重置，而失败后 <img> 被 v-if 移除、根本不会再触发 load →
     * 一张图失败后，后面每一张都停在"图片加载失败"，且左右切换也救不回来。
     */
    watch(currentUrl, () => { loadFailed.value = false; resetZoom(); });
    watch(visible, (v) => { if (!v) resetZoom(); });

    return {
      visible,
      currentUrl,
      imageList,
      currentIndex,
      close,
      goPrev,
      goNext,
      hasMultiple,
      loadFailed,
      reloadKey,
      onImgError,
      retry,
      // 缩放
      scale,
      dragging,
      zoomPercent,
      minScale: MIN_SCALE,
      maxScale: MAX_SCALE,
      zoomIn,
      zoomOut,
      resetZoom,
      imageStyle,
      onWheel,
      onPointerDown,
      onPointerMove,
      onPointerUp,
    };
  }
};
</script>

<style scoped>
.lp-lightbox {
  position: fixed;
  inset: 0;
  z-index: 99999;
  background-color: rgba(0, 0, 0, 0.92);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 40px;
  /* 放大后的图片靠 transform 超出布局尺寸，这里裁掉，避免出现页面级滚动条 */
  overflow: hidden;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}

.lp-stage {
  max-width: 100%;
  max-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.lp-image {
  max-width: calc(100vw - 120px);
  max-height: calc(100vh - 120px);
  object-fit: contain;
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
  border-radius: 6px;
  pointer-events: auto;
  transition: opacity 0.2s ease;
}

.lp-fallback {
  color: #bbb;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  font-size: 15px;
}

.lp-fallback-tip {
  max-width: 320px;
  text-align: center;
  font-size: 12.5px;
  line-height: 1.6;
  color: #8a8a8a;
}

.lp-retry {
  padding: 7px 20px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: rgba(255, 255, 255, 0.1);
  color: #eee;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.lp-retry:hover { background: rgba(255, 255, 255, 0.2); }

.lp-btn {
  position: absolute;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  border: 1px solid rgba(255,255,255,0.15);
  width: 52px;
  height: 52px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.lp-btn:hover {
  background: rgba(255, 255, 255, 0.18);
  transform: scale(1.05);
  color: #fff;
}
.lp-btn:active { transform: scale(0.96); }

.lp-close { top: 24px; right: 28px; }
.lp-prev  { left: 28px;  top: 50%; margin-top: -26px; }
.lp-next  { right: 28px; top: 50%; margin-top: -26px; }

.lp-counter {
  position: absolute;
  bottom: 32px;
  left: 50%;
  transform: translateX(-50%);
  color: rgba(255,255,255,0.85);
  font-size: 14px;
  padding: 6px 14px;
  background: rgba(255,255,255,0.08);
  border-radius: 999px;
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  letter-spacing: 0.5px;
}

.lp-hint {
  position: absolute;
  bottom: 32px;
  right: 32px;
  color: rgba(255,255,255,0.5);
  font-size: 12px;
  pointer-events: none;
}

/* 缩放工具条：放左下角，避开底部居中的页码与右下角的提示 */
.lp-zoom {
  position: absolute;
  bottom: 32px;
  left: 32px;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.lp-zoom-btn {
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: #fff;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease;
}
.lp-zoom-btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.18); }
.lp-zoom-btn:disabled { opacity: 0.35; cursor: not-allowed; }
.lp-zoom-value {
  min-width: 58px;
  height: 34px;
  padding: 0 6px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: rgba(255, 255, 255, 0.88);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: background 0.15s ease;
}
.lp-zoom-value:hover { background: rgba(255, 255, 255, 0.12); }
.lp-zoom-btn:focus-visible,
.lp-zoom-value:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.7);
  outline-offset: 2px;
}

/* 放大后可拖动查看局部 */
.lp-image.is-zoomed { will-change: transform; }
.lp-image.is-dragging { will-change: transform; }

/* 过渡动画 */
.lp-fade-enter-active,
.lp-fade-leave-active {
  transition: opacity 0.18s ease;
}
.lp-fade-enter-from,
.lp-fade-leave-to {
  opacity: 0;
}

@media (max-width: 768px) {
  .lp-lightbox { padding: 40px 12px; }
  .lp-image {
    max-width: calc(100vw - 24px);
    max-height: calc(100vh - 120px);
  }
  .lp-btn { width: 44px; height: 44px; }
  .lp-close { top: 12px; right: 12px; }
  .lp-prev  { left: 8px;  }
  .lp-next  { right: 8px; }
  .lp-hint  { display: none; }
  .lp-zoom { bottom: 20px; left: 12px; }
  .lp-zoom-btn { width: 32px; height: 32px; font-size: 18px; }
  .lp-zoom-value { min-width: 52px; font-size: 12px; }
}
</style>
