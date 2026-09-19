import { ref, reactive, computed } from 'vue';
import { adminCreditsApi } from '../services/api';

/**
 * 积分规则配置：结构化表单 + 脏检查 + 内联校验 + 费用试算。
 *
 * 后端 CreditRule 里三个字段是 JSON 字符串（modelRates / analyzeTypeRates /
 * tieredDiscountThresholds）。直接让管理员编辑 JSON 太容易写坏，这里改成可增删的行编辑器，
 * 提交前再序列化成 JSON 字符串，契约不变。
 */

/** AI 分析的 6 个维度（与 prompts.yml / 前端分析面板一致） */
export const ANALYZE_TYPE_META = [
  { key: 'summary', label: '群聊速览' },
  { key: 'social-graph', label: '社交图谱' },
  { key: 'topic-trend', label: '话题趋势' },
  { key: 'integration-guide', label: '融入指南' },
  { key: 'meme-dictionary', label: '梗词典' },
  { key: 'persona-match', label: '人设匹配' },
];

/** 表单默认值：与后端 CreditRuleService.buildDefaultRule() 对齐，仅作首屏占位 */
export const RULE_FORM_FALLBACK = {
  newUserBonus: 500,
  signInPoints: 150,
  tokenUnit: 1000,
  promptRate: 2,
  completionRate: 4,
  minCost: 5,
  defaultCostPerMsg: 10,
  adminFree: true,
  // 直购积分（客户端「直购积分·N」，代码里的 tier 名只作内部标识）
  planLitePrice: 9.9,
  planLiteCredit: 2000,
  planProPrice: 59,
  planProCredit: 4000,
  planProPlusPrice: 219,
  planProPlusCredit: 12000,
  planUltraPrice: 629,
  planUltraCredit: 40000,
  planMegaPrice: 648,
  planMegaCredit: 100000,
  // 会员月卡
  planSmallMonthCardPrice: 30,
  planSmallMonthCardCredit: 3000,
  planSmallMonthCardDailyBonus: 100,
  planLargeMonthCardPrice: 68,
  planLargeMonthCardCredit: 8000,
  planLargeMonthCardDailyBonus: 300,
  planDurationDays: 30,
  imageExtraCost: 5,
  analyzeBaseCost: 10,
  analyzeCostPerMsg: 1,
  ttsCharsPerCredit: 50,
  ttsMinCost: 2,
  monthlyFreeQuota: 0,
  overtaxRate: 1.5,
  smallMonthCardDiscount: 0.9,
  largeMonthCardDiscount: 0.8,
  allTierDiscount: 0.7,
  contextExtraCostPerMsg: 1,
  contextFreeMsgCount: 10,
  dailyCapCost: 0,
};

