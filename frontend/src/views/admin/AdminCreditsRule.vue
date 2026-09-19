<template>
  <div class="tab-panel admin-credits-rule">
    <!-- 顶部工具条：改没改、能不能存、一键恢复，都在这一行看清楚 -->
    <div class="rule-toolbar">
      <div class="rule-toolbar-left">
        <Icon name="settings" :size="20" />
        <h2>积分规则配置</h2>
        <span v-if="isDirty" class="dirty-badge"><span class="dot"></span>有未保存的修改</span>
        <span v-else-if="loaded" class="clean-badge">已同步</span>
      </div>
      <div class="rule-toolbar-right">
        <span v-if="errorList.length" class="error-badge" :title="errorList.join('；')">
          {{ errorList.length }} 项待修正
        </span>
        <button class="btn-action" :disabled="ruleSaving" @click="loadRule">
          <Icon name="refresh" :size="14" /> 重新加载
        </button>
        <button class="btn-action" :disabled="defaultsLoading" @click="loadDefaults">
          {{ defaultsLoading ? '读取中…' : '填入默认值' }}
        </button>
        <button class="btn-action" :disabled="!isDirty" @click="discardChanges">放弃修改</button>
        <button class="btn-action promote" :disabled="ruleSaving || !isValid" @click="saveRule">
          {{ ruleSaving ? '保存中…' : '保存并立即生效' }}
        </button>
      </div>
    </div>

    <div v-if="ruleLoading" class="loading-box">加载规则中...</div>

    <div v-else class="rule-layout">
      <!-- 左侧分节导航 -->
      <aside class="rule-nav">
        <a
          v-for="s in sections"
          :key="s.id"
          class="nav-link"
          :class="{ active: activeSection === s.id }"
          :href="`#${s.id}`"
          @click.prevent="scrollTo(s.id)"
        >
          <Icon :name="s.icon" :size="14" />
          <span>{{ s.title }}</span>
          <span v-if="sectionErrors[s.id]" class="nav-err">{{ sectionErrors[s.id] }}</span>
        </a>
        <div class="nav-tip">
          改动保存后<b>立即生效</b>，无需重启。规则同时决定「积分扣费」与资金流水里的模拟现金账折算。
        </div>
      </aside>

      <div class="rule-main">
        <!-- 基础奖励 -->
        <section id="sec-base" class="section-card">
          <div class="section-head">
            <h4>基础奖励</h4>
            <span class="section-desc">新人注册与每日签到发多少积分</span>
          </div>
          <div class="field-grid">
            <div v-for="k in ['newUserBonus', 'signInPoints']" :key="k" class="field" :class="{ bad: errors[k] }">
              <label>{{ meta(k).label }}<span v-if="meta(k).unit" class="unit">（{{ meta(k).unit }}）</span></label>
              <input v-model.number="form[k]" type="number" min="0" class="config-input" />
              <small v-if="errors[k]" class="err">{{ errors[k] }}</small>
              <small v-else>{{ meta(k).hint }}</small>
            </div>
          </div>
          <div class="inline-note">
            签到实际发放 = 签到积分 + 月卡每日加成（小月卡 +100 / 大月卡 +300，双持叠加 +400），月卡部分在「套餐」区块说明。
          </div>
        </section>

        <!-- AI 对话计费 -->
        <section id="sec-ai" class="section-card">
          <div class="section-head">
            <h4>AI 对话计费</h4>
            <span class="section-desc">按 token 计费，再乘模型倍率、超配额倍率与折扣</span>
          </div>
          <div class="field-grid">
            <div v-for="k in ['tokenUnit', 'promptRate', 'completionRate', 'minCost', 'defaultCostPerMsg', 'imageExtraCost', 'monthlyFreeQuota', 'overtaxRate']" :key="k"
                 class="field" :class="{ bad: errors[k] }">
              <label>{{ meta(k).label }}<span v-if="meta(k).unit" class="unit">（{{ meta(k).unit }}）</span></label>
              <input v-model.number="form[k]" type="number" :step="meta(k).step || 1" min="0" class="config-input" />
              <small v-if="errors[k]" class="err">{{ errors[k] }}</small>
              <small v-else>{{ meta(k).hint }}</small>
            </div>
            <div class="field toggle-field">
              <label>管理员免费</label>
              <label class="rule-switch">
                <input type="checkbox" v-model="form.adminFree" />
                <span>{{ form.adminFree ? '已开启：管理员调用不计费' : '已关闭：管理员也计费' }}</span>
              </label>
            </div>
          </div>

          <!-- 模型倍率：行编辑代替 JSON -->
          <div class="sub-card" :class="{ bad: errors.modelRates }">
            <div class="sub-head">
              <span class="sub-title">{{ meta('modelRates').label }}</span>
              <span class="sub-desc">按模型名给倍率；没列出的模型走 default</span>
              <button class="mini-btn" @click="addModelRate"><Icon name="add" :size="12" /> 加一行</button>
            </div>
            <div class="kv-rows">
              <div v-for="(row, i) in modelRateRows" :key="i" class="kv-row">
                <input v-model="row.key" type="text" class="config-input" placeholder="模型名，如 default / gpt-4o" />
                <input v-model.number="row.rate" type="number" min="0" step="0.1" class="config-input narrow" placeholder="倍率" />
                <span class="kv-suffix">倍</span>
                <button class="mini-btn danger" @click="removeModelRate(i)"><Icon name="delete" :size="12" /></button>
              </div>
            </div>
            <small v-if="errors.modelRates" class="err">{{ errors.modelRates }}</small>
            <small v-else class="sub-hint">当前：{{ modelRateSummary }}</small>
          </div>

          <!-- 分析类型倍率 -->
          <div class="sub-card" :class="{ bad: errors.analyzeRates }">
            <div class="sub-head">
              <span class="sub-title">{{ meta('analyzeTypeRates').label }}</span>
              <span class="sub-desc">6 个分析维度可单独定价，default 兜底</span>
              <button class="mini-btn" @click="addAnalyzeRate"><Icon name="add" :size="12" /> 加一行</button>
            </div>
            <div class="kv-rows">
              <div v-for="(row, i) in analyzeRateRows" :key="i" class="kv-row">
                <input v-model="row.key" type="text" class="config-input" placeholder="分析类型，如 summary" />
                <span class="kv-label">{{ analyzeTypeLabel(row.key) }}</span>
                <input v-model.number="row.rate" type="number" min="0" step="0.1" class="config-input narrow" placeholder="倍率" />
                <span class="kv-suffix">倍</span>
                <button class="mini-btn danger" @click="removeAnalyzeRate(i)"><Icon name="delete" :size="12" /></button>
              </div>
            </div>
            <small v-if="errors.analyzeRates" class="err">{{ errors.analyzeRates }}</small>
            <small v-else class="sub-hint">留空代表不单独定价，走 default。</small>
          </div>
        </section>

        <!-- 分析与语音 -->
        <section id="sec-analyze" class="section-card">
          <div class="section-head">
            <h4>分析与语音</h4>
            <span class="section-desc">群分析按「基础费 + 条数 × 增量 × 类型倍率」，语音按字符数</span>
          </div>
          <div class="field-grid">
            <div v-for="k in ['analyzeBaseCost', 'analyzeCostPerMsg', 'ttsCharsPerCredit', 'ttsMinCost']" :key="k"
                 class="field" :class="{ bad: errors[k] }">
              <label>{{ meta(k).label }}<span v-if="meta(k).unit" class="unit">（{{ meta(k).unit }}）</span></label>
              <input v-model.number="form[k]" type="number" min="0" class="config-input" />
              <small v-if="errors[k]" class="err">{{ errors[k] }}</small>
              <small v-else>{{ meta(k).hint }}</small>
            </div>
          </div>
        </section>

        <!-- 折扣与封顶 -->
        <section id="sec-discount" class="section-card">
          <div class="section-head">
            <h4>折扣 / 上下文 / 封顶</h4>
            <span class="section-desc">月卡折扣只对月卡生效；阶梯折扣按本月累计消耗</span>
          </div>
          <div class="field-grid">
            <div v-for="k in ['smallMonthCardDiscount', 'largeMonthCardDiscount', 'allTierDiscount', 'contextFreeMsgCount', 'contextExtraCostPerMsg', 'dailyCapCost']" :key="k"
                 class="field" :class="{ bad: errors[k] }">
              <label>{{ meta(k).label }}<span v-if="meta(k).unit" class="unit">（{{ meta(k).unit }}）</span></label>
              <input v-model.number="form[k]" type="number" :step="meta(k).step || 1" :min="meta(k).discount ? 0.01 : 0" :max="meta(k).discount ? 1 : undefined" class="config-input" />
              <div v-if="meta(k).discount" class="quick-row">
                <button v-for="q in [1, 0.9, 0.8, 0.7, 0.5]" :key="q" class="quick-btn" :class="{ active: Number(form[k]) === q }" @click="form[k] = q">
                  {{ q === 1 ? '不打折' : q * 10 + ' 折' }}
                </button>
              </div>
              <small v-if="errors[k]" class="err">{{ errors[k] }}</small>
              <small v-else>{{ meta(k).hint }}</small>
            </div>
          </div>

          <!-- 阶梯折扣：行编辑 -->
          <div class="sub-card" :class="{ bad: errors.thresholds }">
            <div class="sub-head">
              <span class="sub-title">{{ meta('tieredDiscountThresholds').label }}</span>
              <span class="sub-desc">本月累计消耗 ≥ 阈值时打折（取最优一档）</span>
              <button class="mini-btn" @click="addThreshold"><Icon name="add" :size="12" /> 加一档</button>
            </div>
            <div v-if="thresholdRows.length === 0" class="sub-empty">未设置阶梯折扣（所有人按上面的月卡折扣计算）</div>
            <div class="kv-rows">
              <div v-for="(row, i) in thresholdRows" :key="i" class="kv-row">
                <span class="kv-label">本月累计 ≥</span>
                <input v-model.number="row.threshold" type="number" min="1" step="100" class="config-input narrow" />
                <span class="kv-suffix">积分 →</span>
                <input v-model.number="row.discount" type="number" min="0.01" max="1" step="0.01" class="config-input narrow" />
                <span class="kv-suffix">折</span>
                <button class="mini-btn danger" @click="removeThreshold(i)"><Icon name="delete" :size="12" /></button>
              </div>
            </div>
            <small v-if="errors.thresholds" class="err">{{ errors.thresholds }}</small>
          </div>
        </section>

        <!-- 套餐 -->
        <section id="sec-plan" class="section-card">
          <div class="section-head">
            <h4>套餐配置</h4>
            <span class="section-desc">4 档直购积分的价格与赠送积分（月卡价格由后端固定）</span>
          </div>
          <div class="plans-grid">
            <div v-for="plan in planMeta" :key="plan.key" class="plan-card" :style="{ borderTopColor: plan.color }">
              <div class="plan-name">{{ plan.name }}</div>
              <div class="plan-row">
                <label>价格（元）</label>
                <input v-model.number="form[plan.priceField]" type="number" min="0" step="0.01" class="config-input" />
              </div>
              <div class="plan-row">
                <label>赠送积分</label>
                <input v-model.number="form[plan.creditField]" type="number" min="0" class="config-input" />
              </div>
              <div class="plan-meta">
                ¥{{ Number(form[plan.priceField] || 0).toFixed(2) }} / {{ form[plan.creditField] || 0 }} 积分
                · 每积分成本 ¥{{ perCreditCost(plan) }}
              </div>
            </div>
          </div>
          <div class="field-grid plan-duration">
            <div class="field" :class="{ bad: errors.planDurationDays }">
              <label>{{ meta('planDurationDays').label }}<span class="unit">（{{ meta('planDurationDays').unit }}）</span></label>
              <input v-model.number="form.planDurationDays" type="number" min="1" class="config-input" />
              <small v-if="errors.planDurationDays" class="err">{{ errors.planDurationDays }}</small>
              <small v-else>{{ meta('planDurationDays').hint }}</small>
            </div>
          </div>
          <div class="inline-note">
            月卡（小月卡 ¥30/3000 积分、大月卡 ¥68/8000 积分、MEGA ¥648/100000 积分）由后端硬编码，不在这张表单里；
            订阅收入会按这些价格计入后台「资金流水」的模拟现金账。
          </div>
        </section>

        <!-- 试算器 -->
        <section id="sec-preview" class="section-card preview-card">
          <div class="section-head">
            <h4>费用试算</h4>
            <span class="section-desc">用当前表单（含未保存修改）实时算出用户要花多少积分 —— 与后端计费公式一致</span>
          </div>
          <div class="preview-grid">
            <div class="preview-inputs">
              <div class="preview-row">
                <label>输入 tokens</label>
                <input v-model.number="preview.promptTokens" type="number" min="0" class="config-input" />
                <label>输出 tokens</label>
                <input v-model.number="preview.completionTokens" type="number" min="0" class="config-input" />
              </div>
              <div class="preview-row">
                <label>图片张数</label>
                <input v-model.number="preview.imageCount" type="number" min="0" class="config-input" />
                <label>上下文条数</label>
                <input v-model.number="preview.contextMsgs" type="number" min="0" class="config-input" />
              </div>
              <div class="preview-row">
                <label>模型</label>
                <input v-model="preview.model" type="text" class="config-input" placeholder="default" />
                <label>用户档位</label>
                <select v-model="preview.tier" class="audit-action-select">
                  <option v-for="t in tierOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
                </select>
              </div>
              <div class="preview-row">
                <label>本月已消耗</label>
                <input v-model.number="preview.monthlySpent" type="number" min="0" class="config-input" />
                <label class="rule-switch preview-check">
                  <input type="checkbox" v-model="preview.inFreeQuota" />
                  <span>在每月免费次数内</span>
                </label>
              </div>
              <div class="preview-row">
                <label>分析条数</label>
                <input v-model.number="preview.analyzeMsgs" type="number" min="0" class="config-input" />
                <label>分析类型</label>
                <input v-model="preview.analyzeType" type="text" class="config-input" placeholder="summary" />
              </div>
              <div class="preview-row">
                <label>语音字符数</label>
                <input v-model.number="preview.ttsChars" type="number" min="0" class="config-input" />
              </div>
            </div>

            <div class="preview-results">
              <div class="result-card">
                <div class="result-title">AI 对话一次</div>
                <div class="result-cost">{{ chatPreview.cost }} <span class="unit">积分</span></div>
                <ul class="result-detail">
                  <li>基础 {{ chatPreview.base }}（粒度 {{ form.tokenUnit }} / 倍率 {{ form.promptRate }}·{{ form.completionRate }}）</li>
                  <li>图片 +{{ chatPreview.imageExtra }}，上下文 +{{ chatPreview.contextExtra }}（计费 {{ chatPreview.billableMsg }} 条）</li>
                  <li>模型倍率 ×{{ chatPreview.modelRate }}，超配额 ×{{ chatPreview.overtax }}</li>
                  <li>档位折扣 ×{{ chatPreview.tierDiscount }}，阶梯 ×{{ chatPreview.tiered }}</li>
                  <li v-if="chatPreview.quotaFree" class="free">命中每月免费次数 → 0 积分</li>
                </ul>
              </div>
              <div class="result-card">
                <div class="result-title">群分析一次</div>
                <div class="result-cost">{{ analyzePreview.cost }} <span class="unit">积分</span></div>
                <ul class="result-detail">
                  <li>基础 {{ form.analyzeBaseCost }} + {{ preview.analyzeMsgs }} 条 × {{ form.analyzeCostPerMsg }}</li>
                  <li>类型倍率 ×{{ analyzePreview.typeRate }}，档位 ×{{ analyzePreview.tierDiscount }}，阶梯 ×{{ analyzePreview.tiered }}</li>
                </ul>
              </div>
              <div class="result-card">
                <div class="result-title">语音合成一次</div>
                <div class="result-cost">{{ ttsPreview.cost }} <span class="unit">积分</span></div>
                <ul class="result-detail">
                  <li>{{ preview.ttsChars }} 字 ÷ {{ form.ttsCharsPerCredit }}（最小 {{ form.ttsMinCost }}）</li>
                  <li>档位 ×{{ ttsPreview.tierDiscount }}，阶梯 ×{{ ttsPreview.tiered }}</li>
                </ul>
              </div>
              <div class="result-card cash">
                <div class="result-title">折算成现金（模拟账）</div>
                <div class="result-cost">¥{{ (chatPreview.cost * cashPerCredit).toFixed(2) }}</div>
                <ul class="result-detail">
                  <li>按 100 积分 = ¥1 折算，写入「资金流水 → 模型调用成本」</li>
                  <li>折算率来自后端 `credits.cash.cost-per-credit`，改这里不影响它</li>
                </ul>
              </div>
            </div>
          </div>
        </section>

        <div v-if="errorList.length" class="error-panel">
          <strong>{{ errorList.length }} 项需要修正后才能保存：</strong>
          <ul><li v-for="(e, i) in errorList" :key="i">{{ e }}</li></ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { inject, ref, onMounted, onUnmounted, computed } from 'vue';
