import { ref } from 'vue';

// 模块级单例状态 —— 所有组件共享同一份预览窗口
const visible = ref(false);
const currentUrl = ref('');
const imageList = ref([]); // Array<{url, alt?}> 或 Array<string>
const currentIndex = ref(0);

const normalizeUrl = (item) => {
  if (typeof item === 'string') return item;
  return item?.url || item?.src || item?.fileUrl || '';
};

// 键盘处理函数引用（模块级，用于注册/移除）
let keyHandler = null;

const createKeyHandler = () => {
  return (e) => {
    if (!visible.value) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      close();
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      goPrev();
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      goNext();
    }
  };
};

const open = (target, list) => {
  const url = normalizeUrl(target);
  if (!url) return;
  if (Array.isArray(list) && list.length > 0) {
    const urls = list.map(normalizeUrl).filter(Boolean);
    const idx = urls.indexOf(url);
    imageList.value = urls;
    currentIndex.value = idx >= 0 ? idx : 0;
    currentUrl.value = urls[currentIndex.value] || url;
  } else {
    imageList.value = [url];
    currentIndex.value = 0;
    currentUrl.value = url;
  }
  visible.value = true;
  document.body.style.overflow = 'hidden';

  // 注册键盘监听（先移除旧监听防重复）
  if (keyHandler) {
    window.removeEventListener('keydown', keyHandler);
  }
  keyHandler = createKeyHandler();
  window.addEventListener('keydown', keyHandler);
};

const close = () => {
  visible.value = false;
  currentUrl.value = '';
  imageList.value = [];
  currentIndex.value = 0;
  document.body.style.overflow = '';

  // 移除键盘监听
  if (keyHandler) {
    window.removeEventListener('keydown', keyHandler);
    keyHandler = null;
  }
};

const goPrev = () => {
  if (imageList.value.length <= 1) return;
  currentIndex.value = (currentIndex.value - 1 + imageList.value.length) % imageList.value.length;
  currentUrl.value = imageList.value[currentIndex.value];
};

const goNext = () => {
  if (imageList.value.length <= 1) return;
  currentIndex.value = (currentIndex.value + 1) % imageList.value.length;
  currentUrl.value = imageList.value[currentIndex.value];
};

export function useImagePreview() {
  return {
    visible,
    currentUrl,
    imageList,
    currentIndex,
    open,
    close,
    goPrev,
    goNext,
    hasMultiple: () => imageList.value.length > 1,
  };
}