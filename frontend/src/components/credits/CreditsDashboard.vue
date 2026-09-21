<template>
  <div class="credits-dashboard">
    <div v-if="isLoading" class="credits-skeleton">
      <div class="skeleton-banner"></div>
      <div class="skeleton-main-card"></div>
      <div class="skeleton-rewards">
        <div v-for="i in 4" :key="i" class="skeleton-reward-card"></div>
      </div>
      <div class="skeleton-table"></div>
      <div class="skeleton-chart"></div>
    </div>

    <template v-else>
      <div class="credits-banner section-card" :class="{ highlight: highlightSignIn }" ref="bannerRef">
        <div class="banner-left">
          <div class="banner-icon"><Icon name="celebrate" :size="36" /></div>
          <div class="banner-content">
            <div class="banner-title">
              欢迎使用积分体系 · 每日签到 +{{ signInPoints || signInBasePoints || 150 }}
              <span v-if="monthlyCardBonus > 0" class="banner-card-bonus">
                含{{ monthlyCardLabel }}额外 +{{ monthlyCardBonus }}
              </span>
              <span v-else-if="monthlyCardBonusTier > 0" class="banner-card-bonus">
                含{{ monthlyCardLabel }}额外 +{{ monthlyCardBonusTier }}（今日已发放）
              </span>
            </div>
            <div class="banner-subtitle">
              <span v-if="signInStatus.consecutiveDays > 0">已连续签到 {{ signInStatus.consecutiveDays }} 天</span>
              <span v-else>签到可解锁更多奖励权益</span>
            </div>
          </div>
        </div>
        <div class="banner-right">
          <button
            v-if="signInStatus.signedIn"
            class="btn-sign-in signed"
            disabled
          >
            <Icon name="check" :size="14" />
            今日已签
          </button>
          <button
            v-else
            class="btn-sign-in"
            :disabled="signInStatus.signedIn || isSigningIn"
            @click="doSignIn"
          >
            <Icon v-if="!isSigningIn" name="sparkles" :size="14" />
            <Icon v-else name="loader" :size="14" class="spin" />
            {{ isSigningIn ? '签到中...' : '立即签到' }}
          </button>
        </div>
      </div>

      <div class="credits-main-card section-card">
        <div class="main-card-top">
          <div class="main-card-left">
            <div class="main-icon-wrap">
              <Icon name="diamond" :size="36" class="main-icon" />
            </div>
            <div class="main-info">
              <div class="main-label">总可用积分</div>
              <div class="main-balance">{{ formatNumber(balance) }}</div>
            </div>
          </div>
          <button class="btn-upgrade" @click="gotoSubscription">
            <Icon name="layers" :size="14" />
            升级权益
          </button>
        </div>
        <div class="main-card-stats">
          <div class="stat-item">
            <span class="stat-label">累计收入</span>
            <span class="stat-value earned">+{{ formatNumber(totalEarned) }}</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat-item">
            <span class="stat-label">累计支出</span>
            <span class="stat-value spent">-{{ formatNumber(totalSpent) }}</span>
          </div>
          <div class="stat-divider"></div>
          <div class="stat-item">
            <span class="stat-label">当前订阅</span>
            <span class="stat-value tier">
              {{ tierLabel }}
              <small v-if="tierExpireAt">· {{ formatShortDate(tierExpireAt) }}到期</small>
            </span>
          </div>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header">
          <h4>用量明细</h4>
          <div class="detail-summary">
            <span class="summary-item">
              收入合计 <b class="earned">+{{ formatNumber(txIncomeTotal) }}</b>
            </span>
            <span class="summary-item">
              支出合计 <b class="spent">-{{ formatNumber(txSpendTotal) }}</b>
            </span>
            <span class="summary-item">
              净额 <b :class="txNet >= 0 ? 'earned' : 'spent'">{{ formatNumber(txNet) }}</b>
            </span>
          </div>
        </div>

        <div class="detail-tabs">
          <button
            v-for="t in [{ key: 'ALL', label: '全部' }, { key: 'INCOME', label: '收入' }, { key: 'EXPENSE', label: '支出' }]"
            :key="t.key"
            class="detail-tab"
            :class="{ active: activeDetailTab === t.key }"
            @click="changeDetailTab(t.key)"
          >
            {{ t.label }}
          </button>
        </div>

        <div class="filter-row">
          <select v-model="filters.type" class="filter-select">
            <option v-for="opt in typeOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
          <input
            v-model="filters.startDate"
            type="date"
            class="filter-date"
            placeholder="开始日期"
          />
          <span class="filter-sep">~</span>
          <input
            v-model="filters.endDate"
            type="date"
            class="filter-date"
            placeholder="结束日期"
          />
          <input
            v-model="filters.relatedId"
            type="text"
            class="filter-input"
            placeholder="订单号/对话号"
          />
          <button class="btn-filter" @click="searchTransactions(0)">
            <Icon name="search" :size="14" /> 查询
          </button>
          <button class="btn-filter reset" @click="resetFilters">
            重置
          </button>
        </div>

        <div v-if="transactionsLoading" class="tx-loading">
          <div class="loading-spinner"></div><span>加载中...</span>
        </div>

        <div v-else-if="transactions.length === 0" class="tx-empty">
          <Icon name="file-text" :size="32" />
          <p>暂无明细，去 AI 对话或签到吧</p>
          <div class="tx-empty-actions">
            <button class="btn-filter" @click="doSignIn" v-if="!signInStatus.signedIn">
              <Icon name="sparkles" :size="14" /> 立即签到
            </button>
          </div>
        </div>

        <table v-else class="tx-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>类型</th>
              <th>变动积分</th>
              <th>余额</th>
              <th>备注</th>
              <th>relatedId</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="tx in transactions" :key="tx.id || tx.createdAt">
              <td class="tx-time">{{ formatDateTime(tx.createdAt) }}</td>
              <td>
                <span class="tx-type-tag" :class="txAmountClass(tx)">
                  {{ txTypeLabel(tx.type) }}
                </span>
              </td>
              <td>
                <span :class="txAmountClass(tx)">
                  {{ txAmountDisplay(tx) }}
                </span>
              </td>
              <td>{{ formatNumber(tx.balanceAfter !== undefined ? tx.balanceAfter : '-') }}</td>
              <td class="tx-remark">{{ tx.remark || '-' }}</td>
              <td>
                <span v-if="tx.relatedId" class="tx-related-id" @click="copyRelatedId(tx.relatedId)" title="点击复制">
                  {{ shortRelatedId(tx.relatedId) }}
                  <Icon name="copy" :size="12" />
                </span>
                <span v-else>-</span>
              </td>
            </tr>
          </tbody>
        </table>

        <div v-if="transactions.length > 0" class="tx-pagination">
          <button
            :disabled="txPage <= 0 || transactionsLoading"
            @click="searchTransactions(txPage - 1)"
          >
            上一页
          </button>
          <span>{{ txPage + 1 }} / {{ totalTxPages }}</span>
          <button
            :disabled="txPage >= totalTxPages - 1 || transactionsLoading"
            @click="searchTransactions(txPage + 1)"
          >
            下一页
          </button>
          <span class="tx-size-info">每页 {{ txSize }} 条，共 {{ transactionsTotal }} 条</span>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header">
          <h4>积分趋势</h4>
          <div class="trend-controls">
            <button
              v-for="d in [7, 30]"
              :key="d"
              class="chart-btn"
              :class="{ active: trendDays === d }"
              @click="switchTrendDays(d)"
            >
              {{ d }}天
            </button>
          </div>
        </div>
        <div v-if="trendLoading" class="trend-loading">
          <div class="loading-spinner"></div><span>加载中...</span>
        </div>
        <v-chart
          v-else
          class="trend-chart-echarts"
          :option="trendChartOption"
          autoresize
        />
      </div>
    </template>
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import Icon from '../Icon.vue';
import { TX_TYPE_OPTIONS, useCreditsDashboard } from '../../composables/useCreditsDashboard';
// 图表组件局部注册（入口不再全局注册 VChart），echarts 只随需要图表的页面加载
import { VChart } from '../../config/echarts';

