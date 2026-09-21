<template>
  <Teleport to="body">
    <Transition name="pay-fade">
      <div v-if="visible" class="pay-overlay" @click.self="close">
        <div
          class="pay-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="pay-dialog-title"
          tabindex="-1"
          ref="dialogRef"
        >
          <!-- 头部：标题 + 步骤条 -->
          <div class="pay-header">
            <div class="pay-header-main">
              <h3 id="pay-dialog-title" class="pay-title">
                {{ isResultStep ? '订单已提交' : '确认订单' }}
              </h3>
              <p class="pay-subtitle">
                {{ isResultStep
                  ? '请按下方指引完成付款，管理员确认到账后自动发放权益'
                  : '本系统采用人工确认收款，无需扫码支付；提交订单后由管理员核对到账' }}
              </p>
            </div>
            <button class="pay-close" type="button" aria-label="关闭" @click="close">×</button>
          </div>

          <div class="pay-steps" aria-hidden="true">
            <div v-for="(s, i) in steps" :key="s.key" class="pay-step"
                 :class="{ 'is-done': stepIndex > i, 'is-active': stepIndex === i }">
              <span class="pay-step-dot">{{ stepIndex > i ? '✓' : i + 1 }}</span>
              <span class="pay-step-label">{{ s.label }}</span>
              <span v-if="i < steps.length - 1" class="pay-step-line"></span>
            </div>
          </div>

          <!-- 第一步：确认订单 -->
          <div v-if="!isResultStep" class="pay-body">
            <div class="pay-product">
              <div class="pay-product-head">
                <span class="pay-product-name">{{ plan?.planName || '-' }}</span>
                <span class="pay-product-type" :class="isMonthCard ? 'is-month' : 'is-direct'">{{ planTypeText }}</span>
              </div>
              <div class="pay-product-meta">
                <span>获得积分 <b>+{{ plan?.credits || 0 }}</b></span>
                <span>有效期 <b>{{ durationText }}</b></span>
                <span v-if="isMonthCard">计费方式 <b>一次性支付</b></span>
              </div>
              <ul v-if="features.length" class="pay-product-features">
                <li v-for="(f, i) in features" :key="i">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
                  <span>{{ f }}</span>
                </li>
              </ul>
            </div>

            <div class="pay-block">
              <h5 class="pay-block-title">金额明细</h5>
              <div class="pay-amount-list">
                <div class="pay-amount-row"><span>商品金额</span><span>¥{{ amountText }}</span></div>
                <div class="pay-amount-row"><span>优惠减免</span><span class="pay-amount-none">-¥0.00</span></div>
                <div class="pay-amount-row is-total"><span>应付金额</span><span class="pay-amount-total">¥{{ amountText }}</span></div>
              </div>
            </div>

            <div class="pay-block">
              <h5 class="pay-block-title">支付方式</h5>
              <div class="pay-methods" role="radiogroup" aria-label="支付方式">
                <button
                  type="button"
                  class="pay-method"
                  :class="{ 'is-selected': method === 'MANUAL' }"
                  role="radio"
                  :aria-checked="method === 'MANUAL'"
                  @click="method = 'MANUAL'"
                >
                  <span class="pay-method-radio"></span>
                  <span class="pay-method-main">
                    <span class="pay-method-name">
                      人工确认收款
                      <span class="pay-method-tag is-recommend">当前通道</span>
                    </span>
                    <span class="pay-method-desc">提交订单后按管理员指引完成付款，管理员核对到账后立即发放积分与权益</span>
                  </span>
                </button>
                <button type="button" class="pay-method is-disabled" disabled aria-disabled="true">
                  <span class="pay-method-radio"></span>
                  <span class="pay-method-main">
                    <span class="pay-method-name">
                      在线支付（微信 / 支付宝）
                      <span class="pay-method-tag is-muted">未开通</span>
                    </span>
                    <span class="pay-method-desc">当前部署未接入在线支付通道，无需扫码；如已接入可由管理员开启</span>
                  </span>
                </button>
              </div>
            </div>

            <div class="pay-notes">
              <h5 class="pay-block-title">服务须知</h5>
              <ol>
                <li>提交订单后状态为「待确认收款」，未确认前不会扣减或发放任何积分权益。</li>
                <li>管理员确认到账后，积分与权益自动到账，可在「我的订单」查看订单时间轴。</li>
                <li>待确认收款的订单可随时取消；已支付订单如需退款，可发起退款申请。</li>
              </ol>
            </div>
          </div>

          <!-- 第二步：完成付款 -->
          <div v-else class="pay-body">
            <div class="pay-result">
              <div class="pay-result-icon" :class="resultStatus === 'PENDING' ? 'is-pending' : 'is-paid'">
                <svg v-if="resultStatus === 'PENDING'" width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2" stroke-linecap="round"/>
                </svg>
                <svg v-else width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M20 6L9 17l-5-5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </div>
              <h4 class="pay-result-title">{{ resultTitle }}</h4>
              <p class="pay-result-desc">{{ resultMessage }}</p>

              <div class="pay-result-order">
                <div class="pay-result-order-main">
                  <span class="pay-result-order-label">订单号</span>
                  <code class="pay-result-order-no">{{ result?.orderNo || '-' }}</code>
                </div>
                <div class="pay-result-order-side">
                  <span class="status-tag" :class="`status-${resultStatus}`">{{ resultStatusText }}</span>
                  <button type="button" class="pay-copy" @click="copyOrderNo">复制</button>
                </div>
              </div>

              <div class="pay-result-grid">
                <div class="pay-result-item"><label>套餐</label><span>{{ plan?.planName || '-' }}</span></div>
                <div class="pay-result-item"><label>应付金额</label><span class="pay-strong">¥{{ resultAmountText }}</span></div>
                <div class="pay-result-item"><label>获得积分</label><span class="pay-strong">+{{ result?.credits ?? plan?.credits ?? 0 }}</span></div>
                <div class="pay-result-item"><label>支付方式</label><span>人工确认收款</span></div>
              </div>
            </div>

            <div class="pay-notes">
              <h5 class="pay-block-title">下一步</h5>
              <ol>
                <li v-if="resultStatus === 'PENDING'">按管理员指引完成付款，并在付款备注中填写订单号，便于快速核对。</li>
                <li v-if="resultStatus === 'PENDING'">管理员在后台「订单管理」中确认收款后，积分与权益自动到账。</li>
                <li v-else>权益已发放，可在「我的订单」查看订单详情与关联流水。</li>
                <li>如长时间未确认，可复制订单号联系管理员处理。</li>
              </ol>
            </div>
          </div>

          <!-- 底部操作区 -->
          <div class="pay-footer">
            <template v-if="!isResultStep">
              <label class="pay-agreement">
                <input type="checkbox" v-model="agreed" />
                <span>我已阅读并同意《订阅服务协议》与《退款规则》，理解本订单需管理员确认收款后生效</span>
              </label>
              <div class="pay-footer-actions">
                <div class="pay-footer-amount">
                  应付 <b>¥{{ amountText }}</b>
                </div>
                <button type="button" class="pay-btn is-ghost" @click="close">取消</button>
                <button
                  type="button"
                  class="pay-btn is-primary"
                  :disabled="!canSubmit"
                  :title="canSubmit ? '' : '请先勾选并同意服务协议'"
                  @click="onConfirm"
                >
                  {{ submitting ? '提交中...' : `确认支付 ¥${amountText}` }}
                </button>
              </div>
            </template>
            <template v-else>
              <div class="pay-footer-note">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v4h1" stroke-linecap="round"/></svg>
                <span>订单号已复制到订单列表并高亮，可随时在「我的订单」中继续处理。</span>
              </div>
              <div class="pay-footer-actions">
                <button type="button" class="pay-btn is-ghost" @click="finish">完成</button>
                <button type="button" class="pay-btn is-primary" @click="viewOrder">查看订单详情</button>
              </div>
            </template>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { showToast } from '../Toast.vue';