import Icon from '../../components/Icon.vue';
import { FIELD_META, ANALYZE_TYPE_META } from '../../composables/useAdminCreditsRule';

const SECTIONS = [
  { id: 'sec-base', title: '基础奖励', icon: 'coin' },
  { id: 'sec-ai', title: 'AI 对话计费', icon: 'chat' },
  { id: 'sec-analyze', title: '分析与语音', icon: 'file' },
  { id: 'sec-discount', title: '折扣与封顶', icon: 'tag' },
  { id: 'sec-plan', title: '套餐配置', icon: 'wallet' },
  { id: 'sec-preview', title: '费用试算', icon: 'chart' },
];

/** 每个字段属于哪个分节，用于导航上的错误角标 */
const FIELD_SECTION = {
  newUserBonus: 'sec-base', signInPoints: 'sec-base',
  tokenUnit: 'sec-ai', promptRate: 'sec-ai', completionRate: 'sec-ai', minCost: 'sec-ai',
  defaultCostPerMsg: 'sec-ai', imageExtraCost: 'sec-ai', monthlyFreeQuota: 'sec-ai',
  overtaxRate: 'sec-ai', modelRates: 'sec-ai', analyzeRates: 'sec-ai',
  analyzeBaseCost: 'sec-analyze', analyzeCostPerMsg: 'sec-analyze',
  ttsCharsPerCredit: 'sec-analyze', ttsMinCost: 'sec-analyze',
  smallMonthCardDiscount: 'sec-discount', largeMonthCardDiscount: 'sec-discount',
  allTierDiscount: 'sec-discount', contextFreeMsgCount: 'sec-discount',
  contextExtraCostPerMsg: 'sec-discount', dailyCapCost: 'sec-discount', thresholds: 'sec-discount',
  planDurationDays: 'sec-plan',
};

