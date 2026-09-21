<template>
  <div class="state-panel" :class="[`is-${state}`, { 'is-compact': compact }]" role="status" :aria-live="state === 'loading' ? 'polite' : 'off'">
    <!-- 加载态：转圈 -->
    <div v-if="state === 'loading'" class="state-spinner" aria-hidden="true"></div>

    <!-- 空态：图标 -->
    <svg v-else-if="state === 'empty'" class="state-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2"/>
      <rect x="9" y="3" width="6" height="4" rx="1"/>
      <path d="M9 12h6M9 16h4"/>
    </svg>

    <!-- 错误态：警示图标（与空态明确区分，避免"看不出是失败了"） -->
    <svg v-else class="state-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
      <circle cx="12" cy="12" r="9"/>
      <path d="M12 8v5M12 16h.01" stroke-linecap="round"/>
    </svg>

    <p class="state-title">{{ resolvedTitle }}</p>
    <p v-if="hint" class="state-hint">{{ hint }}</p>
    <button v-if="actionText" type="button" class="state-action" @click="$emit('action')">{{ actionText }}</button>
  </div>
</template>

<script>
/**
 * 列表页统一的「加载 / 空 / 错误」状态面板（批次 F.2）。
 *
 * 为什么统一：各页原先自绘状态（`audit-loading` / `audit-empty` / `orders-empty` / 自带 spinner），
 * 同一套语义在不同页面长得不一样，更严重的是**错误态复用了空态样式**——
 * 接口失败时页面显示"暂无数据"，用户与运维都看不出是"没有数据"还是"请求失败"。
 * 这里把三种状态收敛为一个组件：错误态有独立图标与配色，并可挂「重试」按钮。
 *
 * 用法：
 *   <StatePanel state="loading" compact />                     表格行内
 *   <StatePanel state="empty" title="暂无订单数据" compact />
 *   <StatePanel state="error" :title="errMsg" action-text="重试" @action="load" />
 */
export default {
  name: 'StatePanel',
  props: {
    /** loading | empty | error */
    state: { type: String, default: 'empty' },
    /** 覆盖默认文案（空态/错误态建议传业务化文案） */
    title: { type: String, default: '' },
    /** 补充说明（可选） */
    hint: { type: String, default: '' },
    /** 有值时显示操作按钮（如「重试」「去升级权益」） */
    actionText: { type: String, default: '' },
    /** 紧凑模式：用于表格行内，去掉大段上下留白 */
    compact: { type: Boolean, default: false },
  },
  emits: ['action'],
  computed: {
    resolvedTitle() {
      if (this.title) return this.title;
      if (this.state === 'loading') return '加载中...';
      if (this.state === 'error') return '加载失败，请稍后重试';
      return '暂无数据';
    },
  },
};
</script>

<style scoped>
.state-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 20px;
  color: var(--text-muted, #888);
  text-align: center;
}
.state-panel.is-compact { padding: 28px 12px; gap: 8px; }

.state-title { margin: 0; font-size: 13px; line-height: 1.6; }
.state-hint { margin: 0; font-size: 12px; line-height: 1.6; color: var(--text-muted, #999); }

.state-icon { width: 28px; height: 28px; color: var(--text-muted, #bbb); }
.state-panel.is-compact .state-icon { width: 24px; height: 24px; }

/* 错误态：用警示色与空态明确区分 */
.state-panel.is-error .state-icon { color: var(--danger-color, #e74c3c); }
.state-panel.is-error .state-title { color: var(--danger-color, #e74c3c); }

.state-spinner {
  width: 24px;
  height: 24px;
  border: 3px solid var(--border-color, #f3f3f3);
  border-top-color: var(--accent-color, #3498db);
  border-radius: 50%;
  animation: state-spin 1s linear infinite;
}
@keyframes state-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.state-action {
  margin-top: 2px;
  padding: 6px 16px;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
  border: 1px solid var(--accent-color, #3498db);
  background: transparent;
  color: var(--accent-color, #3498db);
  transition: background 0.2s, color 0.2s;
}
.state-action:hover {
  background: var(--accent-color, #3498db);
  color: #fff;
}
</style>