import { orderStatusText } from '../../config/orderStatus';

/**
 * 企业级支付确认弹窗（支付链路唯一入口）
 *
 * 设计约定（对应需求「支付用的二维码替换成按钮」）：
 *  - 不出现任何二维码/收款码/扫码文案；用户通过「确认支付」按钮提交订单，
 *    支付方式以按钮组（radiogroup）呈现，默认且当前唯一通道为「人工确认收款」。
 *  - 两步式：step1 确认订单（明细 + 支付方式 + 协议勾选）→ step2 完成付款（订单号 + 状态 + 后续指引）。
 *  - 组件本身不发请求：由父组件监听 confirm 调用接口，并把结果通过 result 传回，
 *    这样支付结果与订单列表刷新都由页面的单一数据源负责。
 */
export default {
  name: 'PaymentDialog',
  props: {
    visible: { type: Boolean, default: false },
    /** 归一化后的套餐对象：planName / price / credits / durationDays / category / features */
    plan: { type: Object, default: null },
    /** 提交中（父组件请求进行中） */
    submitting: { type: Boolean, default: false },
    /** 下单结果：{ orderNo, status, message, credits, amount }；非空即进入第二步 */
    result: { type: Object, default: null },
  },
  emits: ['update:visible', 'confirm', 'close', 'view-order', 'finish'],
  setup(props, { emit }) {
    const agreed = ref(false);
    const method = ref('MANUAL');
    const dialogRef = ref(null);

    const steps = [
      { key: 'confirm', label: '确认订单' },
      { key: 'pay', label: '完成付款' },
      { key: 'verify', label: '管理员确认到账' },
    ];

    const isResultStep = computed(() => !!props.result);
    const stepIndex = computed(() => (isResultStep.value ? 1 : 0));
    const isMonthCard = computed(() => (props.plan?.category || 'DIRECT') === 'MONTHLY_CARD');
    const planTypeText = computed(() => (isMonthCard.value ? '会员月卡' : '直购积分'));
    const amountText = computed(() => Number(props.plan?.price || 0).toFixed(2));
    const durationText = computed(() => {
      const days = Number(props.plan?.durationDays || 0);
      return days > 0 ? `${days} 天` : '长期有效';
    });
    const features = computed(() => (Array.isArray(props.plan?.features) ? props.plan.features : []));
    const canSubmit = computed(() => agreed.value && !props.submitting);
    const resultStatus = computed(() => props.result?.status || 'PENDING');
    const resultStatusText = computed(() => orderStatusText(resultStatus.value));
    const resultAmountText = computed(() => Number(props.result?.amount ?? props.plan?.price ?? 0).toFixed(2));
    const resultTitle = computed(() => (
      resultStatus.value === 'PENDING' ? '订单已提交，等待确认收款' : '支付成功，权益已发放'
    ));
    const resultMessage = computed(() => {
      if (props.result?.message) return props.result.message;
      return resultStatus.value === 'PENDING'
        ? '管理员确认到账后，积分与权益会自动发放到你的账户。'
        : '积分与权益已发放，可在「我的订单」中查看详情。';
    });

    watch(() => props.visible, (v) => {
      if (v) {
        agreed.value = false;
        method.value = 'MANUAL';
      }
    });

    function close() {
      emit('update:visible', false);
      emit('close');
    }
    function finish() {
      emit('update:visible', false);
      emit('finish');
    }
    function onConfirm() {
      if (!agreed.value) {
        showToast('请先阅读并同意订阅服务协议', 'warning');
        return;
      }
      if (props.submitting) return;
      emit('confirm');
    }
    function viewOrder() {
      emit('view-order', props.result?.orderNo || '');
    }
    async function copyOrderNo() {
      const text = props.result?.orderNo;
      if (!text) return;
      try {
        if (navigator.clipboard?.writeText) {
          await navigator.clipboard.writeText(text);
          showToast('订单号已复制', 'success');
          return;
        }
      } catch (e) { /* 回退到 execCommand */ }
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        showToast('订单号已复制', 'success');
      } catch (e) {
        showToast('复制失败，请手动记录订单号', 'error');
      }
    }

    function onKeydown(e) {
      if (e.key === 'Escape' && props.visible) close();
    }

    onMounted(() => window.addEventListener('keydown', onKeydown));
    onUnmounted(() => window.removeEventListener('keydown', onKeydown));

    return {
      agreed, method, dialogRef, steps,
      isResultStep, stepIndex, isMonthCard, planTypeText, amountText, durationText, features,
      canSubmit, resultStatus, resultStatusText, resultAmountText, resultTitle, resultMessage,
      close, finish, onConfirm, viewOrder, copyOrderNo,
    };
  },
};
</script>

