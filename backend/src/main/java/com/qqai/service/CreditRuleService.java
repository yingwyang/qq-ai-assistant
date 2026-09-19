package com.qqai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.qqai.entity.CreditRule;
import com.qqai.exception.BizException;
import com.qqai.repository.CreditRuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CreditRuleService {

    private static final Logger log = LoggerFactory.getLogger(CreditRuleService.class);

    private static final Long RULE_ID = 1L;

    @Autowired
    private CreditRuleRepository creditRuleRepository;

    private final AtomicReference<CreditRule> cachedRule = new AtomicReference<>();

    @PostConstruct
    public void initDefaultRule() {
        try {
            CreditRule rule = createDefaultRuleIfAbsent();
            cachedRule.set(rule);
            log.info("积分规则已初始化/加载 id={}", RULE_ID);
        } catch (Exception e) {
            log.error("积分规则初始化失败，将在首次访问时重试", e);
        }
    }

    /**
     * 获取当前积分规则，永不返回 null。
     * 优先读缓存；若缓存未命中则尝试从数据库加载/创建默认规则；
     * 若数据库不可用（表不存在、连接失败等）则返回内存中硬编码的默认规则兜底，避免上层 NPE 或 500。
     */
    public CreditRule getRule() {
        CreditRule rule = cachedRule.get();
        if (rule != null) {
            return rule;
        }
        try {
            rule = createDefaultRuleIfAbsent();
            cachedRule.set(rule);
            return rule;
        } catch (Exception e) {
            log.error("数据库积分规则加载失败，使用硬编码默认规则兜底", e);
            return buildDefaultRule();
        }
    }

    /**
     * getRule() 的别名，语义上强调“安全兜底”，同样永不返回 null。
     */
    public CreditRule getSafeRule() {
        return getRule();
    }

    @Transactional
    public CreditRule setRule(CreditRule updates) {
        validate(updates);
        CreditRule existing = creditRuleRepository.findById(RULE_ID)
                .orElseGet(this::buildDefaultRule);
        if (updates.getNewUserBonus() != null) existing.setNewUserBonus(updates.getNewUserBonus());
        if (updates.getSignInPoints() != null) existing.setSignInPoints(updates.getSignInPoints());
        if (updates.getTokenUnit() != null) existing.setTokenUnit(updates.getTokenUnit());
        if (updates.getPromptRate() != null) existing.setPromptRate(updates.getPromptRate());
        if (updates.getCompletionRate() != null) existing.setCompletionRate(updates.getCompletionRate());
        if (updates.getMinCost() != null) existing.setMinCost(updates.getMinCost());
        if (updates.getDefaultCostPerMsg() != null) existing.setDefaultCostPerMsg(updates.getDefaultCostPerMsg());
        if (updates.getAdminFree() != null) existing.setAdminFree(updates.getAdminFree());
        if (updates.getPlanLitePrice() != null) existing.setPlanLitePrice(updates.getPlanLitePrice());
        if (updates.getPlanLiteCredit() != null) existing.setPlanLiteCredit(updates.getPlanLiteCredit());
        if (updates.getPlanProPrice() != null) existing.setPlanProPrice(updates.getPlanProPrice());
        if (updates.getPlanProCredit() != null) existing.setPlanProCredit(updates.getPlanProCredit());
        if (updates.getPlanProPlusPrice() != null) existing.setPlanProPlusPrice(updates.getPlanProPlusPrice());
        if (updates.getPlanProPlusCredit() != null) existing.setPlanProPlusCredit(updates.getPlanProPlusCredit());
        if (updates.getPlanUltraPrice() != null) existing.setPlanUltraPrice(updates.getPlanUltraPrice());
        if (updates.getPlanUltraCredit() != null) existing.setPlanUltraCredit(updates.getPlanUltraCredit());
        if (updates.getPlanDurationDays() != null) existing.setPlanDurationDays(updates.getPlanDurationDays());
        // 精细化计费字段
        if (updates.getModelRates() != null) existing.setModelRates(updates.getModelRates());
        if (updates.getImageExtraCost() != null) existing.setImageExtraCost(updates.getImageExtraCost());
        if (updates.getAnalyzeBaseCost() != null) existing.setAnalyzeBaseCost(updates.getAnalyzeBaseCost());
        if (updates.getAnalyzeCostPerMsg() != null) existing.setAnalyzeCostPerMsg(updates.getAnalyzeCostPerMsg());
        if (updates.getAnalyzeTypeRates() != null) existing.setAnalyzeTypeRates(updates.getAnalyzeTypeRates());
        if (updates.getTtsCharsPerCredit() != null) existing.setTtsCharsPerCredit(updates.getTtsCharsPerCredit());
        if (updates.getTtsMinCost() != null) existing.setTtsMinCost(updates.getTtsMinCost());
        if (updates.getMonthlyFreeQuota() != null) existing.setMonthlyFreeQuota(updates.getMonthlyFreeQuota());
        if (updates.getOvertaxRate() != null) existing.setOvertaxRate(updates.getOvertaxRate());
        // 精细化扩展字段
        if (updates.getSmallMonthCardDiscount() != null) existing.setSmallMonthCardDiscount(updates.getSmallMonthCardDiscount());
        if (updates.getLargeMonthCardDiscount() != null) existing.setLargeMonthCardDiscount(updates.getLargeMonthCardDiscount());
        if (updates.getAllTierDiscount() != null) existing.setAllTierDiscount(updates.getAllTierDiscount());
        if (updates.getContextExtraCostPerMsg() != null) existing.setContextExtraCostPerMsg(updates.getContextExtraCostPerMsg());
        if (updates.getContextFreeMsgCount() != null) existing.setContextFreeMsgCount(updates.getContextFreeMsgCount());
        if (updates.getDailyCapCost() != null) existing.setDailyCapCost(updates.getDailyCapCost());
        if (updates.getTieredDiscountThresholds() != null) existing.setTieredDiscountThresholds(updates.getTieredDiscountThresholds());
        CreditRule saved = creditRuleRepository.save(existing);
        cachedRule.set(saved);
        log.info("积分规则已更新 id={}", RULE_ID);
        return saved;
    }

    /** 出厂默认规则（后台「恢复默认值」用；不落库） */
    public CreditRule defaultRule() {
        return buildDefaultRule();
    }

    /**
     * 保存前校验：数值范围 + 三个 JSON 字段必须是合法对象且值在范围内。
     *
     * <p>校验放在 service 层（而不是只放 controller），任何调用方都绕不过去；
     * 之前 JSON 字段是纯字符串，管理员写错一个逗号就能把计费打穿。</p>
     */
    void validate(CreditRule u) {
        if (u == null) return;
        nonNegative(u.getNewUserBonus(), "新人奖励");
        nonNegative(u.getSignInPoints(), "每日签到积分");
        positive(u.getTokenUnit(), "tokenUnit");
        nonNegative(u.getPromptRate(), "输入倍率");
        nonNegative(u.getCompletionRate(), "输出倍率");
        nonNegative(u.getMinCost(), "单次最小消耗");
        nonNegative(u.getDefaultCostPerMsg(), "默认每条消耗");
        nonNegative(u.getImageExtraCost(), "图片额外费用");
        nonNegative(u.getAnalyzeBaseCost(), "分析基础费用");
        nonNegative(u.getAnalyzeCostPerMsg(), "分析每条消息增量");
        positive(u.getTtsCharsPerCredit(), "TTS 每 N 字符扣 1 积分");
        nonNegative(u.getTtsMinCost(), "TTS 最小消耗");
        nonNegative(u.getMonthlyFreeQuota(), "月度免费配额");
        positive(u.getPlanDurationDays(), "套餐时长");
        nonNegative(u.getPlanLiteCredit(), "Lite 积分");
        nonNegative(u.getPlanProCredit(), "Pro 积分");
        nonNegative(u.getPlanProPlusCredit(), "ProPlus 积分");
        nonNegative(u.getPlanUltraCredit(), "Ultra 积分");
        nonNegative(u.getContextExtraCostPerMsg(), "上下文每条增量");
        nonNegative(u.getContextFreeMsgCount(), "上下文免费条数");
        nonNegative(u.getDailyCapCost(), "每日封顶消耗");

        nonNegativeDecimal(u.getPlanLitePrice(), "Lite 价格");
        nonNegativeDecimal(u.getPlanProPrice(), "Pro 价格");
        nonNegativeDecimal(u.getPlanProPlusPrice(), "ProPlus 价格");
        nonNegativeDecimal(u.getPlanUltraPrice(), "Ultra 价格");

        if (u.getOvertaxRate() != null && u.getOvertaxRate() < 1.0) {
            throw new BizException(400, "超配额倍率不能小于 1.0（1.0 = 不涨价）");
        }
        discount(u.getSmallMonthCardDiscount(), "小月卡折扣");
        discount(u.getLargeMonthCardDiscount(), "大月卡折扣");
        discount(u.getAllTierDiscount(), "ALL 状态折扣");

        validateRateJson(u.getModelRates(), "模型费率映射");
        validateRateJson(u.getAnalyzeTypeRates(), "分析类型倍率");
        validateThresholdJson(u.getTieredDiscountThresholds());
    }

    private static void nonNegative(Integer v, String label) {
        if (v != null && v < 0) throw new BizException(400, label + " 不能为负");
    }

    private static void positive(Integer v, String label) {
        if (v != null && v <= 0) throw new BizException(400, label + " 必须大于 0");
    }

    private static void nonNegativeDecimal(BigDecimal v, String label) {
        if (v != null && v.signum() < 0) throw new BizException(400, label + " 不能为负");
    }

    private static void discount(Double v, String label) {
        if (v == null) return;
        if (v < 0.01 || v > 1.0) throw new BizException(400, label + " 必须在 0.01~1.0 之间（1.0 = 不打折）");
    }

    /** {"模型":倍率} / {"分析类型":倍率} —— 必须是对象，值为 ≥0 的数字 */
    private static void validateRateJson(String json, String label) {
        if (json == null || json.isBlank()) return;
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BizException(400, label + " 不是合法 JSON：" + e.getMessage());
        }
        if (!node.isObject()) throw new BizException(400, label + " 必须是 JSON 对象，如 {\"default\":1.0}");
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            if (!entry.getValue().isNumber()) {
                throw new BizException(400, label + " 中「" + entry.getKey() + "」的倍率必须是数字");
            }
            if (entry.getValue().asDouble() < 0) {
                throw new BizException(400, label + " 中「" + entry.getKey() + "」的倍率不能为负");
            }
        }
    }

    /** {"累计积分":折扣} —— 键是正整数，值是 0.01~1.0 */
    private static void validateThresholdJson(String json) {
        if (json == null || json.isBlank()) return;
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BizException(400, "阶梯累计折扣 不是合法 JSON：" + e.getMessage());
        }
        if (!node.isObject()) throw new BizException(400, "阶梯累计折扣 必须是 JSON 对象，如 {\"1000\":0.95}");
        var it = node.fields();
        while (it.hasNext()) {
            var entry = it.next();
            try {
                if (Long.parseLong(entry.getKey()) <= 0) {
                    throw new BizException(400, "阶梯累计折扣的阈值必须是正整数：" + entry.getKey());
                }
            } catch (NumberFormatException e) {
                throw new BizException(400, "阶梯累计折扣的阈值必须是正整数：" + entry.getKey());
            }
            if (!entry.getValue().isNumber()) {
                throw new BizException(400, "阶梯累计折扣的折扣必须是数字：" + entry.getKey());
            }
            double d = entry.getValue().asDouble();
            if (d < 0.01 || d > 1.0) {
                throw new BizException(400, "阶梯累计折扣「" + entry.getKey() + "」必须在 0.01~1.0 之间");
            }
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public void evictCache() {        cachedRule.set(null);
        log.info("积分规则缓存已失效");
    }

    private synchronized CreditRule createDefaultRuleIfAbsent() {
        return creditRuleRepository.findById(RULE_ID)
                .orElseGet(this::saveDefaultRule);
    }

    private CreditRule saveDefaultRule() {
        CreditRule rule = buildDefaultRule();
        CreditRule saved = creditRuleRepository.save(rule);
        log.info("默认积分规则已创建 id={}", RULE_ID);
        return saved;
    }

    private CreditRule buildDefaultRule() {
        CreditRule rule = new CreditRule();
        rule.setId(RULE_ID);
        rule.setNewUserBonus(500);
        rule.setSignInPoints(150);
        rule.setTokenUnit(1000);
        rule.setPromptRate(2);
        rule.setCompletionRate(4);
        rule.setMinCost(5);
        rule.setDefaultCostPerMsg(10);
        rule.setAdminFree(true);
        rule.setPlanLitePrice(new BigDecimal("9.9"));
        rule.setPlanLiteCredit(2000);
        rule.setPlanProPrice(new BigDecimal("59"));
        rule.setPlanProCredit(4000);
        rule.setPlanProPlusPrice(new BigDecimal("219"));
        rule.setPlanProPlusCredit(12000);
        rule.setPlanUltraPrice(new BigDecimal("629"));
        rule.setPlanUltraCredit(40000);
        rule.setPlanDurationDays(30);
        // 精细化计费默认值
        rule.setModelRates("{\"default\":1.0}");
        rule.setImageExtraCost(5);
        rule.setAnalyzeBaseCost(10);
        rule.setAnalyzeCostPerMsg(1);
        rule.setAnalyzeTypeRates("{\"default\":1.0}");
        rule.setTtsCharsPerCredit(50);
        rule.setTtsMinCost(2);
        rule.setMonthlyFreeQuota(0);
        rule.setOvertaxRate(1.5);
        // 精细化扩展默认值
        rule.setSmallMonthCardDiscount(0.9);   // 小月卡9折
        rule.setLargeMonthCardDiscount(0.8);   // 大月卡8折
        rule.setAllTierDiscount(0.7);          // ALL状态7折
        rule.setContextExtraCostPerMsg(1);     // 每条历史消息+1积分
        rule.setContextFreeMsgCount(10);       // 前10条免费
        rule.setDailyCapCost(0);               // 0=不限
        rule.setTieredDiscountThresholds("{\"1000\":0.95,\"5000\":0.9,\"20000\":0.85}");
        return rule;
    }
}
