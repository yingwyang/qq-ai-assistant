package com.qqai.service;

import com.qqai.entity.CreditRule;
import com.qqai.entity.CreditTransaction;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模拟现金账的纯逻辑测试：类别判定、金额折算、退款回查、汇总聚合。
 *
 * <p>不依赖 Spring：CreditRuleService / Repository 用 Mockito 打桩，折算率用默认 0.01 元/积分。</p>
 */
class CashLedgerServiceTest {

    private CashLedgerService service;
    private CreditRuleService ruleService;
    private com.qqai.repository.CreditTransactionRepository repository;

    @BeforeEach
    void setUp() {
        service = new CashLedgerService();
        ruleService = Mockito.mock(CreditRuleService.class);
        repository = Mockito.mock(com.qqai.repository.CreditTransactionRepository.class);
        Mockito.when(ruleService.getRule()).thenReturn(new CreditRule());
        Mockito.when(repository.findByRelatedIdIn(Mockito.anyList())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "creditRuleService", ruleService);
        ReflectionTestUtils.setField(service, "creditTransactionRepository", repository);
        ReflectionTestUtils.setField(service, "costPerCredit", new BigDecimal("0.01"));
    }

    private static CreditTransaction tx(CreditTransactionType type, CreditDirection dir, int amount,
                                        String remark, String relatedId, LocalDateTime at) {
        CreditTransaction t = new CreditTransaction();
        t.setId((long) (Math.random() * 1_000_000));
        t.setUserId(2L);
        t.setType(type);
        t.setDirection(dir);
        t.setAmount(amount);
        t.setBalanceAfter(1000);
        t.setRemark(remark);
        t.setRelatedId(relatedId);
        t.setCreatedAt(at);
        return t;
    }

    // ==================== 解析 ====================

    @Test
    @DisplayName("套餐档位解析：PROPLUS 不会被 PRO 抢先匹配")
    void parseTierPrefersLongestMatch() {
        assertEquals("LARGE_MONTH_CARD", CashLedgerService.parseTier("订阅套餐: LARGE_MONTH_CARD"));
        assertEquals("PROPLUS", CashLedgerService.parseTier("订阅套餐: PROPLUS"));
        assertEquals("PRO", CashLedgerService.parseTier("订阅套餐: PRO"));
        assertNull(CashLedgerService.parseTier("签到奖励"));
        assertNull(CashLedgerService.parseTier(null));
    }

    @Test
    @DisplayName("退款比例解析：缺省按 1.0")
    void parseRefundRatio() {
        assertEquals(0, new BigDecimal("0.5").compareTo(
                CashLedgerService.parseRefundRatio("订单退款(ratio=0.5): SUB-1 退款审批通过")));
        assertEquals(0, BigDecimal.ONE.compareTo(CashLedgerService.parseRefundRatio("订单退款")));
        assertEquals(0, BigDecimal.ONE.compareTo(CashLedgerService.parseRefundRatio(null)));
    }

    @Test
    @DisplayName("从退款流水里取出原单号（REFUND-SUB-xxx → SUB-xxx）")
    void originalOrderId() {
        CreditTransaction refund = tx(CreditTransactionType.REFUND, CreditDirection.OUT, 100000,
                "订单退款(ratio=1.0): SUB-20260827-NXX6W7 退款审批通过", "REFUND-SUB-20260827-NXX6W7", LocalDateTime.now());
        assertEquals("SUB-20260827-NXX6W7", CashLedgerService.originalOrderIdOf(refund));
        assertNull(CashLedgerService.originalOrderIdOf(
                tx(CreditTransactionType.REFUND, CreditDirection.OUT, 1, "无单号", null, LocalDateTime.now())));
    }

    // ==================== 类别与金额 ====================

    @Test
    @DisplayName("现金类别：订阅/退款/模型成本有账，赠送与签到无账")
    void categoryOf() {
        assertEquals(CashLedgerService.CATEGORY_SUBSCRIPTION, service.categoryOf(
                tx(CreditTransactionType.SUBSCRIPTION_PURCHASE, CreditDirection.IN, 232300, "订阅套餐: LARGE_MONTH_CARD", null, LocalDateTime.now())));
        assertEquals(CashLedgerService.CATEGORY_REFUND, service.categoryOf(
                tx(CreditTransactionType.REFUND, CreditDirection.OUT, 100000, "订单退款", null, LocalDateTime.now())));
        assertEquals(CashLedgerService.CATEGORY_AI_COST, service.categoryOf(
                tx(CreditTransactionType.AI_CHAT, CreditDirection.OUT, 9, "default", null, LocalDateTime.now())));
        assertEquals(CashLedgerService.CATEGORY_AI_COST, service.categoryOf(
                tx(CreditTransactionType.TTS_SYNTHESIS, CreditDirection.OUT, 40, "tts", null, LocalDateTime.now())));
        assertNull(service.categoryOf(
                tx(CreditTransactionType.SIGN_IN, CreditDirection.IN, 10, "签到", null, LocalDateTime.now())));
        assertNull(service.categoryOf(
                tx(CreditTransactionType.MONTHLY_CARD_DAILY, CreditDirection.IN, 300, "月卡每日", null, LocalDateTime.now())));
    }

    @Test
    @DisplayName("订阅收入按套餐价折算（LARGE_MONTH_CARD = 68 元）")
    void subscriptionCash() {
        CreditTransaction t = tx(CreditTransactionType.SUBSCRIPTION_PURCHASE, CreditDirection.IN, 232300,
                "订阅套餐: LARGE_MONTH_CARD", "SUB-1", LocalDateTime.now());
        assertEquals(0, new BigDecimal("68.00").compareTo(service.cashOf(t, new CreditRule(), null)));
    }