/** 数值字段元数据：标签 / 单位 / 说明 / 是否必须 >0 / 是否折扣（0.01~1） */
export const FIELD_META = {
  newUserBonus: { label: '新人奖励', unit: '积分', hint: '注册即送的积分', group: 'base' },
  signInPoints: { label: '每日签到积分', unit: '积分', hint: '签到时发放（月卡加成另外叠加）', group: 'base' },
  tokenUnit: { label: '计费粒度', unit: 'token', hint: '每多少 token 记 1 积分，越大越便宜', positive: true, group: 'ai' },
  promptRate: { label: '输入倍率', hint: '提示词 token 的权重', group: 'ai' },
  completionRate: { label: '输出倍率', hint: '生成 token 的权重（一般比输入贵）', group: 'ai' },
  minCost: { label: '单次最小消耗', unit: '积分', hint: '算出来不足这个数就按这个收', group: 'ai' },
  defaultCostPerMsg: { label: '未知用量时的单条费用', unit: '积分', hint: '拿不到 token 数时按它计费', group: 'ai' },
  modelRates: { label: '模型费率', hint: '按模型名给倍率，default 兜底', group: 'ai' },
  imageExtraCost: { label: '每张图片额外费用', unit: '积分', group: 'ai' },
  monthlyFreeQuota: { label: '每月免费次数', unit: '次', hint: '0 = 不免费；额度内不计费', group: 'ai' },
  overtaxRate: { label: '超配额倍率', hint: '超出免费次数后乘这个数（≥1.0）', step: 0.1, group: 'ai' },
  analyzeBaseCost: { label: '分析基础费用', unit: '积分', group: 'analyze' },
  analyzeCostPerMsg: { label: '分析每条消息增量', unit: '积分', hint: '基础费 + 条数 × 增量 × 类型倍率', group: 'analyze' },
  analyzeTypeRates: { label: '分析类型倍率', hint: '不同分析维度可以贵一点', group: 'analyze' },
  ttsCharsPerCredit: { label: '语音计费粒度', unit: '字符/积分', hint: '每 N 个字符扣 1 积分', positive: true, group: 'analyze' },
  ttsMinCost: { label: '语音最小消耗', unit: '积分', group: 'analyze' },
  smallMonthCardDiscount: { label: '小月卡折扣', discount: true, hint: '0.9 = 9 折；1.0 = 不打折', step: 0.01, group: 'discount' },
  largeMonthCardDiscount: { label: '大月卡折扣', discount: true, hint: '0.8 = 8 折', step: 0.01, group: 'discount' },
  allTierDiscount: { label: '双持（ALL）折扣', discount: true, hint: '大小月卡同时拥有时生效', step: 0.01, group: 'discount' },
  tieredDiscountThresholds: { label: '阶梯累计折扣', hint: '本月累计消耗达到阈值后打折', group: 'discount' },
  contextFreeMsgCount: { label: '上下文免费条数', unit: '条', hint: '前 N 条历史消息不计费', group: 'discount' },
  contextExtraCostPerMsg: { label: '上下文每条增量', unit: '积分', hint: '超过免费条数后每条 +N', group: 'discount' },
  dailyCapCost: { label: '每日封顶消耗', unit: '积分', hint: '单日累计达到后本次免费；0 = 不限', group: 'discount' },
  planDurationDays: { label: '套餐时长', unit: '天', hint: '直购积分与月卡共享', positive: true, group: 'plan' },
  // ====== 直购积分（客户端「直购积分·N」）======
  planLitePrice: { label: '直购积分·2000 价格', unit: '元', group: 'plan' },
  planLiteCredit: { label: '直购积分·2000 赠送积分', unit: '积分', group: 'plan' },
  planProPrice: { label: '直购积分·4000 价格', unit: '元', group: 'plan' },
  planProCredit: { label: '直购积分·4000 赠送积分', unit: '积分', group: 'plan' },
  planProPlusPrice: { label: '直购积分·12000 价格', unit: '元', group: 'plan' },
  planProPlusCredit: { label: '直购积分·12000 赠送积分', unit: '积分', group: 'plan' },
  planUltraPrice: { label: '直购积分·40000 价格', unit: '元', group: 'plan' },
  planUltraCredit: { label: '直购积分·40000 赠送积分', unit: '积分', group: 'plan' },
  planMegaPrice: { label: '直购积分·100000 价格', unit: '元', group: 'plan' },
  planMegaCredit: { label: '直购积分·100000 赠送积分', unit: '积分', group: 'plan' },
  // ====== 会员月卡 ======
  planSmallMonthCardPrice: { label: '小月卡价格', unit: '元', group: 'card' },
  planSmallMonthCardCredit: { label: '小月卡赠送积分', unit: '积分', group: 'card' },
  planSmallMonthCardDailyBonus: { label: '小月卡每日签到加成', unit: '积分/天', group: 'card' },
  planLargeMonthCardPrice: { label: '大月卡价格', unit: '元', group: 'card' },
  planLargeMonthCardCredit: { label: '大月卡赠送积分', unit: '积分', group: 'card' },
  planLargeMonthCardDailyBonus: { label: '大月卡每日签到加成', unit: '积分/天', group: 'card' },
};

