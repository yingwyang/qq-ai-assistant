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
.rule-item small { font-size: 11px; color: #999; }
.rule-item.toggle-item { flex-direction: row; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; background: var(--bg-tertiary, #fafbfc); border-radius: 6px; }
.rule-item.toggle-item label:first-child { flex: 1; }
.rule-switch { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; font-size: 12px; color: #666; }
.rule-switch input[type='checkbox'] { width: 16px; height: 16px; cursor: pointer; }
.plans-edit-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; margin-top: 12px; }
.plan-edit-card { padding: 14px; background: var(--bg-tertiary, #fafbfc); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; display: flex; flex-direction: column; gap: 10px; }
.plan-edit-name { font-size: 14px; font-weight: 600; color: var(--text-primary, #333); }
.plan-edit-row { display: flex; flex-direction: column; gap: 4px; }
.plan-edit-row label { font-size: 12px; color: #666; }
.plan-duration-row { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
.plan-duration-row label { font-size: 13px; font-weight: 500; color: var(--text-primary, #333); }
.plan-duration-input { width: 120px; }
.rule-actions { display: flex; justify-content: flex-end; padding-top: 8px; }
.rule-save-btn { min-width: 160px; }
.readonly-tip { display: block; margin-top: 10px; color: #999; font-size: 12px; }
</style>