export default {
  name: 'CreditsDashboard',
  components: { Icon, VChart },
  emits: ['openUpgrade', 'switchTab'],
  props: {
    autoFocusSignIn: { type: Boolean, default: false },
    autoRelatedId: { type: String, default: '' },
  },
  setup(props, { emit }) {
    const bannerRef = ref(null);
    const typeOptions = TX_TYPE_OPTIONS;
    const rewardIconNames = ['gift', 'calendar', 'calendar', 'crown'];

    const showSystemMsg = (msg, type = 'success') => {
      window.dispatchEvent(new CustomEvent('system:toast', { detail: { msg, type } }));
    };

    const credits = useCreditsDashboard({
      showSystemMsg,
      onOpenUpgrade: () => emit('openUpgrade'),
      onSwitchTab: (tab) => emit('switchTab', tab),
    });

    // 规范化后端奖励字段：{ name, type, valueOrProgress:{x,y}, points, done }
    // → 模板字段：{ id, name, type, creditAmount, status, progressCurrent, progressTotal, expireAt }
    const normalizedRewards = computed(() => {
      const list = credits.rewards.value && credits.rewards.value.length > 0
        ? credits.rewards.value
        : [];
      if (list.length === 0) {
        return [
          { id: 'new_user', name: '新用户福利', type: 'NEW_USER_BONUS', creditAmount: 500, status: 'PENDING', progressCurrent: 0, progressTotal: 1 },
          { id: 'daily_sign', name: '每日签到', type: 'DAILY_SIGN_IN', creditAmount: 150, status: 'PENDING', progressCurrent: 0, progressTotal: 1 },
          { id: 'monthly_login', name: '每月登录赠送', type: 'MONTHLY_LOGIN_BONUS', creditAmount: 1000, status: 'PENDING', progressCurrent: 3, progressTotal: 20 },
          { id: 'loyalty', name: '老用户福利', type: 'LOYALTY_BONUS', creditAmount: 2000, status: 'CLAIMED', progressCurrent: 365, progressTotal: 365 },
        ];
      }
      return list.map((r, idx) => {
        const progress = r.valueOrProgress || r.progress || {};
        const current = Number(progress.x ?? r.progressCurrent ?? 0);
        const total = Number(progress.y ?? r.progressTotal ?? 1);
        const done = r.done === true || r.status === 'CLAIMED';
        return {
          id: r.id || `r-${idx}`,
          name: r.name || '系统奖励',
          type: r.type || '',
          creditAmount: r.creditAmount ?? r.points ?? 0,
          progressCurrent: current,
          progressTotal: total,
          status: done ? 'CLAIMED' : (current > 0 && current >= total ? 'AVAILABLE' : 'PENDING'),
          expireAt: r.expireAt || r.expiresAt || null,
        };
      });
    });

    const rewardTypeName = (type) => {
      const map = {
        NEW_USER_BONUS: '一次性奖励',
        DAILY_SIGN_IN: '每日任务',
        SIGN_IN: '每日任务',
        MONTHLY_LOGIN_BONUS: '每月任务',
        LOYALTY_BONUS: '里程碑奖励',
        OLD_USER_LOYALTY: '里程碑奖励',
      };
      return map[type] || '系统奖励';
    };

    const rewardStatusClass = (status) => {
      if (status === 'CLAIMED') return 'claimed';
      if (status === 'AVAILABLE') return 'available';
      return 'pending';
    };

    const rewardStatusText = (status) => {
      if (status === 'CLAIMED') return '已领取';
      if (status === 'AVAILABLE') return '可领取';
      return '进行中';
    };

    // 后端 amount 始终为正数，direction (IN/OUT) 决定收入/支出
    const txAmountClass = (tx) => {
      const dir = typeof tx === 'object' ? (tx.direction || (tx.amount < 0 ? 'OUT' : 'IN')) : null;
      if (typeof tx === 'number') {
        return tx > 0 ? 'earned' : tx < 0 ? 'spent' : '';
      }
      return dir === 'OUT' ? 'spent' : 'earned';
    };

    const txAmountDisplay = (tx) => {
      const amount = Number(tx.amount) || 0;
      const dir = tx.direction || (amount < 0 ? 'OUT' : 'IN');
      const abs = Math.abs(amount);
      return (dir === 'OUT' ? '-' : '+') + (abs).toLocaleString('zh-CN');
    };

    const formatShortDate = (dateStr) => {
      if (!dateStr) return '-';
      const d = new Date(dateStr);
      if (isNaN(d.getTime())) return dateStr;
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    };

    const formatDateTime = (dateStr) => {
      if (!dateStr) return '-';
      const d = new Date(dateStr);
      if (isNaN(d.getTime())) return dateStr;
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    };

    const shortRelatedId = (id) => {
      if (!id) return '-';
      const str = String(id);
      if (str.length <= 10) return str;
      return str.slice(0, 6) + '...' + str.slice(-4);
    };

    const copyRelatedId = async (id) => {
      try {
        await navigator.clipboard.writeText(String(id));
        showSystemMsg('已复制: ' + id);
      } catch {
        showSystemMsg('复制失败', 'error');
      }
    };

    // 订阅页购买/退款成功后触发余额刷新
    const onCreditsRefresh = () => {
      credits.loadBalance();
      credits.loadTransactions(credits.txPage.value, credits.txSize.value);
    };

    onMounted(() => {
      // 应用 relatedId 过滤（来自订阅详情抽屉跳转）
      if (props.autoRelatedId) {
        credits.filters.value.relatedId = props.autoRelatedId;
      }
      credits.loadAll();
      if (props.autoFocusSignIn) {
        nextTick(() => {
          credits.triggerSignInHighlight();
          bannerRef.value?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        });
      }
      window.addEventListener('credits:refresh', onCreditsRefresh);
    });

    // 监听 autoRelatedId 变化（从订阅详情跳转过来时组件可能已挂载）
    watch(() => props.autoRelatedId, (newVal) => {
      if (newVal) {
        credits.filters.value.relatedId = newVal;
        credits.searchTransactions(0);
      }
    });

    onUnmounted(() => {
      window.removeEventListener('credits:refresh', onCreditsRefresh);
    });

    return {
      ...credits,
      bannerRef,
      typeOptions,
      rewardIconNames,
      normalizedRewards,
      rewardTypeName,
      rewardStatusClass,
      rewardStatusText,
      txAmountClass,
      txAmountDisplay,
      formatShortDate,
      formatDateTime,
      shortRelatedId,
      copyRelatedId,
    };
  },
};
</script>