const INT_FIELDS = [
  'newUserBonus', 'signInPoints', 'planLiteCredit', 'planProCredit',
  'planProPlusCredit', 'planUltraCredit',
];

/** 把 {"key":rate} 解析成行数组 */
export function parseRateJson(json, fallbackDefault = 1.0) {
  const rows = [];
  let parsed = null;
  try {
    parsed = typeof json === 'string' ? JSON.parse(json) : json;
  } catch (e) {
    parsed = null;
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    Object.keys(parsed).forEach((k) => {
      rows.push({ key: k, rate: Number(parsed[k]) });
    });
  }
  if (!rows.length) rows.push({ key: 'default', rate: fallbackDefault });
  return rows;
}

/** 行数组 → JSON 字符串（去掉空 key，重复 key 保留最后一个） */
export function rateRowsToJson(rows) {
  const obj = {};
  (rows || []).forEach((r) => {
    const key = String(r.key ?? '').trim();
    if (!key) return;
    obj[key] = Number(r.rate ?? 0);
  });
  if (!Object.keys(obj).length) obj.default = 1.0;
  return JSON.stringify(obj);
}

/** 阶梯折扣：{"1000":0.95} → 排序后的行数组 */
export function parseThresholdJson(json) {
  const rows = [];
  let parsed = null;
  try {
    parsed = typeof json === 'string' ? JSON.parse(json) : json;
  } catch (e) {
    parsed = null;
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    Object.keys(parsed).forEach((k) => {
      rows.push({ threshold: Number(k), discount: Number(parsed[k]) });
    });
  }
  return rows.sort((a, b) => a.threshold - b.threshold);
}

export function thresholdRowsToJson(rows) {
  const list = (rows || []).filter((r) => Number(r.threshold) > 0);
  list.sort((a, b) => Number(a.threshold) - Number(b.threshold));
  const obj = {};
  list.forEach((r) => { obj[String(Number(r.threshold))] = Number(r.discount); });
  return JSON.stringify(obj);
}