/** 与后端 credits.cash.cost-per-credit 默认值一致：100 积分 = ¥1 */
const CASH_PER_CREDIT = 0.01;

export default {
  name: 'AdminCreditsRule',
  components: { Icon },
  setup() {
    const adminRule = inject('adminCreditsRule');
    const activeSection = ref('sec-base');

    const sections = SECTIONS;
    const meta = (k) => FIELD_META[k] || { label: k };

    const sectionErrors = computed(() => {
      const out = {};
      Object.keys(adminRule.errors.value).forEach((field) => {
        const sec = FIELD_SECTION[field];
        if (!sec) return;
        out[sec] = (out[sec] || 0) + 1;
      });
      return out;
    });

    const analyzeTypeLabel = (key) => {
      const found = ANALYZE_TYPE_META.find((t) => t.key === key);
      return found ? found.label : '';
    };

    const modelRateSummary = computed(() => {
      const rows = adminRule.modelRateRows.value.filter((r) => String(r.key).trim());
      if (!rows.length) return '未配置';
      return rows.map((r) => `${r.key}×${r.rate}`).join('，');
    });

    const perCreditCost = (plan) => {
      const price = Number(adminRule.form[plan.priceField] || 0);
      const credits = Number(adminRule.form[plan.creditField] || 0);
      if (!credits) return '—';
      return (price / credits).toFixed(4);
    };

    const scrollTo = (id) => {
      activeSection.value = id;
      const el = document.getElementById(id);
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };

    // 滚动时高亮当前分节
    let observer = null;
    onMounted(async () => {
      if (!adminRule.loaded.value) await adminRule.loadRule();
      const main = document.querySelector('.rule-main');
      if (main && 'IntersectionObserver' in window) {
        observer = new IntersectionObserver((entries) => {
          entries.forEach((e) => { if (e.isIntersecting) activeSection.value = e.target.id; });
        }, { root: main, rootMargin: '-20% 0px -70% 0px', threshold: 0 });
        SECTIONS.forEach((s) => {
          const el = document.getElementById(s.id);
          if (el) observer.observe(el);
        });
      }
    });
    onUnmounted(() => { if (observer) observer.disconnect(); });

    return {
      ...adminRule,
      sections,
      meta,
      sectionErrors,
      analyzeTypeLabel,
      modelRateSummary,
      perCreditCost,
      scrollTo,
      activeSection,
      cashPerCredit: CASH_PER_CREDIT,
      previewTierOptions: adminRule.tierOptions,
    };
  },
};
</script>

