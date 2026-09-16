<template>
  <div class="tab-panel admin-credits-rule">
    <div class="panel-title">
      <Icon name="settings" :size="20" />
      <h2>积分规则配置</h2>
    </div>

    <div v-if="ruleLoading" class="loading-box">加载规则中...</div>

    <div v-else class="credit-rule-wrap">
      <div class="section-card">
        <h4>基础奖励</h4>
        <div class="rule-form-grid">
          <div class="rule-item">
            <label>新人奖励</label>
            <input type="number" v-model.number="ruleForm.newUserBonus" min="0" class="config-input" />
            <small>注册即送积分</small>
          </div>
          <div class="rule-item">
            <label>每日签到积分</label>
            <input type="number" v-model.number="ruleForm.signInPoints" min="0" class="config-input" />
            <small>每日签到奖励</small>
          </div>
        </div>
      </div>

      <div class="section-card">
        <h4>AI 费率配置</h4>
        <div class="rule-form-grid">
          <div class="rule-item">
            <label>tokenUnit（每多少 token 计费）</label>
            <input type="number" v-model.number="ruleForm.tokenUnit" min="1" class="config-input" />
          </div>
          <div class="rule-item">
            <label>promptRate（输入倍率）</label>
            <input type="number" v-model.number="ruleForm.promptRate" min="0" class="config-input" />
          </div>
          <div class="rule-item">
            <label>completionRate（输出倍率）</label>
            <input type="number" v-model.number="ruleForm.completionRate" min="0" class="config-input" />
          </div>
          <div class="rule-item">
            <label>minCost（单次最小消耗）</label>
            <input type="number" v-model.number="ruleForm.minCost" min="0" class="config-input" />
          </div>
          <div class="rule-item">
            <label>defaultCostPerMsg（默认每条消耗）</label>
            <input type="number" v-model.number="ruleForm.defaultCostPerMsg" min="0" class="config-input" />
          </div>
          <div class="rule-item toggle-item">
            <label>管理员免费（adminFree）</label>
            <label class="rule-switch">
              <input type="checkbox" v-model="ruleForm.adminFree" />
              <span>{{ ruleForm.adminFree ? '已开启' : '已关闭' }}</span>
            </label>
          </div>
          <div class="rule-item toggle-item">
            <label>允许透支（allowOverdraft）</label>
            <label class="rule-switch">
              <input type="checkbox" v-model="ruleForm.allowOverdraft" />
              <span>{{ ruleForm.allowOverdraft ? '已开启' : '已关闭' }}</span>
            </label>
            <small>注：后端实体暂未持久化此字段</small>
          </div>
        </div>
      </div>

      <div class="section-card">
        <h4>精细化计费</h4>
        <div class="rule-form-grid">
          <div class="rule-item">
            <label>模型费率映射（JSON）</label>
            <textarea v-model="ruleForm.modelRates" class="config-input json-textarea" rows="3"></textarea>
            <small>如 {"default":1.0, "gpt-4":3.0}</small>
          </div>
          <div class="rule-item">
            <label>每张图片额外费用</label>
            <input type="number" v-model.number="ruleForm.imageExtraCost" min="0" class="config-input" />
          </div>
          <div class="rule-item">
            <label>分析基础费用</label>
            <input type="number" v-model.number="ruleForm.analyzeBaseCost" min="0" class="config-input" />
          </div>
          <div class="rule-item">
            <label>分析每条消息增量</label>
            <input type="number" v-model.number="ruleForm.analyzeCostPerMsg" min="0" class="config-input" />
          </div>
          <div class="rule-item">
            <label>分析类型倍率（JSON）</label>
            <textarea v-model="ruleForm.analyzeTypeRates" class="config-input json-textarea" rows="3"></textarea>
            <small>如 {"default":1.0, "summary":1.0, "analysis":1.5}</small>
          </div>
          <div class="rule-item">
            <label>TTS 每N字符扣1积分</label>
            <input type="number" v-model.number="ruleForm.ttsCharsPerCredit" min="1" class="config-input" />
          </div>
          <div class="rule-item">
            <label>TTS 最小消耗</label>
            <input type="number" v-model.number="ruleForm.ttsMinCost" min="0" class="config-input" />
          </div>
          <div class="rule-item">
            <label>月度免费配额（次）</label>
            <input type="number" v-model.number="ruleForm.monthlyFreeQuota" min="0" class="config-input" />
            <small>0=不免费，每月前N次AI聊天免费</small>
          </div>
          <div class="rule-item">
            <label>超配额费率倍数</label>
            <input type="number" v-model.number="ruleForm.overtaxRate" min="1" step="0.1" class="config-input" />
            <small>1.0=不涨价，1.5=涨50%</small>
          </div>
        </div>
      </div>

      <div class="section-card">
        <h4>月卡折扣 & 上下文增量 & 每日封顶 & 阶梯累计折扣</h4>
        <div class="rule-form-grid">
          <div class="rule-item">
            <label>小月卡折扣（0.01~1.0）</label>
            <input type="number" v-model.number="ruleForm.smallMonthCardDiscount" min="0.01" max="1" step="0.01" class="config-input" />
            <small>0.9=9折，1.0=无折扣（仅月卡生效）</small>
          </div>
          <div class="rule-item">
            <label>大月卡折扣（0.01~1.0）</label>
            <input type="number" v-model.number="ruleForm.largeMonthCardDiscount" min="0.01" max="1" step="0.01" class="config-input" />
            <small>0.8=8折，1.0=无折扣</small>
          </div>
          <div class="rule-item">
            <label>ALL 状态折扣（0.01~1.0）</label>
            <input type="number" v-model.number="ruleForm.allTierDiscount" min="0.01" max="1" step="0.01" class="config-input" />
            <small>0.7=7折，大小月卡同时拥有时生效</small>
          </div>
          <div class="rule-item">
            <label>上下文每条增量积分</label>
            <input type="number" v-model.number="ruleForm.contextExtraCostPerMsg" min="0" class="config-input" />
            <small>超过免费条数后每条历史消息+N积分</small>
          </div>
          <div class="rule-item">
            <label>上下文免费条数</label>
            <input type="number" v-model.number="ruleForm.contextFreeMsgCount" min="0" class="config-input" />
            <small>前 N 条历史消息不额外计费</small>
          </div>
          <div class="rule-item">
            <label>每日封顶消耗</label>
            <input type="number" v-model.number="ruleForm.dailyCapCost" min="0" class="config-input" />
            <small>单日累计达到后本次免费，0=不限</small>
          </div>
          <div class="rule-item grid-span-2">
            <label>阶梯累计折扣阈值（JSON）</label>
            <textarea v-model="ruleForm.tieredDiscountThresholds" class="config-input json-textarea" rows="2"></textarea>
            <small>例 {"1000":0.95,"5000":0.9,"20000":0.85} 本月累计≥1000时95折，≥5000时9折</small>
          </div>
        </div>
      </div>

      <div class="section-card">
        <h4>套餐配置（4 档直购积分，价格/积分以数据库规则为准）</h4>
        <div class="plans-edit-grid">
          <div v-for="plan in planMeta" :key="plan.key" class="plan-edit-card">
            <div class="plan-edit-name">{{ plan.name }}</div>
            <div class="plan-edit-row">
              <label>价格（元）</label>
              <input type="number" v-model.number="ruleForm[plan.priceField]" min="0" step="0.01" class="config-input" />
            </div>
            <div class="plan-edit-row">
              <label>赠送积分</label>
              <input type="number" v-model.number="ruleForm[plan.creditField]" min="0" class="config-input" />
            </div>
          </div>
        </div>
        <div class="plan-duration-row">
          <label>套餐时长（天，所有档位共享）</label>
          <input type="number" v-model.number="ruleForm.planDurationDays" min="1" class="config-input plan-duration-input" />
        </div>
        <small class="readonly-tip">月卡（小月卡 ¥30/3000、大月卡 ¥68/8000、MEGA ¥648/100000）由后端硬编码，未纳入本表单。</small>
      </div>

      <div class="rule-actions">
        <button class="btn-action promote rule-save-btn" :disabled="ruleSaving" @click="saveRule">
          {{ ruleSaving ? '保存中...' : '保存并立即生效' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';

export default {
  name: 'AdminCreditsRule',
  components: { Icon },
  setup() {
    const adminRule = inject('adminCreditsRule');
    return {
      ruleLoading: adminRule.ruleLoading,
      ruleSaving: adminRule.ruleSaving,
      ruleForm: adminRule.form,
      planMeta: adminRule.planMeta,
      loadRule: adminRule.loadRule,
      saveRule: adminRule.saveRule,
    };
  },
};
</script>

<style scoped>
.credit-rule-wrap { display: flex; flex-direction: column; gap: 16px; }
.rule-form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; margin-top: 12px; }
.rule-item { display: flex; flex-direction: column; gap: 6px; }
.rule-item label { font-size: 13px; font-weight: 500; color: var(--text-primary, #333); }
.rule-item small { font-size: 11px; color: var(--text-muted, #999); }
.rule-item.toggle-item { flex-direction: row; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; background: var(--bg-tertiary, #fafbfc); border-radius: 6px; }
.rule-item.toggle-item label:first-child { flex: 1; }
.rule-switch { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; font-size: 12px; color: var(--text-secondary, #666); }
.rule-switch input[type='checkbox'] { width: 16px; height: 16px; cursor: pointer; }
.plans-edit-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; margin-top: 12px; }
.plan-edit-card { padding: 14px; background: var(--bg-tertiary, #fafbfc); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; display: flex; flex-direction: column; gap: 10px; }
.plan-edit-name { font-size: 14px; font-weight: 600; color: var(--text-primary, #333); }
.plan-edit-row { display: flex; flex-direction: column; gap: 4px; }
.plan-edit-row label { font-size: 12px; color: var(--text-secondary, #666); }
.plan-duration-row { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
.plan-duration-row label { font-size: 13px; font-weight: 500; color: var(--text-primary, #333); }
.plan-duration-input { width: 120px; }
.rule-actions { display: flex; justify-content: flex-end; padding-top: 8px; }
.rule-save-btn { min-width: 160px; }
.readonly-tip { display: block; margin-top: 10px; color: var(--text-muted, #999); font-size: 12px; }
.json-textarea { font-family: monospace; font-size: 12px; resize: vertical; min-height: 60px; }
.grid-span-2 { grid-column: span 2; }
@media (max-width: 600px) {
  .grid-span-2 { grid-column: span 1; }
}
</style>