<style scoped>
.pay-overlay {
  position: fixed;
  inset: 0;
  background: var(--modal-overlay, rgba(0, 0, 0, 0.5));
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  z-index: 10000;
  backdrop-filter: blur(2px);
}
.pay-dialog {
  width: 100%;
  max-width: 560px;
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background: var(--card-bg, #fff);
  border-radius: 12px;
  box-shadow: 0 24px 64px var(--card-shadow, rgba(0, 0, 0, 0.28));
  overflow: hidden;
  outline: none;
}

/* 头部 */
.pay-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 20px 22px 14px; }
.pay-header-main { display: flex; flex-direction: column; gap: 4px; }
.pay-title { margin: 0; font-size: 17px; font-weight: 600; color: var(--text-primary, #333); }
.pay-subtitle { margin: 0; font-size: 12px; line-height: 1.5; color: var(--text-muted, #999); }
.pay-close {
  flex: 0 0 auto; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center;
  background: none; border: none; border-radius: 6px; font-size: 20px; line-height: 1;
  color: var(--text-muted, #999); cursor: pointer; transition: background 0.2s, color 0.2s;
}
.pay-close:hover { background: var(--bg-tertiary, #f5f5f5); color: var(--text-secondary, #666); }

/* 步骤条 */
.pay-steps { display: flex; align-items: center; gap: 8px; padding: 0 22px 16px; }
.pay-step { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.pay-step-dot {
  flex: 0 0 auto; width: 22px; height: 22px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600;
  background: var(--bg-tertiary, #f0f0f0); color: var(--text-muted, #999);
  border: 1px solid var(--border-color, #e0e0e0);
}
.pay-step.is-active .pay-step-dot { background: var(--accent-color, #3498db); border-color: var(--accent-color, #3498db); color: #fff; }
.pay-step.is-done .pay-step-dot { background: #e8f6ee; border-color: #b7e2c8; color: var(--success-color, #27ae60); }
.pay-step-label { font-size: 12px; color: var(--text-muted, #999); white-space: nowrap; }
.pay-step.is-active .pay-step-label { color: var(--text-primary, #333); font-weight: 600; }
.pay-step.is-done .pay-step-label { color: var(--text-secondary, #666); }
.pay-step-line { flex: 1; height: 1px; background: var(--border-color, #e8e8e8); min-width: 12px; }

/* 主体 */
.pay-body { flex: 1; overflow-y: auto; padding: 0 22px 4px; display: flex; flex-direction: column; gap: 16px; }
.pay-block { border: 1px solid var(--border-color, #eee); border-radius: 8px; padding: 14px 16px; }
.pay-block-title { margin: 0 0 10px; font-size: 13px; font-weight: 600; color: var(--text-secondary, #666); }

/* 商品卡 */
.pay-product {
  border: 1px solid #dbe9fb; background: linear-gradient(180deg, #f6faff, var(--card-bg, #fff));
  border-radius: 8px; padding: 14px 16px;
}
.pay-product-head { display: flex; align-items: center; gap: 10px; }
.pay-product-name { font-size: 15px; font-weight: 600; color: var(--text-primary, #2c3e50); }
.pay-product-type { font-size: 11px; padding: 2px 8px; border-radius: 10px; }
.pay-product-type.is-month { background: #fff2e0; color: #d97706; }
.pay-product-type.is-direct { background: #e8f1ff; color: #2f6fd0; }
.pay-product-meta { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 8px; font-size: 12px; color: var(--text-muted, #888); }
.pay-product-meta b { color: var(--text-primary, #333); }
.pay-product-features { list-style: none; margin: 10px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.pay-product-features li { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-secondary, #666); }
.pay-product-features svg { flex: 0 0 auto; color: var(--success-color, #27ae60); }

/* 金额明细 */
.pay-amount-list { display: flex; flex-direction: column; gap: 8px; }
.pay-amount-row { display: flex; align-items: center; justify-content: space-between; font-size: 13px; color: var(--text-secondary, #666); }
.pay-amount-row.is-total { border-top: 1px dashed var(--border-color, #eee); padding-top: 10px; font-size: 14px; color: var(--text-primary, #333); font-weight: 600; }
.pay-amount-none { color: var(--text-muted, #999); }
.pay-amount-total { font-size: 20px; font-weight: 700; color: var(--danger-color, #e74c3c); }

/* 支付方式 */
.pay-methods { display: flex; flex-direction: column; gap: 10px; }
.pay-method {
  display: flex; align-items: flex-start; gap: 10px; width: 100%; text-align: left;
  padding: 12px 14px; border: 1px solid var(--border-color, #e5e5e5); border-radius: 8px;
  background: var(--card-bg, #fff); cursor: pointer; transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}
.pay-method:hover:not(.is-disabled) { border-color: #9cc7ee; }
.pay-method.is-selected { border-color: var(--accent-color, #3498db); box-shadow: 0 0 0 2px rgba(52, 152, 219, 0.12); }
.pay-method.is-disabled { cursor: not-allowed; opacity: 0.6; background: var(--bg-tertiary, #fafafa); }
.pay-method-radio {
  flex: 0 0 auto; width: 16px; height: 16px; margin-top: 1px; border-radius: 50%;
  border: 1px solid var(--border-color, #c9c9c9); background: var(--card-bg, #fff); position: relative;
}
.pay-method.is-selected .pay-method-radio { border-color: var(--accent-color, #3498db); }
.pay-method.is-selected .pay-method-radio::after {
  content: ''; position: absolute; inset: 3px; border-radius: 50%; background: var(--accent-color, #3498db);
}
.pay-method-main { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.pay-method-name { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--text-primary, #333); }
.pay-method-tag { font-size: 11px; font-weight: 500; padding: 1px 7px; border-radius: 9px; }
.pay-method-tag.is-recommend { background: #e8f1ff; color: #2f6fd0; }
.pay-method-tag.is-muted { background: var(--bg-tertiary, #f0f0f0); color: var(--text-muted, #999); }
.pay-method-desc { font-size: 12px; line-height: 1.5; color: var(--text-muted, #888); }

/* 须知 */
.pay-notes { border: 1px solid var(--border-color, #eee); border-radius: 8px; padding: 12px 16px 14px; background: var(--bg-tertiary, #fafafa); }
.pay-notes ol { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; }
.pay-notes li { font-size: 12px; line-height: 1.6; color: var(--text-secondary, #666); }

/* 结果态 */
.pay-result { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 8px; padding: 4px 0 2px; }
.pay-result-icon { width: 52px; height: 52px; border-radius: 50%; display: flex; align-items: center; justify-content: center; }
.pay-result-icon.is-pending { background: #fff7e6; color: #d48806; }
.pay-result-icon.is-paid { background: #e8f6ee; color: var(--success-color, #27ae60); }
.pay-result-title { margin: 4px 0 0; font-size: 16px; font-weight: 600; color: var(--text-primary, #333); }
.pay-result-desc { margin: 0; font-size: 12px; line-height: 1.6; color: var(--text-muted, #888); }
.pay-result-order {
  width: 100%; margin-top: 8px; padding: 12px 14px; border: 1px dashed var(--border-color, #e0e0e0);
  border-radius: 8px; background: var(--bg-tertiary, #fafafa);
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.pay-result-order-main { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; min-width: 0; }
.pay-result-order-label { font-size: 11px; color: var(--text-muted, #999); }
.pay-result-order-no { font-family: 'Consolas', monospace; font-size: 13px; color: var(--text-primary, #333); word-break: break-all; }
.pay-result-order-side { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.pay-copy {
  padding: 4px 10px; font-size: 12px; border-radius: 4px; cursor: pointer;
  border: 1px solid var(--border-color, #ddd); background: var(--card-bg, #fff); color: var(--text-secondary, #666);
}
.pay-copy:hover { border-color: var(--accent-color, #3498db); color: var(--accent-color, #3498db); }
.pay-result-grid { width: 100%; display: grid; grid-template-columns: 1fr 1fr; gap: 10px 16px; margin-top: 10px; text-align: left; }
.pay-result-item { display: flex; align-items: center; justify-content: space-between; font-size: 12px; }
.pay-result-item label { color: var(--text-muted, #999); }
.pay-result-item span { color: var(--text-secondary, #666); }
.pay-strong { font-weight: 600; color: var(--text-primary, #333) !important; }

/* 状态徽章（与订单列表同名 class，保持视觉一致） */
.status-tag { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11px; line-height: 18px; white-space: nowrap; }
.status-tag.status-PENDING { background: #fff7e6; color: #d48806; }
.status-tag.status-PAID { background: #e8f6ee; color: #27ae60; }
.status-tag.status-PENDING_REFUND { background: #fff1f0; color: #d4380d; }
.status-tag.status-DISPUTED { background: #fff1f0; color: #cf1322; }
.status-tag.status-REFUNDED { background: #f0f0f0; color: #666; }
.status-tag.status-CANCELLED { background: #f0f0f0; color: #999; }
.status-tag.status-EXPIRED { background: #f0f0f0; color: #999; }

/* 底部 */
.pay-footer {
  border-top: 1px solid var(--border-color, #eee); background: var(--bg-tertiary, #fafafa);
  padding: 14px 22px 16px; display: flex; flex-direction: column; gap: 12px;
}
.pay-agreement { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; line-height: 1.5; color: var(--text-secondary, #666); cursor: pointer; }
.pay-agreement input { flex: 0 0 auto; margin-top: 2px; cursor: pointer; }
.pay-footer-actions { display: flex; align-items: center; justify-content: flex-end; gap: 10px; }
.pay-footer-amount { margin-right: auto; font-size: 13px; color: var(--text-secondary, #666); }
.pay-footer-amount b { font-size: 18px; color: var(--danger-color, #e74c3c); }
.pay-footer-note { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-muted, #888); }
.pay-btn { padding: 9px 20px; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; border: 1px solid transparent; transition: all 0.2s; }
.pay-btn.is-ghost { background: var(--card-bg, #fff); border-color: var(--border-color, #d9d9d9); color: var(--text-secondary, #666); }
.pay-btn.is-ghost:hover { border-color: var(--accent-color, #3498db); color: var(--accent-color, #3498db); }
.pay-btn.is-primary { background: var(--accent-color, #3498db); color: #fff; min-width: 148px; }
.pay-btn.is-primary:hover:not(:disabled) { background: var(--accent-hover, #2980b9); }
.pay-btn.is-primary:disabled { background: #b9d6ec; cursor: not-allowed; }

/* 动画 */
.pay-fade-enter-active, .pay-fade-leave-active { transition: opacity 0.2s ease; }
.pay-fade-enter-from, .pay-fade-leave-to { opacity: 0; }
.pay-fade-enter-active .pay-dialog, .pay-fade-leave-active .pay-dialog { transition: transform 0.2s ease; }
.pay-fade-enter-from .pay-dialog, .pay-fade-leave-to .pay-dialog { transform: translateY(-12px) scale(0.98); }

/* 暗色主题微调（--card-bg 等已由 App.vue 覆盖，这里只处理写死的浅色背景） */
:global(.theme-dark) .pay-product { border-color: #2b3f66; background: linear-gradient(180deg, #1b2b4a, var(--card-bg, #1e1e3a)); }
:global(.theme-dark) .pay-product-type.is-month { background: #3a2c14; color: #f0b357; }
:global(.theme-dark) .pay-product-type.is-direct { background: #172b47; color: #7fb4ef; }
:global(.theme-dark) .pay-method-tag.is-recommend { background: #172b47; color: #7fb4ef; }
:global(.theme-dark) .pay-result-icon.is-pending { background: #3a2c14; color: #f0b357; }
:global(.theme-dark) .pay-result-icon.is-paid { background: #14301f; color: #4cd07d; }
:global(.theme-dark) .status-tag.status-PENDING { background: #3a2c14; color: #f0b357; }
:global(.theme-dark) .status-tag.status-PAID { background: #14301f; color: #4cd07d; }
:global(.theme-dark) .status-tag.status-PENDING_REFUND,
:global(.theme-dark) .status-tag.status-DISPUTED { background: #3a1a1a; color: #ff7875; }
:global(.theme-dark) .pay-btn.is-primary:disabled { background: #2c4a63; color: #8ba7bd; }
</style>
