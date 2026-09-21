<template>
  <div class="subscription-dashboard">
    <div class="current-plan-card section-card">
      <div class="plan-card-left">
        <div class="plan-badge" :class="planBadgeClass">
          <svg v-if="currentPlan?.planCode === 'ULTRA'" width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M5 16L3 5l5.5 5L12 4l3.5 6L21 5l-2 11H5zm14 3c0 .6-.4 1-1 1H6c-.6 0-1-.4-1-1v-1h14v1z"/></svg>
          <svg v-else-if="currentPlan?.planCode === 'PRO' || currentPlan?.planCode === 'PRO_PLUS'" width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2L9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2z"/></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>
          <span>{{ currentPlan?.planName || '免费' }}</span>
        </div>
        <div class="plan-info">
          <div class="plan-title">当前方案：<span class="plan-name-hl">{{ currentPlan?.planName || '免费版' }}</span></div>
          <div class="plan-benefits">{{ planBenefitsText }}</div>
          <div class="plan-expiry">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zM9 10H7v2h2v-2zm4 0h-2v2h2v-2zm4 0h-2v2h2v-2z"/></svg>
            <span>{{ expiryText }}</span>
          </div>
        </div>
      </div>
      <div class="plan-card-right">
        <button class="btn-upgrade" @click="openUpgradeDialog">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
          升级权益
        </button>
        <div class="plan-mini-info">
          <span v-if="recentSpent > 0">最近消费：{{ recentSpent }} 积分</span>
          <span v-if="remainingDays > 0">剩余 {{ remainingDays }} 天</span>
        </div>
      </div>
    </div>

    <div class="section-card">
      <div class="section-card-header">
        <h4>我的订单</h4>
        <div class="order-filter">
          <select v-model="orderStatusFilter" @change="loadOrders(0)">
            <option value="">全部状态</option>
            <option v-for="opt in orderStatusOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
        </div>
      </div>

      <div v-if="ordersLoading" class="orders-loading">
        <div class="loading-spinner"></div><span>加载中...</span>
      </div>

      <div v-else-if="orders.length === 0" class="orders-empty">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2"/><rect x="9" y="3" width="6" height="4" rx="1"/><path d="M9 12h6M9 16h4"/></svg>
        <p>暂无订单记录</p>
        <button class="btn-upgrade-sm" @click="openUpgradeDialog">去升级权益</button>
      </div>

      <template v-else>
        <table class="orders-table">
          <thead>
            <tr>
              <th>订单号</th><th>套餐名称</th><th>金额</th><th>获得积分</th>
              <th>状态</th><th>支付时间</th><th>权益到期</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in orders" :key="order.orderNo" :class="{ highlight: highlightOrderNo === order.orderNo }">
              <td class="order-no-cell" @click="copyText(order.orderNo, '订单号已复制')" title="点击复制">
                <span class="order-no-text">{{ order.orderNo }}</span>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
              </td>
              <td>{{ order.planName }}</td>
              <td class="amount-cell">¥{{ Number(order.amount || 0).toFixed(2) }}</td>
              <td class="credits-cell">+{{ order.credits || 0 }}</td>
              <td><span class="status-tag" :class="`status-${order.status}`">{{ statusText(order.status) }}</span></td>
              <td>{{ formatShortDate(order.paidAt || order.createdAt) }}</td>
              <td>{{ formatShortDate(order.expiresAt) }}</td>
              <td class="action-cell">
                <button class="btn-link" @click="openOrderDetail(order)">查看详情</button>
                <button v-if="order.status === 'PENDING'" class="btn-link btn-danger" @click="cancelOrder(order)">取消</button>
                <button v-if="order.status === 'PAID'" class="btn-link btn-warn" @click="openRefundDialog(order)">申请退款</button>
                <button v-if="order.status === 'PAID'" class="btn-link btn-warn" @click="openDisputeDialog(order)">纠纷申诉</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="orders-pagination">
          <button :disabled="orderPage <= 0 || ordersLoading" @click="loadOrders(orderPage - 1)">上一页</button>
          <span>{{ orderPage + 1 }} / {{ totalOrderPages }}</span>
          <button :disabled="orderPage >= totalOrderPages - 1 || ordersLoading" @click="loadOrders(orderPage + 1)">下一页</button>
        </div>
      </template>
    </div>

    <div v-if="showUpgradeDialog" class="modal-overlay" @click.self="showUpgradeDialog = false">
      <div class="upgrade-dialog">
        <div class="upgrade-header">
          <div class="upgrade-user">
            <img class="upgrade-avatar" :src="userAvatarUrl" alt="avatar" @error="userAvatarUrl = '/default-avatar.svg'"/>
            <div>
              <div class="upgrade-nickname">{{ userInfo?.nickname || userInfo?.username || '用户' }}</div>
              <span class="user-level-tag" :class="planBadgeClass">{{ currentPlan?.planName || '免费' }}</span>
            </div>
          </div>
          <div class="upgrade-header-actions">
            <button class="credits-chip" @click="goToCredits">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2L9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2z"/></svg>
              <span>积分余额 {{ creditsBalance }}</span>
            </button>
            <button class="credits-chip" @click="goToOrders" title="查看订单与发票">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.89-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z"/></svg>
              <span>订阅与发票</span>
            </button>
            <button class="upgrade-close" @click="showUpgradeDialog = false">×</button>
          </div>
        </div>

        <div class="plan-groups">
          <!-- 月卡组 -->
          <div class="plan-group" v-if="monthlyCardPlans.length > 0">
            <div class="plan-group-header">
              <div class="plan-group-title">
                <span class="plan-group-dot month"></span>
                <h4>会员月卡</h4>
                <span class="plan-group-sub">30 天权益，超值更省</span>
              </div>
            </div>
            <div class="plans-grid">
              <div v-for="plan in monthlyCardPlans" :key="plan.planCode"
                   class="plan-card">
                <div class="plan-card-name">{{ plan.planName }}</div>
                <div class="plan-card-price"><sup>¥</sup>{{ plan.priceText || formatYuan(plan.price) }}<sub>/{{ plan.durationDays }}天</sub></div>
                <div class="plan-card-credits">+{{ plan.credits }} 积分</div>
                <ul class="plan-card-features">
                  <li v-for="(f, i) in (plan.features || [])" :key="i">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
                    <span>{{ f }}</span>
                  </li>
                </ul>
                <button
                  class="plan-card-btn"
                  :disabled="purchasingPlan === plan.planCode || isCurrentPlan(plan.planCode)"
                  @click="openPayment(plan)"
                >
                  <span v-if="purchasingPlan === plan.planCode">购买中...</span>
                  <span v-else-if="isCurrentPlan(plan.planCode)">已购买</span>
                  <span v-else>立即购买</span>
                </button>
              </div>
            </div>
          </div>

          <!-- 直购积分组 -->
          <div class="plan-group">
            <div class="plan-group-header">
              <div class="plan-group-title">
                <span class="plan-group-dot direct"></span>
                <h4>直购积分</h4>
                <span class="plan-group-sub">按档购买，立即到账</span>
              </div>
            </div>
            <div class="plans-grid">
              <div v-for="plan in directPurchasePlans" :key="plan.planCode"
                   class="plan-card">
                <div class="plan-card-name">{{ plan.planName }}</div>
                <div class="plan-card-price"><sup>¥</sup>{{ plan.priceText || formatYuan(plan.price) }}</div>
                <div class="plan-card-credits">+{{ plan.credits }} 积分</div>
                <button
                  class="plan-card-btn"
                  :disabled="purchasingPlan === plan.planCode || isCurrentPlan(plan.planCode)"
                  @click="openPayment(plan)"
                >
                  <span v-if="purchasingPlan === plan.planCode">购买中...</span>
                  <span v-else-if="isCurrentPlan(plan.planCode)">已购买</span>
                  <span v-else>立即购买</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="showRefundDialog" class="modal-overlay" @click.self="showRefundDialog = false">
      <div class="modal-content small-modal">
        <div class="modal-header"><h3>申请退款</h3><button class="btn-close" @click="showRefundDialog = false">×</button></div>
        <div class="modal-body">
          <div class="refund-order-info">
            <div><label>订单号：</label><span>{{ refundTarget?.orderNo }}</span></div>
            <div><label>套餐：</label><span>{{ refundTarget?.planName }}</span></div>
            <div><label>金额：</label><span>¥{{ Number(refundTarget?.amount || 0).toFixed(2) }}</span></div>
          </div>
          <div class="form-group">
            <label>退款原因 <span style="color:red">*</span></label>
            <textarea v-model="refundReason" rows="4" placeholder="请输入退款原因（至少5个字）"></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showRefundDialog = false">取消</button>
          <button class="btn-save btn-warn" :disabled="!refundReason.trim() || refundSubmitting" @click="submitRefund">
            {{ refundSubmitting ? '提交中...' : '确认申请退款' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 纠纷申诉弹窗 -->
    <div v-if="showDisputeDialog" class="modal-overlay" @click.self="showDisputeDialog = false">
      <div class="modal-content small-modal">
        <div class="modal-header"><h3>纠纷申诉</h3><button class="btn-close" @click="showDisputeDialog = false">×</button></div>
        <div class="modal-body">
          <div class="refund-order-info">
            <div><label>订单号：</label><span>{{ disputeTarget?.orderNo }}</span></div>
            <div><label>套餐：</label><span>{{ disputeTarget?.planName }}</span></div>
            <div><label>金额：</label><span>¥{{ Number(disputeTarget?.amount || 0).toFixed(2) }}</span></div>
          </div>
          <div class="form-group">
            <label>申诉原因 <span style="color:red">*</span></label>
            <textarea v-model="disputeReason" rows="4" placeholder="请输入申诉原因（至少5个字）"></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showDisputeDialog = false">取消</button>
          <button class="btn-save btn-warn" :disabled="!disputeReason.trim() || disputeSubmitting" @click="submitDispute">
            {{ disputeSubmitting ? '提交中...' : '确认申诉' }}
          </button>
        </div>
      </div>
    </div>

    <Transition name="drawer">
      <div v-if="showOrderDrawer" class="drawer-overlay" @click.self="showOrderDrawer = false">
        <div class="drawer-container">
          <div class="drawer-header">
            <div class="drawer-title">
              <span>订单详情</span>
              <span class="drawer-order-no" @click="copyText(detail?.orderNo, '订单号已复制')" title="点击复制">
                {{ detail?.orderNo }}
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
              </span>
              <span class="status-tag" :class="`status-${detail?.status}`">{{ statusText(detail?.status) }}</span>
            </div>
            <button class="drawer-close" @click="showOrderDrawer = false">×</button>
          </div>
          <div class="drawer-body" v-if="detail">
            <div class="detail-section">
              <h5>订单基础信息</h5>
              <div class="detail-grid">
                <div class="detail-item"><label>订单号</label><span>{{ detail.orderNo }}</span></div>
                <div class="detail-item"><label>订单状态</label><span>{{ statusText(detail.status) }}</span></div>
                <div class="detail-item"><label>创建时间</label><span>{{ formatLongDate(detail.createdAt) }}</span></div>
                <div class="detail-item"><label>更新时间</label><span>{{ formatLongDate(detail.updatedAt) }}</span></div>
                <div class="detail-item"><label>支付时间</label><span>{{ formatLongDate(detail.paidAt) }}</span></div>
                <div class="detail-item"><label>订单来源</label><span>{{ detail.source || 'WEB' }}</span></div>
              </div>
            </div>
            <div class="detail-section">
              <h5>套餐快照信息</h5>
              <div class="detail-grid">
                <div class="detail-item"><label>套餐编码</label><span>{{ detail.planCode }}</span></div>
                <div class="detail-item"><label>套餐名称</label><span>{{ detail.planName }}</span></div>
                <div class="detail-item"><label>套餐时长</label><span>{{ detail.planDurationDays }} 天</span></div>
                <div class="detail-item"><label>获得积分</label><span>{{ detail.credits }}</span></div>
              </div>
            </div>
            <div class="detail-section">
              <h5>支付与权益</h5>
              <div class="detail-grid">
                <div class="detail-item"><label>订单金额</label><span class="amount-cell">¥{{ Number(detail.amount || 0).toFixed(2) }}</span></div>
                <div class="detail-item"><label>实付金额</label><span class="amount-cell">¥{{ Number(detail.paidAmount || 0).toFixed(2) }}</span></div>
                <div class="detail-item"><label>支付方式</label><span>{{ detail.paymentMethod || '-' }}</span></div>
                <div class="detail-item"><label>权益开始</label><span>{{ formatLongDate(detail.validFrom) }}</span></div>
                <div class="detail-item"><label>权益到期</label><span>{{ formatLongDate(detail.expiresAt) }}</span></div>
                <div class="detail-item"><label>自动续费</label><span>{{ detail.autoRenew ? '是' : '否' }}</span></div>
              </div>
            </div>
            <div v-if="detail.status === 'REFUNDED' || detail.refundReason" class="detail-section">
              <h5>退款信息</h5>
              <div class="detail-grid">
                <div class="detail-item"><label>退款原因</label><span>{{ detail.refundReason || '-' }}</span></div>
                <div class="detail-item"><label>退款时间</label><span>{{ formatLongDate(detail.refundedAt) }}</span></div>
                <div class="detail-item"><label>退款金额</label><span class="amount-cell">¥{{ Number(detail.refundAmount || 0).toFixed(2) }}</span></div>
                <div class="detail-item"><label>退款状态</label><span>{{ detail.refundStatus || '-' }}</span></div>
              </div>
            </div>
            <div v-if="detail.remark || detail.cancelReason" class="detail-section">
              <h5>备注与其他</h5>
              <div class="detail-grid">
                <div class="detail-item"><label>取消原因</label><span>{{ detail.cancelReason || '-' }}</span></div>
                <div class="detail-item"><label>管理员备注</label><span>{{ detail.remark || '-' }}</span></div>
              </div>
            </div>

            <div class="detail-section">
              <h5>订单时间轴</h5>
              <div class="timeline">
                <div v-for="(evt, i) in timelineEvents" :key="i" class="timeline-item" :class="{ done: evt.done, last: i === timelineEvents.length - 1 }">
                  <div class="timeline-dot"></div>
                  <div class="timeline-content">
                    <div class="timeline-title">{{ evt.title }} <span class="timeline-op">（{{ evt.operator }}）</span></div>
                    <div class="timeline-time">{{ formatLongDate(evt.time) }}</div>
                  </div>
                </div>
              </div>
            </div>

            <div v-if="relatedTransactions.length > 0" class="detail-section">
              <h5>关联流水</h5>
              <div class="related-list">
                <div v-for="tx in relatedTransactions" :key="tx.id" class="related-item" @click="goToCreditsWithRelated(detail.orderNo)">
                  <div class="related-left">
                    <span class="related-type" :class="tx.type">{{ txTypeText(tx.type) }}</span>
                    <span class="related-desc">{{ tx.description || '-' }}</span>
                  </div>
                  <div class="related-right">
                    <span class="related-amount" :class="tx.changeType">{{ tx.changeType === 'ADD' ? '+' : '-' }}{{ tx.amount }}</span>
                    <span class="related-time">{{ formatShortDate(tx.createdAt) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="drawer-footer" v-if="detail?.status === 'PAID' || detail?.status === 'DISPUTED' || detail?.status === 'PENDING_REFUND'">
            <div v-if="detail?.status === 'PENDING_REFUND'" class="refund-pending-notice">
              退款申请已提交，等待管理员审批中...
            </div>
            <button v-if="detail?.status === 'PAID'" class="btn-warn" style="width:100%" @click="openRefundDialog(detail)"><Icon name="coin" :size="14" /> 申请退款</button>
            <button v-if="detail?.status === 'PAID'" class="btn-warn" style="width:100%;margin-top:8px" @click="openDisputeDialog(detail)"><Icon name="warning" :size="14" /> 纠纷申诉</button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- 支付链路唯一入口：按钮驱动的确认订单弹窗（无二维码/扫码） -->
    <PaymentDialog
      v-model:visible="showPaymentDialog"
      :plan="paymentPlan"
      :submitting="!!paymentPlan && purchasingPlan === paymentPlan.planCode"
      :result="paymentResult"
      @confirm="submitPayment"
      @view-order="viewPaymentOrder"
      @finish="finishPayment"
      @close="onPaymentDialogClose"
    />
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import Icon from '../Icon.vue';
import { subscriptionApi, creditsApi, authApi } from '../../services/api';
import { showToast } from '../Toast.vue';
import { showConfirm } from '../ConfirmDialog.vue';
import PaymentDialog from './PaymentDialog.vue';
import { ORDER_STATUS_OPTIONS as orderStatusOptions, orderStatusText as orderStatusLabel } from '../../config/orderStatus';
import logger from '../../utils/logger';

// 后端 SubscriptionTier 枚举与 planCode 映射
// 后端 tier: FREE / LITE / PRO / PROPLUS / ULTRA
const TIER_LABEL_MAP = {
  FREE: '免费版',
  LITE: '直购积分·600',
  PRO: '直购积分·3500',
  PROPLUS: '直购积分·16000',
  PRO_PLUS: '直购积分·16000',
  ULTRA: '直购积分·45000',
  MEGA: '直购积分·100000',
  SMALL_MONTH_CARD: '小月卡',
  LARGE_MONTH_CARD: '大月卡',
  ALL: '全功能版（小月卡 + 大月卡）',
};

const TIER_PLAN_CODE_MAP = {
  FREE: 'FREE',
  LITE: 'LITE',
  PRO: 'PRO',
  PROPLUS: 'PROPLUS',
  PRO_PLUS: 'PRO_PLUS',
  ULTRA: 'ULTRA',
  MEGA: 'MEGA',
  SMALL_MONTH_CARD: 'SMALL_MONTH_CARD',
  LARGE_MONTH_CARD: 'LARGE_MONTH_CARD',
};

function tierToPlanCode(tier) {
  return TIER_PLAN_CODE_MAP[tier] || tier || 'FREE';
}

function tierLabel(tier) {
  return TIER_LABEL_MAP[tier] || tier || '免费版';
}

/**
 * 价格文案：整数不带小数（59 → "59"），有角分才带（9.9 → "9.9"，9.95 → "9.95"）。
 *
 * 之前直购档位用 toFixed(0) 显示，9.9 元会显示成「¥10」——卡片价与下单实付不一致。
 */
function formatYuan(value) {
  const n = Number(value || 0);
  if (!Number.isFinite(n)) return '0';
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(2)));
}

// 标准化后端 plan → 模板字段
function normalizePlan(p) {  if (!p) return null;
  const tier = p.tier || p.planCode || 'FREE';
  return {
    planCode: tierToPlanCode(tier) ?? p.planCode,
    tier,
    planName: p.planName || tierLabel(tier),
    price: p.priceYuan != null ? p.priceYuan : p.price,
    priceText: formatYuan(p.priceYuan != null ? p.priceYuan : p.price),
    priceCents: p.priceCents,
    credits: p.pointsGranted != null ? p.pointsGranted : p.credits,
    durationDays: p.durationDays || 30,
    benefits: p.benefits || p.features || [],
    features: p.benefits || p.features || [],
    firstMonthDiscount: p.firstMonthDiscount || false,
    category: p.category || detectCategory(tier),
  };
}

function detectCategory(tier) {
  if (!tier) return 'DIRECT';
  const t = String(tier).toUpperCase();
  if (t.includes('MONTH') || t.includes('CARD')) return 'MONTHLY_CARD';
  return 'DIRECT';
}

// 标准化后端 order → 模板字段
function normalizeOrder(o) {
  if (!o) return null;
  const tier = o.planTier || o.planCode || 'FREE';
  const amount = o.price != null ? o.price : o.amount;
  return {
    ...o,
    planCode: tierToPlanCode(tier),
    planTier: tier,
    planName: o.planName || tierLabel(tier),
    amount,
    paidAmount: o.paidAmount != null ? o.paidAmount : amount,
    credits: o.creditAmount != null ? o.creditAmount : o.credits,
    planDurationDays: o.durationDays != null ? o.durationDays : o.planDurationDays,
    durationDays: o.durationDays,
    status: o.status,
    paidAt: o.paidAt,
    createdAt: o.createdAt,
    expiresAt: o.expiresAt,
    refundedAt: o.refundedAt,
    refundAmount: o.refundAmount,
    refundReason: o.refundReason,
    paymentMethod: o.paymentMethod,
    orderNo: o.orderNo,
    // 模板兼容字段：后端未返回时使用默认值
    validFrom: o.validFrom || o.paidAt,
    source: o.source || 'WEB',
    autoRenew: o.autoRenew || false,
    refundStatus: o.refundStatus || (o.status === 'REFUNDED' ? '已退款' : ''),
  };
}

// 标准化后端 relatedTransaction → 模板字段
function normalizeRelatedTx(tx) {
  if (!tx) return tx;
  const direction = tx.direction || (tx.changeType === 'ADD' ? 'IN' : 'OUT');
  return {
    ...tx,
    type: tx.type,
    description: tx.remark || tx.description || '-',
    changeType: direction === 'IN' ? 'ADD' : 'SUB',
    amount: tx.amount,
    balanceAfter: tx.balanceAfter,
    createdAt: tx.createdAt,
  };
}

export default {
  name: 'SubscriptionDashboard',
  components: { Icon, PaymentDialog },
  props: {
    autoOpenUpgrade: { type: Boolean, default: false }
  },
  emits: ['switchTab', 'refreshCredits'],
  setup(props, { emit }) {
    const router = useRouter();

    const userInfo = ref(null);
    const userAvatarUrl = ref('/default-avatar.svg');
    const creditsBalance = ref(0);
    const currentPlan = ref(null);
    const recentSpent = ref(0);
    const remainingDays = ref(0);

    const showUpgradeDialog = ref(false);
    // 支付链路：确认订单弹窗（按钮驱动，无二维码）
    const showPaymentDialog = ref(false);
    const paymentPlan = ref(null);
    const paymentResult = ref(null);
    const plans = ref([]);
    // 折扣真值（来自 credit_rule，随套餐接口下发）：用于双持权益文案，避免写死折数
    const allTierDiscount = ref(null);
    const smallCardDiscount = ref(null);
    const largeCardDiscount = ref(null);
    const purchasingPlan = ref(null);
    const highlightOrderNo = ref(null);

    const orders = ref([]);
    const ordersLoading = ref(false);
    const orderPage = ref(0);
    const orderPageSize = 10;
    const totalOrderPages = ref(1);
    const orderStatusFilter = ref('');
    const totalOrdersCount = ref(0);

    const showOrderDrawer = ref(false);
    const detail = ref(null);
    const relatedTransactions = ref([]);

    const showRefundDialog = ref(false);
    const refundTarget = ref(null);
    const refundReason = ref('');
    const refundSubmitting = ref(false);
    const disputeTarget = ref(null);
    const disputeReason = ref('');
    const disputeSubmitting = ref(false);
    const showDisputeDialog = ref(false);

    const planBadgeClass = computed(() => {
      const c = currentPlan.value?.planCode;
      if (c === 'ULTRA') return 'ultra';
      if (c === 'MEGA') return 'mega';
      if (c === 'PRO_PLUS' || c === 'PROPLUS') return 'pro-plus';
      if (c === 'PRO') return 'pro';
      if (c === 'LITE') return 'lite';
      if (c === 'SMALL_MONTH_CARD') return 'month-card';
      if (c === 'LARGE_MONTH_CARD') return 'month-card-large';
      if (c === 'ALL') return 'all';   // 双持月卡 = 全功能版，使用蓝色标识
      return 'free';
    });

    const planBenefitsText = computed(() => {
      const features = currentPlan.value?.features || [];
      if (features.length > 0) return features.slice(0, 3).join(' · ');
      const c = currentPlan.value?.planCode;
      if (c === 'FREE' || !c) return '每日签到获取积分 · 基础AI对话 · 媒体文件存储';
      return '解锁高级AI模型 · 优先响应 · 专属客服';
    });

    const expiryText = computed(() => {
      const p = currentPlan.value;
      if (!p || p.planCode === 'FREE' || !p.expiresAt) return '永久免费';
      return `到期：${formatDateOnly(p.expiresAt)}`;
    });

    const timelineEvents = computed(() => {
      const d = detail.value;
      if (!d) return [];
      const evts = [];
      evts.push({ title: '订单创建', operator: d.createdBy || '用户', time: d.createdAt, done: true });
      if (d.paidAt) evts.push({ title: '订单支付完成', operator: d.paidBy || '系统', time: d.paidAt, done: true });
      if (d.status === 'CANCELLED') evts.push({ title: '订单已取消', operator: d.cancelledBy || '用户', time: d.cancelledAt || d.updatedAt, done: true });
      if (d.status === 'REFUNDED') evts.push({ title: '退款处理完成', operator: d.refundedBy || '管理员', time: d.refundedAt || d.updatedAt, done: true });
      if (d.status === 'EXPIRED') evts.push({ title: '订单已过期', operator: '系统', time: d.expiredAt || d.expiresAt, done: true });
      return evts;
    });

    const monthlyCardPlans = computed(() => plans.value.filter(p => (p.category || 'DIRECT') === 'MONTHLY_CARD'));
    const directPurchasePlans = computed(() => plans.value.filter(p => (p.category || 'DIRECT') === 'DIRECT'));

    // 通过 creditsApi.getBalance 获取当前 tier 信息（替代废弃的 getCurrentPlan）
    async function loadBalanceAndTier() {
      try {
        const data = await creditsApi.getBalance();
        creditsBalance.value = data?.balance ?? 0;
        const tier = data?.subscriptionTier || data?.tier || 'FREE';
        const expiresAt = data?.subscriptionExpiresAt || data?.tierExpireAt || null;
        currentPlan.value = {
          planCode: tierToPlanCode(tier),
          tier,
          planName: tierLabel(tier),
          // 双持（ALL）时把两种月卡的权益合并展示，避免只看到一张卡的权益
          features: tier === 'ALL' ? mergedMonthlyCardFeatures() : lookupPlanFeatures(tierToPlanCode(tier)),
          expiresAt,
        };
        if (expiresAt) {
          const now = new Date();
          const exp = new Date(expiresAt);
          remainingDays.value = Math.max(0, Math.ceil((exp - now) / (1000 * 60 * 60 * 24)));
        } else {
          remainingDays.value = 0;
        }
      } catch (err) {
        logger.warn('加载积分余额失败:', err.message);
        currentPlan.value = { planCode: 'FREE', tier: 'FREE', planName: '免费版', features: [] };
      }
    }

    function lookupPlanFeatures(planCode) {
      const p = plans.value.find(x => x.planCode === planCode);
      return p?.features || p?.benefits || [];
    }

    /**
     * 双持月卡（全功能版）的权益合并：
     * - 积分基础**相加**（小月卡 3000 + 大月卡 8000 = 11000），而不是只显示一张卡的 3000；
     * - 两条"每日签到额外 +100/+300"合并为一条 +400（叠加）；
     * - 模型支持取更强的一档（有"全模型支持"就不再显示"基础模型支持"）；
     * - 专属折扣取更优（数值更小的一档）；
     * - 其余权益去重后各保留一次。
     * 顺序上把"积分基础 / 每日签到 / 模型支持"放前面，因为卡片只展示前三项。
     */
    function mergedMonthlyCardFeatures() {
      const all = [...lookupPlanFeatures('SMALL_MONTH_CARD'), ...lookupPlanFeatures('LARGE_MONTH_CARD')];

      const creditSum = all.reduce((sum, f) => {
        const m = /(\d+)\s*积分基础/.exec(f);
        return sum + (m ? Number(m[1]) : 0);
      }, 0);

      const out = [];
      if (creditSum > 0) out.push(`${creditSum} 积分基础`);
      out.push('每日签到额外 +400（小100 + 大300 叠加）');
      if (all.some(f => f.includes('全模型支持'))) out.push('全模型支持');

      const discounts = all
        .map(f => /折扣\s*(\d+(?:\.\d+)?)\s*折/.exec(f))
        .filter(Boolean)
        .map(m => Number(m[1]))
        .sort((a, b) => a - b);
      // 双持折扣以 credit_rule.all_tier_discount 为准（接口下发），没有时退回卡片文案里的较优档
      const allText = formatDiscount(allTierDiscount.value);
      if (allText) out.push(`专属折扣 ${allText}`);
      else if (discounts.length) out.push(`专属折扣 ${discounts[0]} 折`);

      // 其余权益（优先响应队列 / 30 天有效 等）去重追加
      all.forEach(f => {
        if (/积分基础|每日签到额外|模型支持|折扣/.test(f)) return;
        if (!out.includes(f)) out.push(f);
      });
      return out;
    }

    function loadUserAndPlan() {
      const info = localStorage.getItem('user_info');
      if (info) {
        try {
          userInfo.value = JSON.parse(info);
          if (userInfo.value?.avatar) {
            const a = userInfo.value.avatar;
            userAvatarUrl.value = a.startsWith('http') ? a : `http://localhost:8081${a.startsWith('/') ? a : '/' + a}`;
          }
        } catch (e) {
          // 头像只是展示增强，失败不阻塞订阅页；但留下日志便于排查（原先静默吞掉）
          logger.warn('解析用户头像地址失败:', e);
        }
      }
      authApi.getCurrentUser().then(data => {
        if (data) {
          userInfo.value = { ...userInfo.value, ...data };
          if (data.avatar) {
            const a = data.avatar;
            userAvatarUrl.value = a.startsWith('http') ? a : `http://localhost:8081${a.startsWith('/') ? a : '/' + a}`;
          }
        }
      }).catch((e) => {
        // 当前用户信息刷新失败不影响已缓存展示，留日志便于排查登录态问题
        logger.warn('刷新当前用户信息失败:', e);
      });
      // 套餐列表先加载，再加载余额（便于 features 查找）
      subscriptionApi.getPlans().then(data => {
        applyDiscounts(data);
        let list;
        if (Array.isArray(data)) {
          list = data;
        } else if (Array.isArray(data?.groups)) {
          list = [];
          data.groups.forEach(g => {
            if (Array.isArray(g?.plans)) list = list.concat(g.plans);
          });
        } else if (Array.isArray(data?.plans)) {
          list = data.plans;
        } else if (Array.isArray(data?.directPlans) || Array.isArray(data?.monthlyCards)) {
          list = [];
          if (Array.isArray(data.directPlans)) list = list.concat(data.directPlans);
          if (Array.isArray(data.monthlyCards)) list = list.concat(data.monthlyCards);
        } else {
          list = [];
        }
        plans.value = list.map(normalizePlan);
      }).catch(() => {
        plans.value = mockPlans();
      }).finally(() => {
        loadBalanceAndTier();
      });
    }

    /**
     * 折扣显示：接口下发的 allTierDiscount/smallCardDiscount/largeCardDiscount 来自
     * credit_rule，是实际计费用的值；前端不再写死"8 折"。
     */
    function formatDiscount(rate) {
      const n = Number(rate);
      if (!Number.isFinite(n) || n <= 0 || n >= 1) return '';
      const tenths = Math.round(n * 100) / 10;   // 0.7 → 7，0.85 → 8.5
      return `${Number.isInteger(tenths) ? tenths : tenths.toFixed(1)} 折`;
    }

    /** 保存接口下发的折扣（credit_rule 真值） */
    function applyDiscounts(data) {
      if (!data || Array.isArray(data)) return;
      if (data.allTierDiscount != null) allTierDiscount.value = Number(data.allTierDiscount);
      if (data.smallCardDiscount != null) smallCardDiscount.value = Number(data.smallCardDiscount);
      if (data.largeCardDiscount != null) largeCardDiscount.value = Number(data.largeCardDiscount);
    }

    /**
     * 接口不可用时的兜底档位（仅用于渲染骨架，真值一律以 GET /api/subscriptions/plans 为准）。
     *
     * 注意：这里的数字必须与后端 credit_rule 的出厂默认值保持一致 ——
     * 旧版这张兜底表停留在更早的定价（600/3500/16000/45000），接口一挂就会显示过期价格。
     */
    function mockPlans() {
      return [
        { planCode: 'SMALL_MONTH_CARD', tier: 'SMALL_MONTH_CARD', planName: '小月卡', price: 30, credits: 3000, durationDays: 30, category: 'MONTHLY_CARD', features: ['3000 积分基础', '每日签到额外 +100 积分', '基础模型支持', '专属折扣 9 折', '30 天有效'] },
        { planCode: 'LARGE_MONTH_CARD', tier: 'LARGE_MONTH_CARD', planName: '大月卡', price: 68, credits: 8000, durationDays: 30, category: 'MONTHLY_CARD', features: ['8000 积分基础', '每日签到额外 +300 积分', '全模型支持', '专属折扣 8 折', '优先响应队列', '30 天有效'] },
        { planCode: 'LITE', tier: 'LITE', planName: '直购积分·2000', price: 9.9, credits: 2000, durationDays: 30, category: 'DIRECT', features: ['2000 积分', '基础模型支持', '标准响应速度'] },
        { planCode: 'PRO', tier: 'PRO', planName: '直购积分·4000', price: 59, credits: 4000, durationDays: 30, category: 'DIRECT', features: ['4000 积分', '全模型支持', '优先响应', '30 天文件存储'] },
        { planCode: 'PROPLUS', tier: 'PROPLUS', planName: '直购积分·12000', price: 219, credits: 12000, durationDays: 30, category: 'DIRECT', features: ['12000 积分', '全模型支持', '高优先级队列', '高级分析功能', '90 天文件存储'] },
        { planCode: 'ULTRA', tier: 'ULTRA', planName: '直购积分·40000', price: 629, credits: 40000, durationDays: 30, category: 'DIRECT', features: ['40000 积分', '全模型支持', '最高优先级', '全部高级功能', '永久文件存储'] },
        { planCode: 'MEGA', tier: 'MEGA', planName: '直购积分·100000', price: 648, credits: 100000, durationDays: 30, category: 'DIRECT', features: ['100000 积分', '全模型支持', '最高优先级', '全部高级功能', '永久文件存储', '专属客服支持'] }
      ];
    }

    function loadOrders(page = 0) {
      ordersLoading.value = true;
      orderPage.value = page;
      const params = { page, size: orderPageSize };
      if (orderStatusFilter.value) params.status = orderStatusFilter.value;
      subscriptionApi.getMyOrders(params).then(data => {
        const list = Array.isArray(data) ? data : (data?.content || data?.records || []);
        orders.value = list.map(normalizeOrder);
        const total = data?.totalElements ?? data?.total ?? orders.value.length;
        totalOrdersCount.value = total;
        totalOrderPages.value = Math.max(1, Math.ceil(total / orderPageSize));
      }).catch(() => {
        orders.value = []; totalOrderPages.value = 1;
      }).finally(() => { ordersLoading.value = false; });
    }

    function openUpgradeDialog() {
      if (plans.value.length === 0) {
        subscriptionApi.getPlans().then(data => {
          applyDiscounts(data);
          let list;
          if (Array.isArray(data)) list = data;
          else if (Array.isArray(data?.groups)) {
            list = [];
            data.groups.forEach(g => { if (Array.isArray(g?.plans)) list = list.concat(g.plans); });
          } else if (Array.isArray(data?.plans)) list = data.plans;
          else if (Array.isArray(data?.directPlans) || Array.isArray(data?.monthlyCards)) {
            list = [];
            if (Array.isArray(data.directPlans)) list = list.concat(data.directPlans);
            if (Array.isArray(data.monthlyCards)) list = list.concat(data.monthlyCards);
          } else list = [];
          plans.value = list.map(normalizePlan);
        }).catch(() => { plans.value = mockPlans(); });
      }
      showUpgradeDialog.value = true;
    }

    function isCurrentPlan(code) {
      const plan = plans.value.find(p => p.planCode === code);
      if (!plan) return false;
      // 只有月卡才显示"再次购买"（续费），直购积分始终显示"立即购买"
      if ((plan.category || 'DIRECT') !== 'MONTHLY_CARD') return false;
      const currentTier = currentPlan.value?.tier || tierToPlanCode(currentPlan.value?.planCode);
      // 如果是 ALL 状态，所有月卡都显示"已购买"
      if (currentTier === 'ALL') return true;
      return currentTier && currentTier === plan.tier && currentTier !== 'FREE';
    }

    /**
     * 支付链路（唯一功能约定）：支付用「按钮」而不是二维码/扫码。
     * 点击「立即购买」→ 打开确认订单弹窗（套餐/金额/支付方式/协议勾选）
     * → 点「确认支付」提交订单 → 弹窗进入第二步展示订单号与待确认收款状态。
     */
    function openPayment(plan) {
      paymentPlan.value = plan;
      paymentResult.value = null;
      showPaymentDialog.value = true;
    }

    function resetPaymentState() {
      paymentResult.value = null;
      paymentPlan.value = null;
    }

    /** 弹窗关闭（含右上角 ×）：订单已在列表中，刷新一次保证状态最新 */
    function onPaymentDialogClose() {
      const submitted = !!paymentResult.value?.orderNo;
      resetPaymentState();
      if (submitted) loadOrders(0);
    }

    /** 第二步点「完成」：收起支付与套餐弹窗，回到订单列表并高亮新订单 */
    function finishPayment() {
      const orderNo = paymentResult.value?.orderNo;
      resetPaymentState();
      showPaymentDialog.value = false;
      showUpgradeDialog.value = false;
      if (orderNo) {
        highlightOrderNo.value = orderNo;
        setTimeout(() => { highlightOrderNo.value = null; }, 8000);
        loadOrders(0);
      }
    }

    /** 第二步点「查看订单详情」：直接打开订单抽屉 */
    async function viewPaymentOrder() {
      const orderNo = paymentResult.value?.orderNo;
      const plan = paymentPlan.value;
      const result = paymentResult.value;
      resetPaymentState();
      showPaymentDialog.value = false;
      showUpgradeDialog.value = false;
      if (!orderNo) return;
      await openOrderDetail({
        orderNo,
        planCode: plan?.tier || plan?.planCode,
        planName: plan?.planName,
        amount: result?.amount ?? plan?.price,
        credits: result?.credits ?? plan?.credits,
        status: result?.status || 'PENDING',
        createdAt: new Date().toISOString(),
      });
    }

    /** 弹窗「确认支付」：提交订单（后端仍为人工确认收款，下单即 PENDING） */
    async function submitPayment() {
      const plan = paymentPlan.value;
      if (!plan || purchasingPlan.value) return;
      purchasingPlan.value = plan.planCode;
      try {
        const res = await subscriptionApi.purchase({ planCode: plan.tier || plan.planCode, paymentMethod: 'MANUAL' });
        const orderNo = res?.orderNo || '';
        const status = res?.status || 'PENDING';
        const isPending = status === 'PENDING';
        const gained = res?.pointsGranted ?? res?.credits ?? plan.credits;

        paymentResult.value = {
          orderNo,
          status,
          message: res?.message || '',
          credits: gained,
          amount: res?.amount ?? plan.price,
        };
        if (orderNo) {
          highlightOrderNo.value = orderNo;
          setTimeout(() => { highlightOrderNo.value = null; }, 8000);
        }

        if (isPending) {
          // PENDING 订单不改变余额/tier（未到账），刷新订单列表即可
          showToast(res?.message || '订单已提交，等待管理员确认收款', 'success');
          loadOrders(0);
        } else {
          showToast(`购买成功，获得 ${gained} 积分！${orderNo ? `（订单号 ${orderNo}）` : ''}`, 'success');
          creditsBalance.value = (typeof res?.newBalance === 'number') ? res.newBalance : (creditsBalance.value + gained);
          currentPlan.value = {
            planCode: tierToPlanCode(res?.subscriptionTier || plan.tier),
            tier: res?.subscriptionTier || plan.tier,
            expiresAt: res?.expiresAt || null,
          };
          // 刷新余额、tier、订单列表
          setTimeout(() => {
            loadBalanceAndTier();
            loadOrders(0);
            emit('refreshCredits');
          }, 300);
        }
      } catch (err) {
        let msg = err.message || '购买失败';
        if (err.errorCode === 'PLAN_NOT_FOUND') msg = '套餐不存在或已下架';
        else if (err.errorCode === 'ORDER_STATUS_INVALID') msg = '订单状态异常，请稍后重试';
        else if (err.errorCode === 'DUPLICATE_PURCHASE' || /重复|already/.test(msg)) msg = '您已购买相同档位，无法重复购买';
        showToast(msg, 'error');
      } finally { purchasingPlan.value = null; }
    }

    async function cancelOrder(order) {
      const ok = await showConfirm({ title: '取消订单', message: `确定取消订单 ${order.orderNo}？取消后无法恢复。`, type: 'warning' });
      if (!ok) return;
      try {
        await subscriptionApi.cancelOrder(order.orderNo);
        showToast('订单已取消', 'success');
        loadOrders(orderPage.value);
        if (detail.value?.orderNo === order.orderNo) { detail.value = { ...detail.value, status: 'CANCELLED' }; }
      } catch (err) { showToast(err.message || '取消失败', 'error'); }
    }

    function openRefundDialog(order) {
      refundTarget.value = order;
      refundReason.value = '';
      showRefundDialog.value = false;
      showRefundDialog.value = true;
    }

    async function submitRefund() {
      if (refundReason.value.trim().length < 5) { showToast('请填写至少5个字的退款原因', 'warning'); return; }
      if (!refundTarget.value) return;
      refundSubmitting.value = true;
      try {
        const res = await subscriptionApi.refundRequest(refundTarget.value.orderNo, refundReason.value.trim());
        showToast(`退款申请已提交${res?.refundPoints ? `，扣减 ${res.refundPoints} 积分` : ''}`, 'success');
        showRefundDialog.value = false;
        showOrderDrawer.value = false;
        if (typeof res?.newBalance === 'number') creditsBalance.value = res.newBalance;
        loadOrders(orderPage.value);
        loadBalanceAndTier();
        emit('refreshCredits');
      } catch (err) { showToast(err.message || '退款申请失败', 'error'); }
      finally { refundSubmitting.value = false; }
    }

    function openDisputeDialog(order) {
      disputeTarget.value = order;
      disputeReason.value = '';
      showDisputeDialog.value = true;
    }

    async function submitDispute() {
      if (disputeReason.value.trim().length < 5) { showToast('请填写至少5个字的申诉原因', 'warning'); return; }
      if (!disputeTarget.value) return;
      disputeSubmitting.value = true;
      try {
        const res = await subscriptionApi.disputeOrder(disputeTarget.value.orderNo, disputeReason.value.trim());
        showToast('纠纷申诉已提交，请等待管理员处理', 'success');
        showDisputeDialog.value = false;
        showOrderDrawer.value = false;
        loadOrders(orderPage.value);
      } catch (err) { showToast(err.message || '申诉失败', 'error'); }
      finally { disputeSubmitting.value = false; }
    }

    async function openOrderDetail(order) {
      showOrderDrawer.value = true;
      detail.value = normalizeOrder(order);
      relatedTransactions.value = [];
      try {
        const d = await subscriptionApi.getOrderDetail(order.orderNo);
        if (d) {
          detail.value = normalizeOrder({ ...order, ...d });
          // 后端在 detail 中一并返回 relatedTransactions
          if (Array.isArray(d.relatedTransactions)) {
            relatedTransactions.value = d.relatedTransactions.map(normalizeRelatedTx);
          }
        }
      } catch (e) {
        // 详情接口失败时抽屉仍展示列表里的基础信息；记录原因便于排查（原先静默吞掉）
        logger.warn('加载订单详情失败:', e);
      }
    }

    async function copyText(text, successMsg) {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(text);
          showToast(successMsg || '已复制', 'success');
          return;
        }
      } catch (e) {
        // clipboard API 不可用（非 HTTPS / 权限不足）时回退到 execCommand，这里只记调试日志
        logger.debug('clipboard API 复制失败，回退 execCommand:', e);
      }
      try {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta);
        showToast(successMsg || '已复制', 'success');
      } catch (e) {
        // 两条复制途径都不可用（非 HTTPS + execCommand 被禁用，极少见）：
        // 提示用户手动记录，不再使用原生 window.prompt（已按企业级约定清除原生弹窗）
        showToast(`复制失败，请手动记录：${text}`, 'error');
      }
    }

    function goToCredits() {
      showUpgradeDialog.value = false;
      emit('switchTab', 'credits');
      if (router) router.push({ path: '/user-center', query: { tab: 'credits' } });
      else window.location.href = '/user-center?tab=credits';
    }
    function goToOrders() {
      showUpgradeDialog.value = false;
      emit('switchTab', 'subscription');
      if (router && router.currentRoute.value.path === '/user-center') {
        // 已在用户中心，直接滚动到订单区域
        nextTick(() => {
          const el = document.querySelector('.orders-list-section');
          if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
      } else if (router) {
        router.push({ path: '/user-center', query: { tab: 'subscription' } });
      } else {
        window.location.href = '/user-center?tab=subscription';
      }
    }
    function goToCreditsWithRelated(orderNo) {
      showOrderDrawer.value = false;
      emit('switchTab', 'credits');
      if (router) router.push({ path: '/user-center', query: { tab: 'credits', relatedId: orderNo } });
      else window.location.href = `/user-center?tab=credits&relatedId=${orderNo}`;
    }

    // 状态文案统一取自 config/orderStatus.js：与筛选下拉、管理后台、文档同一套叫法
    const statusText = orderStatusLabel;
    function txTypeText(t) {
      return { SUBSCRIPTION_PURCHASE: '订阅购买', REFUND: '退款', ADMIN_ADJUST: '管理员补偿', ADMIN_ADJUSTMENT: '管理员调整', SIGN_IN: '签到奖励', DAILY_SIGN_IN: '每日签到', AI_CHAT: 'AI消耗', NEW_USER_BONUS: '新人福利', MONTHLY_LOGIN_BONUS: '每月登录赠送', LOYALTY_BONUS: '老用户福利', MONTHLY_CARD_DAILY: '月卡每日奖励' }[t] || t || '-';
    }
    function formatDateOnly(d) { if (!d) return '-'; const x = new Date(d); return isNaN(x) ? '-' : `${x.getFullYear()}-${String(x.getMonth()+1).padStart(2,'0')}-${String(x.getDate()).padStart(2,'0')}`; }
    function formatShortDate(d) { if (!d) return '-'; const x = new Date(d); if (isNaN(x)) return '-'; return `${x.getFullYear()}-${String(x.getMonth()+1).padStart(2,'0')}-${String(x.getDate()).padStart(2,'0')} ${String(x.getHours()).padStart(2,'0')}:${String(x.getMinutes()).padStart(2,'0')}`; }
    function formatLongDate(d) { if (!d) return '-'; const x = new Date(d); if (isNaN(x)) return '-'; return `${x.getFullYear()}-${String(x.getMonth()+1).padStart(2,'0')}-${String(x.getDate()).padStart(2,'0')} ${String(x.getHours()).padStart(2,'0')}:${String(x.getMinutes()).padStart(2,'0')}:${String(x.getSeconds()).padStart(2,'0')}`; }

    function onOpenUpgradeEvent() { openUpgradeDialog(); }

    // 监听签到成功事件刷新余额
    function onSignInSuccess() {
      loadBalanceAndTier();
    }

    onMounted(() => {
      loadUserAndPlan();
      loadOrders(0);
      window.addEventListener('subscription:open-upgrade', onOpenUpgradeEvent);
      window.addEventListener('credits:sign-in-success', onSignInSuccess);
      if (props.autoOpenUpgrade) setTimeout(() => openUpgradeDialog(), 300);
    });

    onUnmounted(() => {
      window.removeEventListener('subscription:open-upgrade', onOpenUpgradeEvent);
      window.removeEventListener('credits:sign-in-success', onSignInSuccess);
    });

    watch(() => props.autoOpenUpgrade, v => { if (v) { openUpgradeDialog(); } });

    return {
      userInfo, userAvatarUrl, creditsBalance, currentPlan, recentSpent, remainingDays,
      planBadgeClass, planBenefitsText, expiryText,
      showUpgradeDialog, plans, monthlyCardPlans, directPurchasePlans, purchasingPlan, isCurrentPlan, openUpgradeDialog, goToCredits,
      showPaymentDialog, paymentPlan, paymentResult, openPayment, submitPayment, viewPaymentOrder, finishPayment, onPaymentDialogClose,
      orderStatusOptions,
      orders, ordersLoading, orderPage, totalOrderPages, orderStatusFilter,
      loadOrders, cancelOrder, openRefundDialog, openOrderDetail, highlightOrderNo,
      showOrderDrawer, detail, relatedTransactions, timelineEvents,
      showRefundDialog, refundTarget, refundReason, refundSubmitting, submitRefund,
      statusText, txTypeText, formatShortDate, formatLongDate,
      showDisputeDialog, disputeTarget, disputeReason, disputeSubmitting, openDisputeDialog, submitDispute,
      copyText, goToCreditsWithRelated, goToOrders,
      formatYuan
    };
  }
};
</script>

<style scoped>
.subscription-dashboard { display: flex; flex-direction: column; gap: 20px; }
.section-card { background: var(--card-bg, #fff); border-radius: 8px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.section-card h4 { margin: 0; font-size: 15px; font-weight: 600; color: var(--text-primary, #333); }
.section-card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }

.current-plan-card { display: flex; justify-content: space-between; align-items: center; padding: 24px; background: var(--bg-tertiary, #f8f9fa); border: 1px solid #e0ebff; }
.plan-card-left { display: flex; gap: 16px; align-items: flex-start; }
.plan-badge { display: inline-flex; align-items: center; gap: 6px; padding: 6px 14px; border-radius: 20px; font-size: 13px; font-weight: 600; color: #fff; }
.plan-badge.free { background: #95a5a6; }
.plan-badge.lite { background: #3498db; }
.plan-badge.pro { background: #27ae60; }
.plan-badge.pro-plus { background: #8e44ad; }
.plan-badge.ultra { background: #f39c12; }
.plan-badge.mega { background: linear-gradient(135deg, #e74c3c, #c0392b); }
.plan-badge.month-card { background: linear-gradient(135deg, #ff9800, #f57c00); }
.plan-badge.month-card-large { background: linear-gradient(135deg, #9c27b0, #7b1fa2); }
/* 全功能版（同时持有小月卡 + 大月卡）：蓝色标识 */
.plan-badge.all { background: linear-gradient(135deg, #4a90e2, #2f6fd0); }
.plan-info { display: flex; flex-direction: column; gap: 6px; }
.plan-title { font-size: 16px; font-weight: 600; color: var(--text-primary, #2c3e50); }
/* "当前方案：xxx" 里的方案名用蓝色突出 */
.plan-name-hl { color: #2f6fd0; }
.plan-benefits { font-size: 13px; color: var(--text-secondary, #666); }
.plan-expiry { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-muted, #888); }
.plan-card-right { display: flex; flex-direction: column; gap: 10px; align-items: flex-end; }
.btn-upgrade { display: inline-flex; align-items: center; gap: 6px; padding: 8px 18px; background: #3498db; color: #fff; border: none; border-radius: 4px; font-size: 13px; font-weight: 500; cursor: pointer; transition: background 0.2s; }
.btn-upgrade:hover { background: #2980b9; }
.btn-upgrade-sm { padding: 8px 18px; background: #3498db; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.plan-mini-info { display: flex; gap: 16px; font-size: 12px; color: var(--text-muted, #888); }

.order-filter select { padding: 6px 10px; border: 1px solid var(--border-color, #ddd); border-radius: 4px; font-size: 13px; }
.orders-loading, .orders-empty { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 60px 20px; color: var(--text-muted, #888); }
.orders-loading .loading-spinner, .loading-spinner { width: 24px; height: 24px; border: 3px solid #f3f3f3; border-top: 3px solid #3498db; border-radius: 50%; animation: spin 1s linear infinite; }
@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }

.orders-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.orders-table th, .orders-table td { padding: 12px 10px; text-align: left; border-bottom: 1px solid var(--border-color, #eee); }
.orders-table th { background: var(--bg-tertiary, #f8f9fa); font-weight: 600; color: var(--text-secondary, #666); font-size: 12px; }
.orders-table tr.highlight { background: #fffbe6 !important; }
.orders-table tr.highlight td { animation: highlightPulse 1s ease; }
@keyframes highlightPulse { 0% { background: #ffe58f; } 100% { background: #fffbe6; } }
.order-no-cell { display: inline-flex; align-items: center; gap: 6px; color: #3498db; cursor: pointer; font-family: 'Consolas', monospace; }
.order-no-cell:hover { text-decoration: underline; }
.order-no-text { font-size: 12px; }
.amount-cell { color: #e67e22; font-weight: 500; }
.credits-cell { color: #27ae60; font-weight: 500; }
.status-tag { display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 500; border: 1px solid transparent; }
.status-PAID { background: rgba(82, 196, 26, 0.12); color: #52c41a; border-color: rgba(82, 196, 26, 0.4); }
.status-PENDING { background: rgba(217, 217, 217, 0.2); color: #8c8c8c; border-color: var(--border-color, #d9d9d9); }
.status-REFUNDED { background: rgba(250, 140, 22, 0.12); color: #fa8c16; border-color: rgba(250, 140, 22, 0.4); }
.status-CANCELLED { background: rgba(245, 34, 45, 0.1); color: #f5222d; border-color: rgba(245, 34, 45, 0.4); }
.status-EXPIRED { background: rgba(24, 144, 255, 0.1); color: #1890ff; border-color: rgba(24, 144, 255, 0.4); }
.status-PENDING_REFUND { background: rgba(255, 152, 0, 0.12); color: #ef6c00; border-color: rgba(255, 152, 0, 0.4); }
.status-DISPUTED { background: rgba(156, 39, 176, 0.12); color: #8e24aa; border-color: rgba(156, 39, 176, 0.4); }
.action-cell { white-space: nowrap; }
.btn-link { background: none; border: none; color: #3498db; cursor: pointer; font-size: 13px; padding: 4px 8px; border-radius: 4px; transition: background-color 0.2s; }
.btn-link:hover { text-decoration: underline; background: rgba(52, 152, 219, 0.1); }
.btn-link.btn-danger { color: #ff7875; }
.btn-link.btn-danger:hover { background: rgba(255, 120, 117, 0.15); }
/* 订单表格里的橙色按钮：文字用黑色（原先 #ffc53d 叠在橙底上几乎看不清） */
.btn-link.btn-warn { color: var(--text-primary, #000); }
.btn-link.btn-warn:hover { background: rgba(0, 0, 0, 0.08); }

.orders-pagination { display: flex; justify-content: center; align-items: center; gap: 15px; margin-top: 16px; font-size: 13px; color: var(--text-secondary, #666); }
.orders-pagination button { padding: 4px 12px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #ddd); border-radius: 4px; cursor: pointer; }
.orders-pagination button:disabled { opacity: 0.5; cursor: not-allowed; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.upgrade-dialog { background: var(--card-bg, #fff); border-radius: 12px; width: 95%; max-width: 900px; max-height: 90vh; overflow-y: auto; }
.upgrade-header { display: flex; justify-content: space-between; align-items: center; padding: 20px 24px; border-bottom: 1px solid var(--border-color, #f0f0f0); }
.upgrade-user { display: flex; align-items: center; gap: 12px; }
.upgrade-avatar { width: 40px; height: 40px; border-radius: 50%; border: 2px solid var(--border-color, #eee); object-fit: cover; }
.upgrade-nickname { font-size: 15px; font-weight: 600; color: var(--text-primary, #333); }
.user-level-tag { display: inline-block; margin-top: 2px; padding: 1px 8px; border-radius: 8px; font-size: 11px; color: #fff; }
.user-level-tag.free { background: #95a5a6; } .user-level-tag.lite { background: #3498db; }
.user-level-tag.all { background: #2f6fd0; }
.user-level-tag.pro { background: #27ae60; } .user-level-tag.pro-plus { background: #8e44ad; } .user-level-tag.ultra { background: #f39c12; }
.upgrade-header-actions { display: flex; align-items: center; gap: 10px; }
.credits-chip { display: inline-flex; align-items: center; gap: 6px; padding: 6px 14px; background: #fff7e6; color: #d46b08; border: 1px solid #ffd591; border-radius: 4px; font-size: 13px; font-weight: 500; cursor: pointer; }
.btn-disabled-sm { padding: 6px 14px; background: var(--bg-tertiary, #f5f5f5); color: #bbb; border: 1px solid var(--border-color, #e8e8e8); border-radius: 4px; font-size: 13px; cursor: not-allowed; }
.upgrade-close { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; background: none; border: none; color: var(--text-muted, #999); font-size: 22px; cursor: pointer; border-radius: 6px; }
.upgrade-close:hover { background: var(--bg-tertiary, #f5f5f5); }

.plan-groups { padding: 16px 24px 24px; display: flex; flex-direction: column; gap: 20px; }
.plan-group { background: var(--bg-tertiary, #fafbfc); border: 1px solid #eef0f3; border-radius: 8px; padding: 16px; }
.plan-group-header { margin-bottom: 12px; padding-bottom: 8px; border-bottom: 1px solid #eef0f3; }
.plan-group-title { display: flex; align-items: center; gap: 8px; }
.plan-group-title h4 { margin: 0; font-size: 15px; font-weight: 600; color: var(--text-primary, #333); }
.plan-group-sub { font-size: 12px; color: var(--text-muted, #888); margin-left: 4px; }
.plan-group-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.plan-group-dot.month { background: #ff9800; }
.plan-group-dot.direct { background: #1976d2; }

.plans-grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); }

.plan-card { position: relative; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e5e7eb); border-radius: 6px; padding: 16px 14px; display: flex; flex-direction: column; }
.plan-card-name { font-size: 15px; font-weight: 600; color: var(--text-primary, #333); margin-bottom: 8px; }
.plan-card-price { font-size: 28px; font-weight: 700; color: #e67e22; line-height: 1; margin-bottom: 6px; }
.plan-card-price sup { font-size: 14px; font-weight: 500; }
.plan-card-price sub { font-size: 12px; color: var(--text-muted, #999); font-weight: 400; }
.plan-card-credits { font-size: 13px; color: #27ae60; font-weight: 500; margin-bottom: 10px; }
.plan-card-features { list-style: none; padding: 0; margin: 0 0 12px; flex: 1; }
.plan-card-features li { display: flex; align-items: flex-start; gap: 6px; font-size: 12px; padding: 3px 0; color: #27ae60; }
.plan-card-features li span { color: var(--text-secondary, #555); }
.plan-card-btn { width: 100%; padding: 8px; background: var(--card-bg, #fff); color: #3498db; border: 1px solid #3498db; border-radius: 4px; font-size: 13px; cursor: pointer; transition: background 0.2s; }
.plan-card-btn:hover:not(:disabled) { background: #3498db; color: #fff; }

/* 旧样式占位，保持兼容 */
.upgrade-segment { display: none; }
.seg-btn, .seg-tag, .seg-soon { /* 废弃 */ }
.plan-discount { position: absolute; top: -1px; right: -1px; padding: 3px 8px; background: #52c41a; color: #fff; font-size: 11px; border-radius: 0 5px 0 5px; }

.modal-content { background: var(--card-bg, #fff); border-radius: 8px; width: 90%; max-width: 480px; }
.small-modal { max-width: 420px; }
.modal-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; border-bottom: 1px solid var(--border-color, #eee); }
.modal-header h3 { margin: 0; font-size: 18px; font-weight: 600; }
.btn-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; font-size: 22px; color: var(--text-muted, #999); border-radius: 4px; }
.btn-close:hover { background: var(--bg-tertiary, #f5f5f5); }
.modal-body { padding: 20px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 12px; padding: 16px 20px; border-top: 1px solid var(--border-color, #eee); background: var(--bg-tertiary, #fafafa); border-radius: 0 0 8px 8px; }
.btn-cancel { padding: 10px 20px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #d9d9d9); border-radius: 6px; cursor: pointer; color: var(--text-secondary, #666); }
.btn-cancel:hover { border-color: #40a9ff; color: #40a9ff; }
.btn-save { padding: 10px 20px; background: #3498db; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.btn-save:hover { background: #2980b9; }
.btn-save.btn-warn { background: #f39c12; }
.btn-save.btn-warn:hover { background: #e67e22; }

.form-group { margin-bottom: 16px; }
.form-group label { display: block; margin-bottom: 8px; font-size: 13px; font-weight: 500; color: var(--text-primary, #333); }
.form-group input, .form-group textarea { width: 100%; padding: 10px 12px; border: 1px solid var(--border-color, #d9d9d9); border-radius: 6px; font-size: 14px; font-family: inherit; resize: vertical; }
.form-group input:focus, .form-group textarea:focus { outline: none; border-color: #3498db; }

.refund-order-info { padding: 12px 16px; background: var(--bg-tertiary, #f8f9fa); border-radius: 8px; margin-bottom: 16px; font-size: 13px; }
.refund-order-info > div { display: flex; padding: 4px 0; }
.refund-order-info label { width: 90px; color: var(--text-muted, #888); flex-shrink: 0; }

.drawer-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.45); z-index: 1001; }
.drawer-container { position: absolute; right: 0; top: 0; bottom: 0; width: 560px; background: var(--card-bg, #fff); box-shadow: -8px 0 30px rgba(0,0,0,0.15); display: flex; flex-direction: column; }
.drawer-header { display: flex; justify-content: space-between; align-items: center; padding: 20px 24px; border-bottom: 1px solid var(--border-color, #f0f0f0); flex-shrink: 0; }
.drawer-title { display: flex; align-items: center; gap: 12px; font-size: 16px; font-weight: 600; color: var(--text-primary, #333); }
.drawer-order-no { display: inline-flex; align-items: center; gap: 6px; padding: 2px 10px; background: var(--bg-primary, #f5f7fa); border-radius: 6px; font-size: 12px; font-family: 'Consolas', monospace; color: #3498db; cursor: pointer; }
.drawer-close { width: 32px; height: 32px; background: none; border: none; cursor: pointer; font-size: 24px; color: var(--text-muted, #999); border-radius: 6px; }
.drawer-close:hover { background: var(--bg-tertiary, #f5f5f5); }
.drawer-body { flex: 1; overflow-y: auto; padding: 24px; }
.drawer-footer { padding: 16px 24px; border-top: 1px solid var(--border-color, #f0f0f0); flex-shrink: 0; }
.refund-pending-notice { width: 100%; padding: 10px 12px; background: rgba(255, 152, 0, 0.1); color: #ef6c00; border: 1px solid rgba(255, 152, 0, 0.3); border-radius: 4px; font-size: 13px; text-align: center; }
.btn-warn { padding: 8px 16px; background: #f39c12; color: #fff; border: none; border-radius: 4px; font-size: 13px; cursor: pointer; transition: background 0.2s; }
.btn-warn:hover { background: #e67e22; }
.btn-warn:disabled { background: #ffd591; cursor: not-allowed; color: #fff; }

.detail-section { margin-bottom: 24px; }
.detail-section h5 { margin: 0 0 16px; font-size: 14px; font-weight: 600; color: var(--text-primary, #333); padding-left: 10px; border-left: 3px solid #3498db; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 24px; }
.detail-item { display: flex; flex-direction: column; gap: 4px; }
.detail-item label { font-size: 12px; color: var(--text-muted, #888); }
.detail-item > span { font-size: 13px; color: var(--text-primary, #333); }

.timeline { position: relative; padding-left: 28px; }
.timeline-item { position: relative; padding-bottom: 24px; }
.timeline-item.last { padding-bottom: 0; }
.timeline-item::before { content: ''; position: absolute; left: -22px; top: 8px; bottom: -8px; width: 2px; background: var(--border-color, #e8e8e8); }
.timeline-item.last::before { display: none; }
.timeline-item.done::before { background: #52c41a; }
.timeline-dot { position: absolute; left: -28px; top: 4px; width: 14px; height: 14px; border-radius: 50%; background: #d9d9d9; border: 3px solid #fff; box-shadow: 0 0 0 1px #d9d9d9; }
.timeline-item.done .timeline-dot { background: #52c41a; box-shadow: 0 0 0 1px #52c41a; }
.timeline-content { display: flex; flex-direction: column; gap: 4px; }
.timeline-title { font-size: 14px; color: var(--text-primary, #333); font-weight: 500; }
.timeline-op { font-size: 12px; color: var(--text-muted, #888); font-weight: 400; }
.timeline-time { font-size: 12px; color: var(--text-muted, #999); }

.related-list { display: flex; flex-direction: column; gap: 10px; }
.related-item { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; background: var(--bg-tertiary, #f8f9fa); border-radius: 8px; cursor: pointer; transition: all 0.2s; }
.related-item:hover { background: #f0f5ff; transform: translateX(-4px); }
.related-left { display: flex; align-items: center; gap: 12px; }
.related-type { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 500; }
.related-type.SUBSCRIPTION_PURCHASE { background: #e6f7ff; color: #1890ff; }
.related-type.REFUND { background: #fff7e6; color: #fa8c16; }
.related-type.ADMIN { background: #f9f0ff; color: #722ed1; }
.related-type.SIGN_IN { background: #f6ffed; color: #52c41a; }
.related-type.MONTHLY_CARD_DAILY { background: #fff0f6; color: #c41d7f; }
.related-type.AI_CONSUME { background: #fff1f0; color: #f5222d; }
.related-desc { font-size: 13px; color: var(--text-secondary, #555); }
.related-right { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.related-amount { font-size: 14px; font-weight: 600; }
.related-amount.ADD { color: #27ae60; }
.related-amount.SUB { color: #e74c3c; }
.related-time { font-size: 12px; color: var(--text-muted, #999); }

.drawer-enter-active, .drawer-leave-active { transition: all 0.3s ease; }
.drawer-enter-from .drawer-container, .drawer-leave-to .drawer-container { transform: translateX(100%); }
.drawer-enter-from, .drawer-leave-to { opacity: 0; }
</style>