export function useAdminCreditsRule({ showSystemMsg } = {}) {
  const ruleLoading = ref(false);
  const ruleSaving = ref(false);
  const defaultsLoading = ref(false);
  const loaded = ref(false);

  const form = reactive({ ...RULE_FORM_FALLBACK });
  // 结构化子表单（JSON 字段的行编辑形态）
  const modelRateRows = ref([{ key: 'default', rate: 1.0 }]);
  const analyzeRateRows = ref([{ key: 'default', rate: 1.0 }]);
  const thresholdRows = ref([]);
  /** 上一次保存/加载的快照，用于脏检查 */
  const snapshot = ref('');

  const planMeta = [
    { key: 'LITE', name: '直购积分·2000', tier: 'LITE', priceField: 'planLitePrice', creditField: 'planLiteCredit', color: '#4caf50' },
    { key: 'PRO', name: '直购积分·4000', tier: 'PRO', priceField: 'planProPrice', creditField: 'planProCredit', color: '#2196f3' },
    { key: 'PROPLUS', name: '直购积分·12000', tier: 'PROPLUS', priceField: 'planProPlusPrice', creditField: 'planProPlusCredit', color: '#9c27b0' },
    { key: 'ULTRA', name: '直购积分·40000', tier: 'ULTRA', priceField: 'planUltraPrice', creditField: 'planUltraCredit', color: '#ff9800' },
    { key: 'MEGA', name: '直购积分·100000', tier: 'MEGA', priceField: 'planMegaPrice', creditField: 'planMegaCredit', color: '#e91e63' },
  ];

  /** 会员月卡：价格 / 赠送积分 / 每日签到加成（双持 = 两张卡相加） */
  const cardMeta = [
    {
      key: 'SMALL_MONTH_CARD', name: '小月卡', tier: 'SMALL_MONTH_CARD', color: '#ffb300',
      priceField: 'planSmallMonthCardPrice', creditField: 'planSmallMonthCardCredit',
      bonusField: 'planSmallMonthCardDailyBonus', discountField: 'smallMonthCardDiscount',
    },
    {
      key: 'LARGE_MONTH_CARD', name: '大月卡', tier: 'LARGE_MONTH_CARD', color: '#fb8c00',
      priceField: 'planLargeMonthCardPrice', creditField: 'planLargeMonthCardCredit',
      bonusField: 'planLargeMonthCardDailyBonus', discountField: 'largeMonthCardDiscount',
    },
  ];

  /** 双持（小月卡 + 大月卡同时有效）的每日加成与折扣 */
  const comboSummary = computed(() => ({
    dailyBonus: (Number(form.planSmallMonthCardDailyBonus) || 0) + (Number(form.planLargeMonthCardDailyBonus) || 0),
    price: (Number(form.planSmallMonthCardPrice) || 0) + (Number(form.planLargeMonthCardPrice) || 0),
    credits: (Number(form.planSmallMonthCardCredit) || 0) + (Number(form.planLargeMonthCardCredit) || 0),
    discount: form.allTierDiscount,
  }));

  /** 当前表单的可比较快照（含结构化子表单） */
  function serialize() {
    return JSON.stringify({
      ...form,
      modelRateRows: modelRateRows.value,
      analyzeRateRows: analyzeRateRows.value,
      thresholdRows: thresholdRows.value,
    });
  }

  const isDirty = computed(() => loaded.value && serialize() !== snapshot.value);

  function applyRule(rule) {
    if (!rule) return;
    Object.keys(RULE_FORM_FALLBACK).forEach((k) => {
      if (rule[k] !== null && rule[k] !== undefined) form[k] = rule[k];
    });
    ['tokenUnit', 'promptRate', 'completionRate', 'minCost', 'defaultCostPerMsg',
      'imageExtraCost', 'analyzeBaseCost', 'analyzeCostPerMsg', 'ttsCharsPerCredit',
      'ttsMinCost', 'monthlyFreeQuota', 'contextExtraCostPerMsg', 'contextFreeMsgCount',
      'dailyCapCost', 'planDurationDays', 'planLiteCredit', 'planProCredit',
      'planProPlusCredit', 'planUltraCredit', 'planMegaCredit',
      'planSmallMonthCardCredit', 'planSmallMonthCardDailyBonus',
      'planLargeMonthCardCredit', 'planLargeMonthCardDailyBonus',
      'newUserBonus', 'signInPoints'].forEach((k) => {
      if (rule[k] !== null && rule[k] !== undefined) form[k] = Number(rule[k]);
    });
    ['planLitePrice', 'planProPrice', 'planProPlusPrice', 'planUltraPrice', 'planMegaPrice',
      'planSmallMonthCardPrice', 'planLargeMonthCardPrice'].forEach((k) => {
      if (rule[k] !== null && rule[k] !== undefined) form[k] = Number(rule[k]);
    });
    ['overtaxRate', 'smallMonthCardDiscount', 'largeMonthCardDiscount', 'allTierDiscount'].forEach((k) => {
      if (rule[k] !== null && rule[k] !== undefined) form[k] = Number(rule[k]);
    });
    modelRateRows.value = parseRateJson(rule.modelRates);
    analyzeRateRows.value = parseRateJson(rule.analyzeTypeRates);
    thresholdRows.value = parseThresholdJson(rule.tieredDiscountThresholds);
    snapshot.value = serialize();
    loaded.value = true;
  }

  // ==================== 校验 ====================
  /** 字段级错误：{ 字段名: 提示 } —— 前端先拦一道，后端 setRule 还会再校验一遍 */
  const errors = computed(() => {
    const e = {};
    Object.entries(FIELD_META).forEach(([key, meta]) => {
      if (!(key in form)) return;
      const raw = form[key];
      if (raw === '' || raw === null || raw === undefined || Number.isNaN(Number(raw))) {
        e[key] = `${meta.label}不能为空`;
        return;
      }
      const v = Number(raw);
      if (meta.positive && v <= 0) e[key] = `${meta.label}必须大于 0`;
      else if (v < 0) e[key] = `${meta.label}不能为负`;
      if (meta.discount && (v < 0.01 || v > 1.0)) e[key] = `${meta.label}需在 0.01~1.0 之间（1.0 = 不打折）`;
      if (key === 'overtaxRate' && v < 1.0) e[key] = '超配额倍率不能小于 1.0';
    });
    ['planLitePrice', 'planProPrice', 'planProPlusPrice', 'planUltraPrice', 'planMegaPrice',
      'planSmallMonthCardPrice', 'planLargeMonthCardPrice'].forEach((k) => {
      if (Number(form[k]) < 0) e[k] = '价格不能为负';
    });
    INT_FIELDS.forEach((k) => {
      if (form[k] !== '' && !Number.isInteger(Number(form[k]))) e[k] = '必须是整数';
    });
    // 结构化子表单
    const modelDup = duplicateKeys(modelRateRows.value);
    if (modelDup) e.modelRates = `模型名重复：${modelDup}`;
    if (modelRateRows.value.some((r) => String(r.key).trim() && Number(r.rate) < 0)) {
      e.modelRates = '倍率不能为负';
    }
    if (!modelRateRows.value.some((r) => String(r.key).trim() === 'default')) {
      e.modelRates = '建议保留一条 default（未列出的模型按它计费）';
    }
    const analyzeDup = duplicateKeys(analyzeRateRows.value);
    if (analyzeDup) e.analyzeRates = `分析类型重复：${analyzeDup}`;
    if (analyzeRateRows.value.some((r) => String(r.key).trim() && Number(r.rate) < 0)) {
      e.analyzeRates = '倍率不能为负';
    }
    thresholdRows.value.forEach((r, i) => {
      if (!(Number(r.threshold) > 0)) e.thresholds = `第 ${i + 1} 行阈值必须是正整数`;
      else if (Number(r.discount) < 0.01 || Number(r.discount) > 1.0) {
        e.thresholds = `第 ${i + 1} 行折扣需在 0.01~1.0`;
      }
    });
    return e;
  });

  function duplicateKeys(rows) {
    const seen = new Set();
    for (const r of rows || []) {
      const key = String(r.key ?? '').trim();
      if (!key) continue;
      if (seen.has(key)) return key;
      seen.add(key);
    }
    return null;
  }

  const errorList = computed(() => Object.values(errors.value));
  const isValid = computed(() => errorList.value.length === 0);

  // ==================== 行编辑 ====================
  const addModelRate = () => modelRateRows.value.push({ key: '', rate: 1.0 });
  const removeModelRate = (i) => {
    modelRateRows.value.splice(i, 1);
    if (!modelRateRows.value.length) modelRateRows.value.push({ key: 'default', rate: 1.0 });
  };
  const addAnalyzeRate = () => analyzeRateRows.value.push({ key: '', rate: 1.0 });
  const removeAnalyzeRate = (i) => {
    analyzeRateRows.value.splice(i, 1);
    if (!analyzeRateRows.value.length) analyzeRateRows.value.push({ key: 'default', rate: 1.0 });
  };
  const addThreshold = () => thresholdRows.value.push({ threshold: 1000, discount: 0.95 });
  const removeThreshold = (i) => thresholdRows.value.splice(i, 1);

  // ==================== 加载 / 保存 ====================
  async function loadRule() {
    if (ruleLoading.value) return;   // 后台切 tab 与页面 onMounted 会同时触发，避免重复请求
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

  /** 用后端出厂默认值填充表单（不保存，需再点保存） */
  async function loadDefaults() {
    defaultsLoading.value = true;
    try {
      const defaults = await adminCreditsApi.getRuleDefaults();
      applyRule(defaults);
      if (showSystemMsg) showSystemMsg('已填入出厂默认值，确认后点「保存并立即生效」');
    } catch (error) {
      if (showSystemMsg) showSystemMsg('读取默认值失败: ' + error.message, 'error');
    } finally {
      defaultsLoading.value = false;
    }
  }

  /** 放弃未保存的修改：回到上次加载/保存的状态 */
  function discardChanges() {
    if (!snapshot.value) return;
    applyRule(JSON.parse(snapshot.value));
    if (showSystemMsg) showSystemMsg('已放弃未保存的修改');
  }

  async function saveRule() {
    if (!isValid.value) {
      if (showSystemMsg) showSystemMsg(errorList.value[0], 'error');
      return false;
    }
    ruleSaving.value = true;
    try {
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
        planMegaPrice: Number(form.planMegaPrice),
        planMegaCredit: Number(form.planMegaCredit),
        planSmallMonthCardPrice: Number(form.planSmallMonthCardPrice),
        planSmallMonthCardCredit: Number(form.planSmallMonthCardCredit),
        planSmallMonthCardDailyBonus: Number(form.planSmallMonthCardDailyBonus),
        planLargeMonthCardPrice: Number(form.planLargeMonthCardPrice),
        planLargeMonthCardCredit: Number(form.planLargeMonthCardCredit),
        planLargeMonthCardDailyBonus: Number(form.planLargeMonthCardDailyBonus),
        planDurationDays: Number(form.planDurationDays),
        modelRates: rateRowsToJson(modelRateRows.value),
        imageExtraCost: Number(form.imageExtraCost),
        analyzeBaseCost: Number(form.analyzeBaseCost),
        analyzeCostPerMsg: Number(form.analyzeCostPerMsg),
        analyzeTypeRates: rateRowsToJson(analyzeRateRows.value),
        ttsCharsPerCredit: Number(form.ttsCharsPerCredit),
        ttsMinCost: Number(form.ttsMinCost),
        monthlyFreeQuota: Number(form.monthlyFreeQuota),
        overtaxRate: Number(form.overtaxRate),
        smallMonthCardDiscount: Number(form.smallMonthCardDiscount),
        largeMonthCardDiscount: Number(form.largeMonthCardDiscount),
        allTierDiscount: Number(form.allTierDiscount),
        contextExtraCostPerMsg: Number(form.contextExtraCostPerMsg),
        contextFreeMsgCount: Number(form.contextFreeMsgCount),
        dailyCapCost: Number(form.dailyCapCost),
        tieredDiscountThresholds: thresholdRowsToJson(thresholdRows.value),
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

  // ==================== 费用试算（与后端 CreditService 公式对齐） ====================
  const preview = reactive({
    promptTokens: 800,
    completionTokens: 400,
    imageCount: 0,
    contextMsgs: 12,
    model: 'default',
    analyzeMsgs: 20,
    analyzeType: 'summary',
    ttsChars: 60,
    tier: 'FREE',
    monthlySpent: 0,
    inFreeQuota: false,
  });

  const tierOptions = [
    { value: 'FREE', label: '免费用户（无折扣）' },
    { value: 'SMALL_MONTH_CARD', label: '小月卡' },
    { value: 'LARGE_MONTH_CARD', label: '大月卡' },
    { value: 'ALL', label: '双持（ALL）' },
  ];

  function rateOf(rows, key) {
    const found = (rows || []).find((r) => String(r.key).trim() === key);
    if (found) return Number(found.rate) || 0;
    const def = (rows || []).find((r) => String(r.key).trim() === 'default');
    return def ? Number(def.rate) || 0 : 1.0;
  }

  function tierDiscountOf(tier) {
    if (tier === 'SMALL_MONTH_CARD') return Number(form.smallMonthCardDiscount) || 1;
    if (tier === 'LARGE_MONTH_CARD') return Number(form.largeMonthCardDiscount) || 1;
    if (tier === 'ALL') return Number(form.allTierDiscount) || 1;
    return 1;
  }

  function tieredDiscountOf(spent) {
    let best = 1;
    thresholdRows.value.forEach((r) => {
      if (Number(spent) >= Number(r.threshold) && Number(r.discount) < best) best = Number(r.discount);
    });
    return best;
  }

  /** 聊天一次的费用明细（与 CreditService.estimateChatCost 同公式） */
  const chatPreview = computed(() => {
    const p = Number(preview.promptTokens) || 0;
    const c = Number(preview.completionTokens) || 0;
    const tokenUnit = Number(form.tokenUnit) || 1000;
    let base;
    if (p === 0 && c === 0) {
      base = Number(form.defaultCostPerMsg) || 0;
    } else {
      const raw = Math.ceil(((p * (Number(form.promptRate) || 0)) + (c * (Number(form.completionRate) || 0))) / tokenUnit);
      base = Math.max(raw, Number(form.minCost) || 0);
    }
    const imageExtra = (Number(preview.imageCount) || 0) * (Number(form.imageExtraCost) || 0);
    const billableMsg = Math.max(0, (Number(preview.contextMsgs) || 0) - (Number(form.contextFreeMsgCount) || 0));
    const contextExtra = billableMsg * (Number(form.contextExtraCostPerMsg) || 0);
    const rawCost = base + imageExtra + contextExtra;
    const modelRate = rateOf(modelRateRows.value, String(preview.model || 'default').trim());
    const overtax = Number(form.overtaxRate) || 1;
    const tierDiscount = tierDiscountOf(preview.tier);
    const tiered = tieredDiscountOf(preview.monthlySpent);
    const quotaFree = preview.inFreeQuota && Number(form.monthlyFreeQuota) > 0;
    const cost = quotaFree ? 0 : Math.max(1, Math.ceil(rawCost * modelRate * overtax * tierDiscount * tiered));
    return {
      base, imageExtra, contextExtra, rawCost, modelRate, overtax,
      tierDiscount, tiered, cost, quotaFree, billableMsg,
    };
  });

  /** 一次群分析的费用（与 CreditService.spendForAnalyze 同公式） */
  const analyzePreview = computed(() => {
    const msgs = Number(preview.analyzeMsgs) || 0;
    const typeRate = rateOf(analyzeRateRows.value, String(preview.analyzeType || 'default').trim());
    const rawCost = (Number(form.analyzeBaseCost) || 0) + Math.max(0, msgs) * (Number(form.analyzeCostPerMsg) || 0);
    const tierDiscount = tierDiscountOf(preview.tier);
    const tiered = tieredDiscountOf(preview.monthlySpent);
    return {
      rawCost, typeRate, tierDiscount, tiered,
      cost: Math.max(1, Math.ceil(rawCost * typeRate * tierDiscount * tiered)),
    };
  });

  /** 一次语音合成的费用（与 CreditService.spendForTts 同公式） */
  const ttsPreview = computed(() => {
    const chars = Number(preview.ttsChars) || 0;
    const per = Number(form.ttsCharsPerCredit) || 50;
    const tierDiscount = tierDiscountOf(preview.tier);
    const tiered = tieredDiscountOf(preview.monthlySpent);
    const raw = Math.max(Number(form.ttsMinCost) || 0, Math.ceil(chars / per) * tierDiscount * tiered);
    return { cost: Math.max(1, Math.ceil(raw)), tierDiscount, tiered };
  });

  return {
    ruleLoading, ruleSaving, defaultsLoading, loaded,
    form, planMeta, cardMeta, comboSummary, errors, errorList, isValid, isDirty,
    modelRateRows, analyzeRateRows, thresholdRows,
    addModelRate, removeModelRate, addAnalyzeRate, removeAnalyzeRate,
    addThreshold, removeThreshold,
    loadRule, loadDefaults, discardChanges, saveRule,
    preview, tierOptions, chatPreview, analyzePreview, ttsPreview,
  };
}
