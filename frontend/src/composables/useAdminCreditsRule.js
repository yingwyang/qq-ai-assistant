import { ref, reactive } from 'vue';
import { adminCreditsApi } from '../services/api';

// 规则配置 Tab：新人奖励 / 签到 / AI 费率 / 套餐 4 档
// 后端 CreditRule 字段：newUserBonus, signInPoints, tokenUnit, promptRate,
// completionRate, minCost, defaultCostPerMsg, adminFree,
// planLitePrice/planLiteCredit, planProPrice/planProCredit,
// planProPlusPrice/planProPlusCredit, planUltraPrice/planUltraCredit, planDurationDays
// price 在后端以 BigDecimal 元为单位存储，UI 编辑亦用元
export function useAdminCreditsRule({ showSystemMsg } = {}) {
  const ruleLoading = ref(false);
  const ruleSaving = ref(false);

  // 表单模型：直接对应后端字段名，便于 PUT 时整体提交
  const form = reactive({
    newUserBonus: 500,
    signInPoints: 150,
    tokenUnit: 1000,
    promptRate: 2,
    completionRate: 4,
    minCost: 5,
    defaultCostPerMsg: 10,
    adminFree: true,
    allowOverdraft: false, // 后端实体暂未持久化，仅 UI 展示
    planLitePrice: 9.9,
    planLiteCredit: 2000,
    planProPrice: 59,
    planProCredit: 4000,
    planProPlusPrice: 219,
    planProPlusCredit: 12000,
    planUltraPrice: 629,
    planUltraCredit: 40000,
    planDurationDays: 30,
  });

  // 4 档套餐展示元数据（planName 不存后端，仅前端固定）
  const planMeta = [
    { key: 'Lite', name: '轻享版 Lite', priceField: 'planLitePrice', creditField: 'planLiteCredit' },
    { key: 'Pro', name: '专业版 Pro', priceField: 'planProPrice', creditField: 'planProCredit' },
    { key: 'ProPlus', name: '旗舰版 ProPlus', priceField: 'planProPlusPrice', creditField: 'planProPlusCredit' },
    { key: 'Ultra', name: '至尊版 Ultra', priceField: 'planUltraPrice', creditField: 'planUltraCredit' },
  ];

  // 将后端返回填充到表单
  function applyRule(rule) {
    if (!rule) return;
    const numKeys = [
      'newUserBonus', 'signInPoints', 'tokenUnit', 'promptRate', 'completionRate',
      'minCost', 'defaultCostPerMsg', 'planLiteCredit', 'planProCredit',
      'planProPlusCredit', 'planUltraCredit', 'planDurationDays',
    ];
    numKeys.forEach((k) => {
      if (rule[k] !== null && rule[k] !== undefined) form[k] = rule[k];
    });
    const priceKeys = [
      'planLitePrice', 'planProPrice', 'planProPlusPrice', 'planUltraPrice',
    ];
    priceKeys.forEach((k) => {
      if (rule[k] !== null && rule[k] !== undefined) form[k] = Number(rule[k]);
    });
    if (rule.adminFree !== null && rule.adminFree !== undefined) form.adminFree = rule.adminFree;
    // allowOverdraft 后端无字段，保留默认
  }

  // 数字校验：负数拦截、空值拦截
  function validate() {
    const intChecks = [
      ['newUserBonus', '新人奖励'],
      ['signInPoints', '签到积分'],
      ['tokenUnit', 'tokenUnit'],
      ['promptRate', 'promptRate'],
      ['completionRate', 'completionRate'],
      ['minCost', 'minCost'],
      ['defaultCostPerMsg', 'defaultCostPerMsg'],
      ['planLiteCredit', 'Lite 积分'],
      ['planProCredit', 'Pro 积分'],
      ['planProPlusCredit', 'ProPlus 积分'],
      ['planUltraCredit', 'Ultra 积分'],
      ['planDurationDays', '套餐时长'],
    ];
    for (const [k, label] of intChecks) {
      const v = form[k];
      if (v === '' || v === null || v === undefined || Number.isNaN(Number(v))) {
        return `${label} 不能为空`;
      }
      if (Number(v) < 0) return `${label} 不能为负`;
    }
    // tokenUnit 与 planDurationDays 必须大于 0
    if (Number(form.tokenUnit) <= 0) return 'tokenUnit 必须大于 0';
    if (Number(form.planDurationDays) <= 0) return '套餐时长必须大于 0';
    // 价格校验
    const priceChecks = [
      ['planLitePrice', 'Lite 价格'],
      ['planProPrice', 'Pro 价格'],
      ['planProPlusPrice', 'ProPlus 价格'],
      ['planUltraPrice', 'Ultra 价格'],
    ];
    for (const [k, label] of priceChecks) {
      const v = form[k];
      if (v === '' || v === null || v === undefined || Number.isNaN(Number(v))) {
        return `${label} 不能为空`;
      }
      if (Number(v) < 0) return `${label} 不能为负`;
    }
    return null;
  }

  async function loadRule() {
    ruleLoading.value = true;
    try {
      const rule = await adminCreditsApi.getRule();
      applyRule(rule);
    } catch (error) {
      if (showSystemMsg) showSystemMsg('加载积分规则失败: ' + error.message, 'error');
    } finally {
      ruleLoading.value = false;
    }
  }

  async function saveRule() {
    const err = validate();
    if (err) {
      if (showSystemMsg) showSystemMsg(err, 'error');
      return false;
    }
    ruleSaving.value = true;
    try {
      // 仅提交后端支持的合法字段
      const payload = {
        newUserBonus: Number(form.newUserBonus),
        signInPoints: Number(form.signInPoints),
        tokenUnit: Number(form.tokenUnit),
        promptRate: Number(form.promptRate),
        completionRate: Number(form.completionRate),
        minCost: Number(form.minCost),
        defaultCostPerMsg: Number(form.defaultCostPerMsg),
        adminFree: !!form.adminFree,
        planLitePrice: Number(form.planLitePrice),
        planLiteCredit: Number(form.planLiteCredit),
        planProPrice: Number(form.planProPrice),
        planProCredit: Number(form.planProCredit),
        planProPlusPrice: Number(form.planProPlusPrice),
        planProPlusCredit: Number(form.planProPlusCredit),
        planUltraPrice: Number(form.planUltraPrice),
        planUltraCredit: Number(form.planUltraCredit),
        planDurationDays: Number(form.planDurationDays),
      };
      const saved = await adminCreditsApi.updateRule(payload);
      applyRule(saved);
      if (showSystemMsg) showSystemMsg('已保存，立即生效');
      return true;
    } catch (error) {
      if (showSystemMsg) showSystemMsg('保存失败: ' + error.message, 'error');
      return false;
    } finally {
      ruleSaving.value = false;
    }
  }

  return {
    ruleLoading, ruleSaving, form, planMeta,
    loadRule, saveRule,
  };
}