<style scoped>
/* 顶部工具条 */
.rule-toolbar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  flex-wrap: wrap; padding: 12px 14px; margin-bottom: 14px; border-radius: 10px;
  background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8);
}
.rule-toolbar-left { display: flex; align-items: center; gap: 10px; }
.rule-toolbar-left h2 { margin: 0; font-size: 17px; color: var(--text-primary, #333); }
.rule-toolbar-right { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dirty-badge { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: #b7950b; background: rgba(241, 196, 15, .15); padding: 2px 9px; border-radius: 999px; }
.dirty-badge .dot { width: 6px; height: 6px; border-radius: 50%; background: #f1c40f; }
.clean-badge { font-size: 12px; color: #27ae60; background: rgba(46, 204, 113, .12); padding: 2px 9px; border-radius: 999px; }
.error-badge { font-size: 12px; color: #e74c3c; background: rgba(231, 76, 60, .12); padding: 2px 9px; border-radius: 999px; cursor: help; }

/* 两栏布局 */
.rule-layout { display: grid; grid-template-columns: 190px 1fr; gap: 16px; align-items: start; }
@media (max-width: 1100px) { .rule-layout { grid-template-columns: 1fr; } .rule-nav { display: none; } }
.rule-nav { position: sticky; top: 12px; display: flex; flex-direction: column; gap: 4px; padding: 12px; border-radius: 10px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); }
.nav-link { display: flex; align-items: center; gap: 8px; padding: 7px 9px; border-radius: 7px; font-size: 13px; color: var(--text-secondary, #666); text-decoration: none; }
.nav-link:hover { background: var(--bg-tertiary, #f5f5f5); color: var(--accent-color, #3498db); }
.nav-link.active { background: rgba(52, 152, 219, .12); color: var(--accent-color, #3498db); font-weight: 600; }
.nav-err { margin-left: auto; font-size: 11px; color: #e74c3c; background: rgba(231, 76, 60, .14); border-radius: 8px; padding: 0 6px; }
.nav-tip { margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--border-color, #eee); font-size: 11px; line-height: 1.6; color: var(--text-muted, #999); }

.rule-main { display: flex; flex-direction: column; gap: 16px; }

/* 分节卡片 */
.section-card { padding: 16px; border-radius: 10px; background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); scroll-margin-top: 12px; }
.section-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.section-head h4 { margin: 0; font-size: 15px; color: var(--text-primary, #333); }
.section-desc { font-size: 12px; color: var(--text-muted, #999); }
.field-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 14px; }
.field { display: flex; flex-direction: column; gap: 5px; }
.field > label { font-size: 13px; font-weight: 500; color: var(--text-primary, #333); }
.field .unit { font-weight: 400; font-size: 11px; color: var(--text-muted, #999); }
.field small { font-size: 11px; color: var(--text-muted, #999); }
.field small.err, small.err { color: #e74c3c; }
.field.bad .config-input, .sub-card.bad { border-color: #e74c3c; }
.field.bad .config-input { box-shadow: 0 0 0 2px rgba(231, 76, 60, .12); }
.toggle-field { justify-content: center; }
.rule-switch { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; font-size: 12px; color: var(--text-secondary, #666); }
.rule-switch input[type='checkbox'] { width: 16px; height: 16px; cursor: pointer; }
.quick-row { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 2px; }
.quick-btn { font-size: 11px; padding: 2px 7px; border-radius: 999px; border: 1px solid var(--border-color, #e0e0e0); background: transparent; color: var(--text-secondary, #666); cursor: pointer; }
.quick-btn.active { border-color: var(--accent-color, #3498db); color: var(--accent-color, #3498db); background: rgba(52, 152, 219, .1); }

/* 子卡片（行编辑器） */
.sub-card { margin-top: 14px; padding: 12px; border-radius: 8px; background: var(--bg-tertiary, #fafbfc); border: 1px dashed var(--border-color, #e0e0e0); }
.sub-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-primary, #333); }
.sub-desc { font-size: 11px; color: var(--text-muted, #999); }
.sub-hint { display: block; margin-top: 6px; font-size: 11px; color: var(--text-muted, #999); }
.sub-empty { font-size: 12px; color: var(--text-muted, #999); padding: 6px 0; }
.kv-rows { display: flex; flex-direction: column; gap: 8px; }
.kv-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.kv-row .config-input { flex: 1 1 180px; min-width: 120px; }
.kv-row .config-input.narrow { flex: 0 0 110px; min-width: 90px; }
.kv-label { font-size: 12px; color: var(--text-secondary, #666); white-space: nowrap; }
.kv-suffix { font-size: 12px; color: var(--text-muted, #999); }
.mini-btn { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; padding: 3px 8px; border-radius: 6px; border: 1px solid var(--border-color, #e0e0e0); background: transparent; color: var(--text-secondary, #666); cursor: pointer; }
.mini-btn:hover { color: var(--accent-color, #3498db); border-color: var(--accent-color, #3498db); }
.mini-btn.danger:hover { color: #e74c3c; border-color: #e74c3c; }
.sub-head .mini-btn { margin-left: auto; }

/* 套餐 */
.plans-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(210px, 1fr)); gap: 12px; }
.plan-card { padding: 12px; border-radius: 8px; background: var(--bg-tertiary, #fafbfc); border: 1px solid var(--border-color, #e8e8e8); border-top: 3px solid #90a4ae; display: flex; flex-direction: column; gap: 8px; }
.plan-name { font-size: 13px; font-weight: 600; color: var(--text-primary, #333); }
.plan-row { display: flex; flex-direction: column; gap: 4px; }
.plan-row label { font-size: 12px; color: var(--text-secondary, #666); }
.plan-meta { font-size: 11px; color: var(--text-muted, #999); }
.plan-duration { margin-top: 14px; grid-template-columns: minmax(200px, 240px); }

.inline-note { margin-top: 12px; padding: 9px 12px; border-radius: 6px; font-size: 12px; line-height: 1.6; color: var(--text-secondary, #666); background: var(--bg-tertiary, #f8f9fa); }

/* 试算器 */
.preview-card { border-left: 3px solid var(--accent-color, #3498db); }
.preview-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 1200px) { .preview-grid { grid-template-columns: 1fr; } }
.preview-inputs { display: flex; flex-direction: column; gap: 10px; }
.preview-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.preview-row label { font-size: 12px; color: var(--text-secondary, #666); min-width: 72px; }
.preview-row .config-input { flex: 1 1 110px; min-width: 90px; }
.preview-check { min-width: auto; }
.preview-results { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
@media (max-width: 700px) { .preview-results { grid-template-columns: 1fr; } }
.result-card { padding: 12px; border-radius: 8px; background: var(--bg-tertiary, #f8f9fa); border: 1px solid var(--border-color, #e8e8e8); }
.result-card.cash { border-color: rgba(46, 204, 113, .4); }
.result-title { font-size: 12px; color: var(--text-secondary, #666); }
.result-cost { font-size: 22px; font-weight: 700; color: var(--accent-color, #3498db); margin: 2px 0 6px; font-variant-numeric: tabular-nums; }
.result-cost .unit { font-size: 12px; font-weight: 400; color: var(--text-muted, #999); }
.result-detail { margin: 0; padding-left: 16px; font-size: 11px; line-height: 1.7; color: var(--text-muted, #888); }
.result-detail .free { color: #27ae60; }

.error-panel { padding: 12px 14px; border-radius: 8px; background: rgba(231, 76, 60, .08); border: 1px solid rgba(231, 76, 60, .3); color: #c0392b; font-size: 12px; }
.error-panel ul { margin: 6px 0 0; padding-left: 18px; line-height: 1.7; }
</style>
