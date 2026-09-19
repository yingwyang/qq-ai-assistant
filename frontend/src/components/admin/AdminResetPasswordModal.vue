<template>
  <div v-if="state.visible" class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content small-modal credit-modal">
      <div class="modal-header">
        <h3>重置用户密码</h3>
        <button class="btn-close" @click="$emit('close')">×</button>
      </div>
      <div class="modal-body">
        <div class="refund-order-info">
          <div><label>用户：</label><span>{{ state.username }}</span></div>
        </div>

        <p class="reset-hint">规则：8-64 位，且同时包含大写字母、小写字母与数字。重置后该用户已签发的登录态会立即失效。</p>

        <div class="form-group">
          <label>新密码 <span class="required">*</span></label>
          <div class="reset-input-row">
            <input
              v-model="state.password"
              type="text"
              placeholder="请输入或点击右侧生成"
              autocomplete="off"
              @input="$emit('validate')"
            />
            <button class="btn-action" type="button" @click="$emit('generate')">生成随机</button>
            <button class="btn-action" type="button" :disabled="!state.password" @click="$emit('copy')">复制</button>
          </div>
        </div>

        <p v-if="state.password && !state.valid" class="reset-error">密码不符合规则：需 8-64 位且含大小写字母与数字。</p>
        <p v-else-if="state.valid" class="reset-ok">密码格式符合规则。</p>
      </div>
      <div class="modal-footer">
        <button class="btn-cancel" @click="$emit('close')">取消</button>
        <button
          class="btn-save"
          :disabled="state.submitting || !state.valid"
          @click="$emit('submit')"
        >
          {{ state.submitting ? '提交中...' : '确认重置' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
/**
 * 重置用户密码弹窗。
 * 早期用 window.prompt 让管理员手输明文密码：无法校验、无法生成、也无法复制，
 * 这里改为弹窗 + 规则校验 + 随机生成。
 */
export default {
  name: 'AdminResetPasswordModal',
  props: {
    state: { type: Object, required: true },
  },
  emits: ['close', 'submit', 'generate', 'copy', 'validate'],
};
</script>

<style scoped>
.required { color: #e74c3c; }
.reset-hint { font-size: 12.5px; color: var(--text-muted, #888); line-height: 1.6; margin: 0 0 14px 0; }
.reset-input-row { display: flex; gap: 8px; align-items: center; }
.reset-input-row input { flex: 1; min-width: 0; }
.reset-error { font-size: 12.5px; color: #e74c3c; margin: 8px 0 0 0; }
.reset-ok { font-size: 12.5px; color: var(--success-color, #27ae60); margin: 8px 0 0 0; }
</style>
