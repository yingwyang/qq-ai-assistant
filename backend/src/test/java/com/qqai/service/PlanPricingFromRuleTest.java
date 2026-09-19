package com.qqai.service;

import com.qqai.entity.CreditRule;
import com.qqai.entity.enums.SubscriptionTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「积分规则表单是唯一价格来源」的回归测试。
 *
 * <p>2026-09-19 之前：小月卡 ¥30/3000、大月卡 ¥68/8000、直购积分·100000 ¥648/100000
 * 硬编码在 {@code SubscriptionsController} / {@code SubscriptionService} / {@code CreditService} 里，
 * 管理员在后台改不动，客户端文案与实收还可能不一致（例：卡片写「每日签到额外 +100」但代码是别的数）。
 * 这里锁住：改 CreditRule 就能改价、改积分、改每日加成与折扣。</p>
 */
class PlanPricingFromRuleTest {

    private final SubscriptionService subscriptionService = new SubscriptionService();

    private static CreditRule customRule() {
        CreditRule rule = new CreditRule();
        // 直购积分
        rule.setPlanLitePrice(new BigDecimal("1.5"));
        rule.setPlanLiteCredit(111);
        rule.setPlanProPrice(new BigDecimal("2.5"));
        rule.setPlanProCredit(222);
        rule.setPlanProPlusPrice(new BigDecimal("3.5"));
        rule.setPlanProPlusCredit(333);
        rule.setPlanUltraPrice(new BigDecimal("4.5"));
        rule.setPlanUltraCredit(444);
        rule.setPlanMegaPrice(new BigDecimal("5.5"));
        rule.setPlanMegaCredit(555);
        // 会员月卡
        rule.setPlanSmallMonthCardPrice(new BigDecimal("6.5"));
        rule.setPlanSmallMonthCardCredit(666);
        rule.setPlanSmallMonthCardDailyBonus(7);
        rule.setPlanLargeMonthCardPrice(new BigDecimal("8.5"));
        rule.setPlanLargeMonthCardCredit(888);
        rule.setPlanLargeMonthCardDailyBonus(9);
        return rule;
    }

    @Test
    @DisplayName("所有档位的价格都读规则（含月卡与直购积分·100000，不再硬编码）")
    void pricesComeFromRule() {
        CreditRule rule = customRule();
        assertEquals(0, new BigDecimal("1.5").compareTo(subscriptionService.getPlanPrice(SubscriptionTier.LITE, rule)));
        assertEquals(0, new BigDecimal("5.5").compareTo(subscriptionService.getPlanPrice(SubscriptionTier.MEGA, rule)));
        assertEquals(0, new BigDecimal("6.5").compareTo(
                subscriptionService.getPlanPrice(SubscriptionTier.SMALL_MONTH_CARD, rule)));
        assertEquals(0, new BigDecimal("8.5").compareTo(
                subscriptionService.getPlanPrice(SubscriptionTier.LARGE_MONTH_CARD, rule)));
    }

    @Test
    @DisplayName("所有档位的赠送积分都读规则")
    void creditsComeFromRule() {
        CreditRule rule = customRule();
        assertEquals(111, subscriptionService.getPlanCredit(SubscriptionTier.LITE, rule));
        assertEquals(555, subscriptionService.getPlanCredit(SubscriptionTier.MEGA, rule));
        assertEquals(666, subscriptionService.getPlanCredit(SubscriptionTier.SMALL_MONTH_CARD, rule));
        assertEquals(888, subscriptionService.getPlanCredit(SubscriptionTier.LARGE_MONTH_CARD, rule));
    }

    @Test
    @DisplayName("月卡每日签到加成读规则；双持 = 两张卡相加（不会出现双持反而更少）")
    void dailyBonusComesFromRule() {
        CreditRule rule = customRule();
        assertEquals(7, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.SMALL_MONTH_CARD, rule));
        assertEquals(9, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.LARGE_MONTH_CARD, rule));
        assertEquals(16, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.ALL, rule));
        assertEquals(0, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.FREE, rule));
    }

    @Test
    @DisplayName("出厂默认值与客户端文案一致：小 30/3000/+100，大 68/8000/+300，100000 档 648")
    void defaultsMatchClientCopy() {
        CreditRule rule = new CreditRuleService().defaultRule();
        assertEquals(0, new BigDecimal("30").compareTo(
                subscriptionService.getPlanPrice(SubscriptionTier.SMALL_MONTH_CARD, rule)));
        assertEquals(3000, subscriptionService.getPlanCredit(SubscriptionTier.SMALL_MONTH_CARD, rule));
        assertEquals(100, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.SMALL_MONTH_CARD, rule));

        assertEquals(0, new BigDecimal("68").compareTo(
                subscriptionService.getPlanPrice(SubscriptionTier.LARGE_MONTH_CARD, rule)));
        assertEquals(8000, subscriptionService.getPlanCredit(SubscriptionTier.LARGE_MONTH_CARD, rule));
        assertEquals(300, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.LARGE_MONTH_CARD, rule));

        // 双持 +400 = 100 + 300（客户端订阅页写的「+400」）
        assertEquals(400, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.ALL, rule));

        assertEquals(0, new BigDecimal("648").compareTo(
                subscriptionService.getPlanPrice(SubscriptionTier.MEGA, rule)));
        assertEquals(100000, subscriptionService.getPlanCredit(SubscriptionTier.MEGA, rule));
    }

    @Test
    @DisplayName("每日加成配成负数时按 0 处理，不发放负积分")
    void negativeBonusIsClamped() {
        CreditRule rule = new CreditRule();
        rule.setPlanSmallMonthCardDailyBonus(-5);
        rule.setPlanLargeMonthCardDailyBonus(null);
        assertEquals(0, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.SMALL_MONTH_CARD, rule));
        assertEquals(0, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.LARGE_MONTH_CARD, rule));
        assertEquals(0, subscriptionService.getMonthlyCardDailyBonus(SubscriptionTier.ALL, rule));
    }
}
