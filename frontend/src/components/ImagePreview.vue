<template>
  <Teleport to="body">
    <Transition name="lp-fade">
      <div
        v-if="visible"
        class="lp-lightbox"
        @click.self="close"
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
            alt="预览"
            draggable="false"
            @click.stop
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

        <!-- 页码 -->
        <div v-if="hasMultiple()" class="lp-counter">
          {{ currentIndex + 1 }} / {{ imageList.length }}
        </div>

        <!-- 提示 -->
        <div class="lp-hint">
          ESC 关闭 · ← → 切换
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script>
import { ref, watch } from 'vue';
import { useImagePreview } from '../composables/useImagePreview';

export default {
  name: 'ImagePreview',
  setup() {
    const { visible, currentUrl, imageList, currentIndex, close, goPrev, goNext, hasMultiple } = useImagePreview();
    const loadFailed = ref(false);
    const reloadKey = ref(0);   // 变化时强制重建 <img>（配合 key），用于"重试"

    const onImgError = () => { loadFailed.value = true; };
    const retry = () => { loadFailed.value = false; reloadKey.value += 1; };

    /**
     * 关键修复：切换图片时必须重置失败状态。
     * 原来 loadFailed 只在 @load 里重置，而失败后 <img> 被 v-if 移除、根本不会再触发 load →
     * 一张图失败后，后面每一张都停在"图片加载失败"，且左右切换也救不回来。
     */
    watch(currentUrl, () => { loadFailed.value = false; });

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
}
</style>
