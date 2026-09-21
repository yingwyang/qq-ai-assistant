package com.qqai;

import com.qqai.entity.CreditRule;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.repository.CreditRuleRepository;
import com.qqai.repository.SubscriptionOrderRepository;
import com.qqai.service.CreditRuleService;
import com.qqai.service.SubscriptionOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单出参映射回归（企业级化批次 E）。
 *
 * 两件事：① 套餐展示名改为读「规则配置」动态生成（原先硬编码「直购积分·600」，
 * 管理员改了积分值后页面名称与实发积分不一致）；② 用户端与管理端共用同一映射实现，
 * 管理端多出排查字段（clientIp/userAgent/disputeReason），用户端不得泄漏这些。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SubscriptionOrderMapperTest {

    @Autowired private SubscriptionOrderMapper mapper;
    @Autowired private CreditRuleService creditRuleService;
    @Autowired private CreditRuleRepository creditRuleRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;

    @Test
    @DisplayName("套餐名跟随规则配置变化（不再硬编码直购积分·600）")
    void planNameFollowsRuleConfiguration() {
        CreditRule rule = creditRuleService.getSafeRule();
        rule.setPlanLiteCredit(7777);
        creditRuleRepository.save(rule);
        creditRuleService.evictCache();

        assertEquals("直购积分·7777", mapper.planTierName(SubscriptionTier.LITE),
                "改了规则里的 LITE 积分后，展示名必须同步变化");
        assertEquals("小月卡", mapper.planTierName(SubscriptionTier.SMALL_MONTH_CARD));
        assertEquals("大月卡", mapper.planTierName(SubscriptionTier.LARGE_MONTH_CARD));
        assertEquals("免费版", mapper.planTierName(SubscriptionTier.FREE));
        assertEquals("免费版", mapper.planTierName(null));
    }

    @Test
    @DisplayName("用户端视图不含管理员专属字段；管理端视图包含")
    void userViewHidesAdminOnlyFields() {
        SubscriptionOrder order = sampleOrder();

        Map<String, Object> userView = mapper.toUserView(order);
        assertFalse(userView.containsKey("clientIp"), "用户端不应返回 clientIp");
        assertFalse(userView.containsKey("userAgent"), "用户端不应返回 userAgent");
        assertFalse(userView.containsKey("disputeReason"), "用户端不应返回 disputeReason");
        // 共有字段必须一致
        assertEquals(order.getOrderNo(), userView.get("orderNo"));
        // 套餐名以「规则配置」为准，而不是订单里的积分快照（演示两者可能不同：订单快照 600，规则现值见下）
        assertEquals(mapper.planTierName(SubscriptionTier.LITE), userView.get("planName"));
        assertEquals("PENDING", userView.get("status"));
        assertEquals("", userView.get("refundStatus"));

        Map<String, Object> adminView = mapper.toAdminView(order);
        assertEquals("10.0.0.9", adminView.get("clientIp"));
        assertEquals("JUnit", adminView.get("userAgent"));
        assertEquals("测试纠纷原因", adminView.get("disputeReason"));
        assertEquals(order.getOrderNo(), adminView.get("orderNo"));
    }

    @Test
    @DisplayName("管理端视图从 metadata 解析申请时间等扩展字段")
    void adminViewExtractsMetadataFields() {
        SubscriptionOrder order = sampleOrder();
        order.setMetadata("refundRequestReason=测试;refundRequestedAt=2026-09-21T10:00;"
                + "disputedAt=2026-09-21T11:00;refundRejectReason=凭证不足");

        Map<String, Object> adminView = mapper.toAdminView(order);
        assertEquals("2026-09-21T10:00", adminView.get("refundRequestedAt"));
        assertEquals("2026-09-21T11:00", adminView.get("disputedAt"));
        assertEquals("凭证不足", adminView.get("refundRejectReason"));
    }

    @Test
    @DisplayName("退款状态文案：仅退款相关状态有值")
    void refundStatusLabel() {
        assertEquals("已退款", mapper.refundStatusName(com.qqai.entity.enums.OrderStatus.REFUNDED));
        assertEquals("退款审批中", mapper.refundStatusName(com.qqai.entity.enums.OrderStatus.PENDING_REFUND));
        assertEquals("", mapper.refundStatusName(com.qqai.entity.enums.OrderStatus.PAID));
        assertEquals("", mapper.refundStatusName(null));
    }

    private SubscriptionOrder sampleOrder() {
        SubscriptionOrder order = new SubscriptionOrder();
        order.setOrderNo("QQAIMAPPER0001");
        order.setUserId(42L);
        order.setPlanTier(SubscriptionTier.LITE);
        order.setPrice(new BigDecimal("9.90"));
        order.setCreditAmount(600);
        order.setDurationDays(30);
        order.setStatus(com.qqai.entity.enums.OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        order.setClientIp("10.0.0.9");
        order.setUserAgent("JUnit");
        order.setDisputeReason("测试纠纷原因");
        return subscriptionOrderRepository.save(order);
    }
}