    @Test
    @DisplayName("模型调用成本按积分折算：100 积分 = 1 元，支出为负")
    void aiCostCash() {
        CreditTransaction t = tx(CreditTransactionType.AI_ANALYZE, CreditDirection.OUT, 254,
                "analyze:summary", null, LocalDateTime.now());
        assertEquals(0, new BigDecimal("-2.54").compareTo(service.cashOf(t, new CreditRule(), null)));
    }

    @Test
    @DisplayName("退款金额 = -(原单金额 × 比例)，拿不到原单就记 0（不猜）")
    void refundCash() {
        CreditTransaction refund = tx(CreditTransactionType.REFUND, CreditDirection.OUT, 100000,
                "订单退款(ratio=0.5): SUB-1 退款审批通过", "REFUND-SUB-1", LocalDateTime.now());
        CreditTransaction original = tx(CreditTransactionType.SUBSCRIPTION_PURCHASE, CreditDirection.IN, 232300,
                "订阅套餐: LARGE_MONTH_CARD", "SUB-1", LocalDateTime.now());

        assertEquals(0, new BigDecimal("-34.00").compareTo(service.cashOf(refund, new CreditRule(), original)));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.cashOf(refund, new CreditRule(), null)));
    }

    @Test
    @DisplayName("手工记账：落库金额优先，不再按规则折算")
    void manualEntryWins() {
        CreditTransaction manual = tx(CreditTransactionType.CASH_INCOME, CreditDirection.IN, 0,
                "【手工记账】赞助", null, LocalDateTime.now());
        manual.setCashAmount(new BigDecimal("200.00"));
        manual.setCashCategory(CashLedgerService.CATEGORY_MANUAL);
        assertEquals(0, new BigDecimal("200.00").compareTo(service.cashOf(manual, new CreditRule(), null)));
        assertEquals(CashLedgerService.CATEGORY_MANUAL, service.categoryOf(manual));
    }

    // ==================== 汇总聚合 ====================

    @Test
    @DisplayName("汇总：现金/积分总额、按日趋势补齐空日、按类别与类型聚合")
    void summarize() {
        LocalDate today = LocalDate.now();
        CreditTransaction sub = tx(CreditTransactionType.SUBSCRIPTION_PURCHASE, CreditDirection.IN, 232300,
                "订阅套餐: LARGE_MONTH_CARD", "SUB-1", today.atTime(10, 0));
        CreditTransaction ai = tx(CreditTransactionType.AI_CHAT, CreditDirection.OUT, 9,
                "default", null, today.atTime(11, 0));
        CreditTransaction sign = tx(CreditTransactionType.SIGN_IN, CreditDirection.IN, 10,
                "签到", null, today.atTime(12, 0));

        Map<String, Object> s = service.summarize(List.of(sub, ai, sign), today.minusDays(2), today);

        assertEquals(0, new BigDecimal("68.00").compareTo((BigDecimal) s.get("income")));
        assertEquals(0, new BigDecimal("0.09").compareTo((BigDecimal) s.get("expense")));
        assertEquals(0, new BigDecimal("67.91").compareTo((BigDecimal) s.get("net")));
        assertEquals(232310L, s.get("pointsIn"));
        assertEquals(9L, s.get("pointsOut"));
        assertEquals(3L, s.get("count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) s.get("series");
        assertEquals(3, series.size(), "空日要补 0，折线不能断");
        assertEquals(0, ((BigDecimal) series.get(0).get("income")).compareTo(BigDecimal.ZERO));
        assertEquals(0, ((BigDecimal) series.get(2).get("income")).compareTo(new BigDecimal("68.00")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories = (List<Map<String, Object>>) s.get("byCategory");
        assertEquals(2, categories.size(), "只有订阅与模型成本有现金账");
        assertEquals(CashLedgerService.CATEGORY_SUBSCRIPTION, categories.get(0).get("category"));
        assertEquals("订阅收入", categories.get(0).get("label"));
        assertEquals(0, new BigDecimal("68.00").compareTo((BigDecimal) categories.get(0).get("amount")));
        assertEquals(CashLedgerService.CATEGORY_AI_COST, categories.get(1).get("category"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) s.get("byType");
        assertEquals(3, types.size());
    }

    @Test
    @DisplayName("空数据不炸：总额为 0、趋势仍按日期补齐")
    void summarizeEmpty() {
        LocalDate today = LocalDate.now();
        Map<String, Object> s = service.summarize(List.of(), today.minusDays(1), today);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) s.get("income")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) s.get("net")));
        assertEquals(2, ((List<?>) s.get("series")).size());
        assertTrue(((List<?>) s.get("byCategory")).isEmpty());
    }

    @Test
    @DisplayName("enrich：给每行补出 cashAmount / cashCategory / 标签")
    void enrichRows() {
        CreditTransaction ai = tx(CreditTransactionType.AI_CHAT, CreditDirection.OUT, 100,
                "default", null, LocalDateTime.now());
        Map<Long, Map<String, Object>> index = service.enrich(List.of(ai));
        Map<String, Object> info = index.get(ai.getId());
        assertNotNull(info);
        assertEquals(0, new BigDecimal("-1.00").compareTo((BigDecimal) info.get("cashAmount")));
        assertEquals(CashLedgerService.CATEGORY_AI_COST, info.get("cashCategory"));
        assertEquals("模型调用成本", info.get("cashCategoryLabel"));
    }
}
