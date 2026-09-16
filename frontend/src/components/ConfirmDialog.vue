<template>
  <Teleport to="body">
    <Transition name="dialog">
      <div v-if="visible" class="dialog-overlay" @click.self="handleCancel">
        <div class="dialog-container">
          <div class="dialog-header">
            <h3 class="dialog-title">{{ title }}</h3>
            <button class="dialog-close" @click="handleCancel">×</button>
          </div>
          <div class="dialog-body">
            <div class="dialog-icon" v-if="type">
              <svg v-if="type === 'warning'" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <path d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <svg v-else-if="type === 'error'" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <circle cx="12" cy="12" r="10" stroke-width="2"/>
                <path d="M8 8l8 8M16 8l-8 8" stroke-width="2" stroke-linecap="round"/>
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <circle cx="12" cy="12" r="10" stroke-width="2"/>
                <path d="M12 8v4m0 4h.01" stroke-width="2" stroke-linecap="round"/>
              </svg>
            </div>
            <p class="dialog-message">{{ message }}</p>
          </div>
          <div class="dialog-footer">
            <button class="btn btn-secondary" @click="handleCancel">{{ cancelText }}</button>
            <button class="btn" :class="`btn-${type || 'primary'}`" @click="handleConfirm">{{ confirmText }}</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script>
import { ref } from 'vue';

let resolvePromise = null;

const visible = ref(false);
const title = ref('提示');
const message = ref('');
const type = ref('');
const confirmText = ref('确定');
const cancelText = ref('取消');

export function showConfirm(options) {
  return new Promise((resolve) => {
    resolvePromise = resolve;
    
    if (typeof options === 'string') {
      message.value = options;
      title.value = '提示';
      type.value = '';
      confirmText.value = '确定';
      cancelText.value = '取消';
    } else {
      message.value = options.message || '';
      title.value = options.title || '提示';
      type.value = options.type || '';
      confirmText.value = options.confirmText || '确定';
      cancelText.value = options.cancelText || '取消';
    }
    
    visible.value = true;
  });
}

function handleConfirm() {
  visible.value = false;
  if (resolvePromise) {
    resolvePromise(true);
    resolvePromise = null;
  }
}

function handleCancel() {
  visible.value = false;
  if (resolvePromise) {
    resolvePromise(false);
    resolvePromise = null;
  }
}

export default {
  name: 'ConfirmDialog',
  setup() {
    return {
      visible,
      title,
      message,
      type,
      confirmText,
      cancelText,
      handleConfirm,
      handleCancel
    };
  }
};
</script>

<style scoped>
.dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9998;
  backdrop-filter: blur(2px);
}

.dialog-container {
  background: var(--card-bg, #fff);
  border-radius: 12px;
  width: 90%;
  max-width: 400px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  overflow: hidden;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color, #f0f0f0);
}

.dialog-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.dialog-close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none;
  border: none;
  color: var(--text-muted, #999);
  font-size: 20px;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s;
}

.dialog-close:hover {
  background: var(--bg-tertiary, #f5f5f5);
  color: var(--text-secondary, #666);
}

.dialog-body {
  padding: 24px 20px;
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.dialog-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.dialog-icon svg {
  width: 24px;
  height: 24px;
}

.dialog-icon:has(+ .dialog-message) {
  background: #fff7e6;
  color: #faad14;
}

.dialog-message {
  margin: 0;
  font-size: 14px;
  color: var(--text-primary, #333);
  line-height: 1.6;
  flex: 1;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid var(--border-color, #f0f0f0);
  background: var(--bg-tertiary, #fafafa);
}

.btn {
  padding: 8px 20px;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid transparent;
}

.btn-secondary {
  background: var(--card-bg, #fff);
  border-color: var(--border-color, #d9d9d9);
  color: var(--text-secondary, #666);
}

.btn-secondary:hover {
  border-color: #40a9ff;
  color: #40a9ff;
}

.btn-primary {
  background: #1890ff;
  color: #fff;
}

.btn-primary:hover {
  background: #40a9ff;
}

.btn-warning {
  background: #faad14;
  color: #fff;
}

.btn-warning:hover {
  background: #ffc53d;
}

.btn-error {
  background: #ff4d4f;
  color: #fff;
}

.btn-error:hover {
  background: #ff7875;
}

/* 动画效果 */
.dialog-enter-active,
.dialog-leave-active {
  transition: all 0.3s ease;
}

.dialog-enter-from .dialog-container,
.dialog-leave-to .dialog-container {
  opacity: 0;
  transform: scale(0.9) translateY(-20px);
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.dialog-enter-active .dialog-container,
.dialog-leave-active .dialog-container {
  transition: all 0.3s ease;
}
</style>