<style scoped>
.credits-dashboard {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.credits-skeleton {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.skeleton-banner,
.skeleton-main-card,
.skeleton-table,
.skeleton-chart {
  height: 120px;
  background: linear-gradient(90deg, var(--border-color, #f0f0f0) 25%, #e8e8e8 50%, #f0f0f0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 8px;
}

.skeleton-main-card {
  height: 180px;
}

.skeleton-table {
  height: 320px;
}

.skeleton-chart {
  height: 300px;
}

.skeleton-rewards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.skeleton-reward-card {
  height: 140px;
  background: linear-gradient(90deg, var(--border-color, #f0f0f0) 25%, #e8e8e8 50%, #f0f0f0 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 8px;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.credits-banner {
  background: linear-gradient(135deg, #e8f4fd 0%, #d1ecff 50%, #e3f2ff 100%);
  border-radius: 12px;
  padding: 20px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #bcdcff;
  transition: all 0.3s ease;
}

.credits-banner.highlight {
  animation: bannerPulse 1.6s ease-in-out 2;
}

@keyframes bannerPulse {
  0%, 100% {
    box-shadow: 0 0 0 rgba(52, 152, 219, 0);
    background: linear-gradient(135deg, #e8f4fd 0%, #d1ecff 50%, #e3f2ff 100%);
  }
  50% {
    box-shadow: 0 0 24px rgba(52, 152, 219, 0.35);
    background: linear-gradient(135deg, #d4edfc 0%, #b6dcff 50%, #cfe8ff 100%);
  }
}

.banner-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.banner-icon {
  font-size: 36px;
  line-height: 1;
}

.banner-title {
  font-size: 18px;
  font-weight: 600;
  color: #1a4e80;
  margin-bottom: 4px;
}

.banner-subtitle {
  font-size: 13px;
  color: #4a7ba8;
}

/* 月卡每日额外积分标记（签到卡上体现月卡权益） */
.banner-card-bonus {  display: inline-block;
  margin-left: 6px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
  color: #b8860b;
  background: linear-gradient(135deg, #fff4d1, #ffe9a8);
  border: 1px solid #f0d27a;
  vertical-align: middle;
}

.btn-sign-in {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  background: linear-gradient(135deg, #3498db, #2980b9);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(52, 152, 219, 0.3);
}

.btn-sign-in:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(52, 152, 219, 0.4);
}

.btn-sign-in:disabled,
.btn-sign-in.signed {
  background: #95a5a6;
  cursor: not-allowed;
  box-shadow: none;
  opacity: 0.9;
}

.btn-sign-in .spin {
  animation: spin 1s linear infinite;
}

.credits-main-card {
  border-radius: 12px;
  padding: 24px;
  background: linear-gradient(135deg, var(--card-bg, #ffffff) 0%, #fafbff 100%);
  border: 1px solid #e8ecf3;
}

.main-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
}

.main-card-left {
  display: flex;
  align-items: center;
  gap: 18px;
}

.main-icon-wrap {
  width: 64px;
  height: 64px;
  background: linear-gradient(135deg, #fff4cc, #ffe4a0);
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(243, 156, 18, 0.15);
}

.main-icon {
  font-size: 36px;
  line-height: 1;
}

.main-label {
  font-size: 14px;
  color: #7f8c8d;
  margin-bottom: 6px;
}

.main-balance {
  font-size: 42px;
  font-weight: 700;
  color: var(--text-primary, #2c3e50);
  line-height: 1;
  letter-spacing: -0.5px;
  background: linear-gradient(135deg, #2c3e50, #3498db);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.btn-upgrade {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: linear-gradient(135deg, #9b59b6, #8e44ad);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 8px rgba(155, 89, 182, 0.25);
}

.btn-upgrade:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(155, 89, 182, 0.35);
}

.main-card-stats {
  display: flex;
  align-items: center;
  padding: 16px;
  background: var(--bg-tertiary, #f8f9fc);
  border-radius: 8px;
  gap: 20px;
}

.stat-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.stat-label {
  font-size: 12px;
  color: #95a5a6;
}

.stat-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #2c3e50);
}

.stat-value.earned {
  color: #27ae60;
}

.stat-value.spent {
  color: #e74c3c;
}

.stat-value.tier {
  color: #9b59b6;
  font-size: 15px;
}

.stat-value small {
  font-size: 12px;
  color: #95a5a6;
  font-weight: 400;
  margin-left: 2px;
}

.stat-divider {
  width: 1px;
  height: 36px;
  background: #e1e4ea;
  flex-shrink: 0;
}

.section-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.section-card-header h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.section-hint {
  font-size: 12px;
  color: #95a5a6;
}

.rewards-loading,
.tx-loading,
.trend-loading,
.orders-loading {
  text-align: center;
  padding: 32px;
  color: #7f8c8d;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.rewards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.reward-card {
  padding: 16px;
  border-radius: 10px;
  background: var(--card-bg, white);
  border: 1px solid #eef0f4;
  transition: all 0.2s;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.reward-card:hover {
  border-color: #d0d7e2;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}

.reward-card.claimed {
  background: var(--bg-tertiary, #fafbfc);
  opacity: 0.85;
}

.reward-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.reward-emoji {
  font-size: 28px;
  line-height: 1;
  flex-shrink: 0;
}

.reward-title-block {
  flex: 1;
  min-width: 0;
}

.reward-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #2c3e50);
  margin-bottom: 2px;
}

.reward-type {
  font-size: 11px;
  color: #95a5a6;
}

.reward-progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.progress-bar {
  height: 6px;
  background: var(--bg-tertiary, #ecf0f1);
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #3498db, #2ecc71);
  border-radius: 3px;
  transition: width 0.4s ease;
}

.progress-text {
  font-size: 12px;
  color: var(--text-secondary, #666);
}

.progress-text b {
  color: #27ae60;
  font-weight: 600;
}

.reward-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid #f0f2f5;
  font-size: 11px;
}

.reward-expiry {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #95a5a6;
}

.reward-status {
  padding: 2px 10px;
  border-radius: 10px;
  font-weight: 500;
}

.reward-status.pending {
  background: #fff5e1;
  color: #b78000;
}

.reward-status.available {
  background: #d4edda;
  color: #155724;
}

.reward-status.claimed {
  background: #e2e8f0;
  color: #64748b;
}

.detail-summary {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-secondary, #666);
}

.detail-summary .summary-item b {
  margin-left: 4px;
  font-weight: 600;
}

.detail-summary .earned {
  color: #27ae60;
}

.detail-summary .spent {
  color: #e74c3c;
}

.detail-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eef0f4;
}

.detail-tab {
  padding: 6px 16px;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: #7f8c8d;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  font-weight: 500;
}

.detail-tab:hover {
  color: #3498db;
  background: var(--bg-tertiary, #f0f7ff);
}

.detail-tab.active {
  color: white;
  background: #3498db;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.filter-select,
.filter-date,
.filter-input {
  padding: 7px 10px;
  border: 1px solid var(--border-color, #ddd);
  border-radius: 6px;
  font-size: 13px;
  background: var(--card-bg, white);
  color: var(--text-primary, #333);
  outline: none;
  transition: border-color 0.2s;
}

.filter-select:focus,
.filter-date:focus,
.filter-input:focus {
  border-color: #3498db;
}

.filter-select {
  min-width: 140px;
}

.filter-input {
  min-width: 180px;
}

.filter-sep {
  color: #95a5a6;
  font-size: 13px;
}

.btn-filter {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 14px;
  background: #3498db;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-filter:hover {
  background: #2980b9;
}

.btn-filter.reset {
  background: var(--bg-tertiary, #ecf0f1);
  color: var(--text-secondary, #555);
}

.btn-filter.reset:hover {
  background: #dfe6e9;
}

.tx-empty {
  text-align: center;
  padding: 48px 20px;
  color: #7f8c8d;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.tx-empty p {
  margin: 0;
  font-size: 14px;
}

.tx-empty-actions {
  display: flex;
  gap: 10px;
}

.tx-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--card-bg, white);
  border-radius: 6px;
  overflow: hidden;
}

.tx-table th,
.tx-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid #f0f2f5;
  font-size: 13px;
}

.tx-table th {
  background: var(--bg-tertiary, #f8f9fb);
  font-weight: 600;
  color: var(--text-secondary, #666);
  font-size: 12px;
}

.tx-table tbody tr:hover {
  background: var(--bg-tertiary, #fafbfc);
}

.tx-time {
  color: var(--text-secondary, #666);
  font-size: 12px;
  white-space: nowrap;
}

.tx-type-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.tx-type-tag.earned {
  background: #e8f8ef;
  color: #1e8449;
}

.tx-type-tag.spent {
  background: #fdecea;
  color: #c0392b;
}

.tx-remark {
  max-width: 220px;
  color: var(--text-secondary, #666);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tx-related-id {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: #f0f4f8;
  border-radius: 4px;
  color: var(--text-secondary, #555);
  font-family: monospace;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.2s;
}

.tx-related-id:hover {
  background: #e1e8ef;
  color: #3498db;
}

.tx-pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 15px;
  margin-top: 16px;
  font-size: 13px;
  color: var(--text-secondary, #666);
}

.tx-pagination button {
  padding: 5px 14px;
  background: var(--card-bg, white);
  border: 1px solid var(--border-color, #ddd);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
  color: var(--text-primary, #333);
}

.tx-pagination button:hover:not(:disabled) {
  background: var(--bg-tertiary, #f0f7ff);
  border-color: #3498db;
  color: #3498db;
}

.tx-pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.tx-size-info {
  color: #95a5a6;
  font-size: 12px;
  margin-left: 10px;
}

.trend-controls {
  display: flex;
  gap: 8px;
}

.chart-btn {
  padding: 4px 12px;
  background-color: var(--card-bg, white);
  border: 1px solid var(--border-color, #ddd);
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary, #555);
  transition: all 0.2s;
}

.chart-btn.active {
  background-color: #3498db;
  color: white;
  border-color: #3498db;
}

.trend-chart-echarts {
  width: 100%;
  height: 320px;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

/* ===================== 暗色主题：浅色渐变卡片 ===================== */
/* 这些卡片用 background-image（渐变），不随 background-color 变量变化，需要单独覆盖 */
.theme-dark .credits-banner {
  background: linear-gradient(135deg, #1e293b 0%, #243b55 50%, #1e293b 100%);
  border-color: #24304a;
}
.theme-dark .banner-title { color: #bfdbfe; }
.theme-dark .banner-subtitle { color: #93c5fd; }
.theme-dark .banner-card-bonus {
  color: #fde68a;
  background: linear-gradient(135deg, #78350f, #92400e);
  border-color: #b45309;
}
.theme-dark .credits-main-card {
  background: linear-gradient(135deg, var(--card-bg, #1e1e3a) 0%, #16213e 100%);
  border-color: #24304a;
}
.theme-dark .main-icon-wrap { background: linear-gradient(135deg, #78350f, #92400e); }
</style>
