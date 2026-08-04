package com.qqai.service;

import com.qqai.entity.CreditRule;
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
        CreditRule saved = creditRuleRepository.save(existing);
        cachedRule.set(saved);
        log.info("积分规则已更新 id={}", RULE_ID);
        return saved;
    }

    public void evictCache() {
        cachedRule.set(null);
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
        return rule;
    }
}
