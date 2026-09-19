package com.qqai.service;

import com.qqai.entity.CreditRule;
import com.qqai.entity.CreditTransaction;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「模拟现金账」：在积分流水之上叠一层现金收支，让后台能看出钱从哪来、花到哪去。
 *
 * <h3>为什么是模拟</h3>
 * <p>本项目没有接真实支付通道（没有下单、回调、对账单）。所以现金不是银行流水，而是按
 * <b>规则折算</b>出来的：</p>
 * <ul>
 *   <li>{@code SUBSCRIPTION_PURCHASE} —— 按套餐价计收入（价格来自 {@code credit_rule}，
 *       与 {@code SubscriptionService.getPlanPrice} 同一口径，后台改价立即生效）；</li>
 *   <li>{@code REFUND} —— 按退款比例计支出（比例写在备注里，原单金额由
 *       {@code REFUND-SUB-xxx → SUB-xxx} 关联回查）；</li>
 *   <li>AI 对话 / 分析 / TTS —— 按积分成本折算支出（默认 100 积分 = 1 元，可配置）；</li>
 *   <li>赠送 / 签到 / 月卡每日奖励 / 过期 —— 与现金无关，记 0；</li>
 *   <li>{@code CASH_INCOME} / {@code CASH_EXPENSE} —— 管理员手工记账（模拟），金额直接落库。</li>
 * </ul>
 *
 * <p>除手工记账外都不写库：读取时推算，历史数据无需回填，改规则后口径也自动跟着变。</p>
 */
@Service
public class CashLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CashLedgerService.class);

    /** 现金类别常量 */
    public static final String CATEGORY_SUBSCRIPTION = "SUBSCRIPTION";
    public static final String CATEGORY_REFUND = "REFUND";
    public static final String CATEGORY_AI_COST = "AI_COST";
    public static final String CATEGORY_MANUAL = "MANUAL";

    /** 退款备注里的比例：「订单退款(ratio=1.0): ...」 */
    private static final Pattern REFUND_RATIO = Pattern.compile("ratio=([0-9.]+)");
    /** 订单号：SUB-20260827-XXXX 或 REFUND-SUB-20260827-XXXX */
    private static final Pattern ORDER_ID = Pattern.compile("(SUB-[A-Za-z0-9-]+)");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 积分→现金折算率：每 1 积分折算多少元（默认 0.01，即 100 积分 = 1 元）。
     * 只用于把 AI/TTS 的积分消耗折算成"模拟成本"。
     */
    @Value("${credits.cash.cost-per-credit:0.01}")
    private BigDecimal costPerCredit = new BigDecimal("0.01");

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private com.qqai.repository.CreditTransactionRepository creditTransactionRepository;

    @Autowired(required = false)
    private SubscriptionService subscriptionService;

    // ==================== 单笔推算 ====================

    /**
     * 这笔流水的现金影响（带符号，元）。
     *
     * @param originalPurchase 退款时需要原单（可为 null：拿不到就按 0 处理，不猜）
     */
    public BigDecimal cashOf(CreditTransaction tx, CreditRule rule, CreditTransaction originalPurchase) {
        if (tx == null) return BigDecimal.ZERO;
        if (tx.getCashAmount() != null) return tx.getCashAmount();   // 手工记账：以落库值为准

        String category = categoryOf(tx);
        if (category == null) return BigDecimal.ZERO;

        return switch (category) {
            case CATEGORY_SUBSCRIPTION -> planPriceOf(tx, rule);
            case CATEGORY_REFUND -> refundCashOf(tx, originalPurchase);
            case CATEGORY_AI_COST -> pointsCostOf(tx);
            default -> BigDecimal.ZERO;
        };
    }

    /** 现金类别；null 表示这笔与现金无关 */
    public String categoryOf(CreditTransaction tx) {
        if (tx == null || tx.getType() == null) return null;
        if (tx.getCashCategory() != null && !tx.getCashCategory().isBlank()) return tx.getCashCategory();
        return switch (tx.getType()) {
            case SUBSCRIPTION_PURCHASE -> CATEGORY_SUBSCRIPTION;
            case REFUND -> CATEGORY_REFUND;
            case AI_CHAT, AI_ANALYZE, AI_CONSUMPTION, TTS_SYNTHESIS -> CATEGORY_AI_COST;
            case CASH_INCOME, CASH_EXPENSE -> CATEGORY_MANUAL;
            default -> null;
        };
    }

    /** 订阅收入 = 套餐价（备注里带档位，如「订阅套餐: LARGE_MONTH_CARD」） */
    BigDecimal planPriceOf(CreditTransaction tx, CreditRule rule) {
        String tierName = parseTier(tx.getRemark());
        if (tierName == null || rule == null) {
            log.debug("订阅流水 {} 无法解析套餐档位，现金记 0：remark={}", tx.getId(), tx.getRemark());
            return BigDecimal.ZERO;
        }
        try {
            com.qqai.entity.enums.SubscriptionTier tier =
                    com.qqai.entity.enums.SubscriptionTier.valueOf(tierName);
            BigDecimal price = subscriptionService != null
                    ? subscriptionService.getPlanPrice(tier, rule)
                    : fallbackPlanPrice(tier, rule);
            return price == null ? BigDecimal.ZERO : price.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("订阅流水 {} 档位 {} 无价格：{}", tx.getId(), tierName, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /** 退款支出 = -(原单金额 × 退款比例) */
    BigDecimal refundCashOf(CreditTransaction tx, CreditTransaction originalPurchase) {
        if (originalPurchase == null) return BigDecimal.ZERO;
        BigDecimal ratio = parseRefundRatio(tx.getRemark());
        BigDecimal original = planPriceOf(originalPurchase, creditRuleService.getRule());
        if (original.signum() == 0) return BigDecimal.ZERO;
        return original.multiply(ratio).negate().setScale(2, RoundingMode.HALF_UP);
    }

    /** AI/TTS 成本 = -(积分消耗 × 折算率) */
    BigDecimal pointsCostOf(CreditTransaction tx) {
        int amount = tx.getAmount() == null ? 0 : Math.abs(tx.getAmount());
        return costPerCredit.multiply(BigDecimal.valueOf(amount))
                .negate().setScale(2, RoundingMode.HALF_UP);
    }

    /** 备注里解析套餐档位 */
    static String parseTier(String remark) {
        if (remark == null) return null;
        Matcher m = Pattern.compile("(LITE|PROPLUS|PRO|ULTRA|MEGA|SMALL_MONTH_CARD|LARGE_MONTH_CARD|ALL)")
                .matcher(remark.toUpperCase());
        String found = null;
        // 取最长的匹配，避免 "PRO" 抢先匹配到 "PROPLUS"
        while (m.find()) {
            String cur = m.group(1);
            if (found == null || cur.length() > found.length()) found = cur;
        }
        return found;
    }

    /** 备注里解析退款比例，默认 1.0 */
    static BigDecimal parseRefundRatio(String remark) {
        if (remark == null) return BigDecimal.ONE;
        Matcher m = REFUND_RATIO.matcher(remark);
        if (m.find()) {
            try {
                return new BigDecimal(m.group(1));
            } catch (Exception ignore) {
                // 落到默认值
            }
        }
        return BigDecimal.ONE;
    }

    /** 从退款流水中取出被退款的原单号（REFUND-SUB-xxx → SUB-xxx） */
    static String originalOrderIdOf(CreditTransaction refundTx) {
        if (refundTx == null) return null;
        String source = refundTx.getRelatedId();
        if (source == null || source.isBlank()) source = refundTx.getRemark();
        if (source == null) return null;
        Matcher m = ORDER_ID.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    /** SubscriptionService 不可用时（单测 new 出来的场景）的兜底价 —— 与 SubscriptionService 同一份规则字段 */
    private static BigDecimal fallbackPlanPrice(com.qqai.entity.enums.SubscriptionTier tier, CreditRule rule) {
        return switch (tier) {
            case LITE -> rule.getPlanLitePrice();
            case PRO -> rule.getPlanProPrice();
            case PROPLUS -> rule.getPlanProPlusPrice();
            case ULTRA -> rule.getPlanUltraPrice();
            case MEGA -> rule.getPlanMegaPrice();
            case SMALL_MONTH_CARD -> rule.getPlanSmallMonthCardPrice();
            case LARGE_MONTH_CARD -> rule.getPlanLargeMonthCardPrice();
            default -> null;
        };
    }

    // ==================== 批量：列表 + 汇总 ====================

    /**
     * 给一批流水补上现金信息（退款需要原单，这里一次性批量查，避免 N+1）。
     *
     * @return txId → { cashAmount(元,带符号), cashCategory, cashCategoryLabel }
     */
    public Map<Long, Map<String, Object>> enrich(List<CreditTransaction> rows) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) return out;

        CreditRule rule = creditRuleService.getRule();

        // 收集退款需要回查的原单号
        List<String> orderIds = new ArrayList<>();
        for (CreditTransaction tx : rows) {
            if (CATEGORY_REFUND.equals(categoryOf(tx))) {
                String orderId = originalOrderIdOf(tx);
                if (orderId != null && !orderIds.contains(orderId)) orderIds.add(orderId);
            }
        }
        Map<String, CreditTransaction> purchases = new HashMap<>();
        if (!orderIds.isEmpty()) {
            try {
                for (CreditTransaction t : creditTransactionRepository.findByRelatedIdIn(orderIds)) {
                    if (t.getType() == CreditTransactionType.SUBSCRIPTION_PURCHASE
                            && t.getRelatedId() != null && !purchases.containsKey(t.getRelatedId())) {
                        purchases.put(t.getRelatedId(), t);
                    }
                }
            } catch (Exception e) {
                log.warn("回查退款原单失败（退款现金按 0 计）: {}", e.getMessage());
            }
        }

        for (CreditTransaction tx : rows) {
            String category = categoryOf(tx);
            BigDecimal cash = cashOf(tx, rule, purchases.get(originalOrderIdOf(tx)));
            Map<String, Object> info = new HashMap<>();
            info.put("cashAmount", cash);
            info.put("cashCategory", category);
            info.put("cashCategoryLabel", categoryLabel(category));
            out.put(tx.getId(), info);
        }
        return out;
    }

    /**
     * 现金收支汇总（含趋势与构成），供后台图表使用。
     *
     * @param rows 已经按时间范围取好的流水（调用方负责限定范围与条数上限）
     */
    public Map<String, Object> summarize(List<CreditTransaction> rows, LocalDate from, LocalDate to) {
        Map<Long, Map<String, Object>> cashIndex = enrich(rows);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        long pointsIn = 0;
        long pointsOut = 0;

        Map<String, Map<String, Object>> dayBuckets = new TreeMap<>();
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        Map<String, Long> byCategoryCount = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();

        for (CreditTransaction tx : rows) {
            BigDecimal cash = (BigDecimal) cashIndex.get(tx.getId()).get("cashAmount");
            String category = (String) cashIndex.get(tx.getId()).get("cashCategory");
            int amount = tx.getAmount() == null ? 0 : tx.getAmount();
            boolean isIn = tx.getDirection() == CreditDirection.IN;

            if (cash.signum() > 0) income = income.add(cash);
            else if (cash.signum() < 0) expense = expense.add(cash.abs());
            if (isIn) pointsIn += amount; else pointsOut += amount;

            LocalDate day = tx.getCreatedAt() == null ? from : tx.getCreatedAt().toLocalDate();
            Map<String, Object> bucket = dayBuckets.computeIfAbsent(day.format(DAY), k -> {
                Map<String, Object> b = new HashMap<>();
                b.put("date", k);
                b.put("income", BigDecimal.ZERO);
                b.put("expense", BigDecimal.ZERO);
                b.put("net", BigDecimal.ZERO);
                b.put("pointsIn", 0L);
                b.put("pointsOut", 0L);
                b.put("count", 0L);
                return b;
            });
            if (cash.signum() > 0) bucket.put("income", ((BigDecimal) bucket.get("income")).add(cash));
            else if (cash.signum() < 0) bucket.put("expense", ((BigDecimal) bucket.get("expense")).add(cash.abs()));
            bucket.put("net", ((BigDecimal) bucket.get("income")).subtract((BigDecimal) bucket.get("expense")));
            if (isIn) bucket.put("pointsIn", (Long) bucket.get("pointsIn") + amount);
            else bucket.put("pointsOut", (Long) bucket.get("pointsOut") + amount);
            bucket.put("count", (Long) bucket.get("count") + 1);

            if (category != null && cash.signum() != 0) {
                byCategory.merge(category, cash, BigDecimal::add);
                byCategoryCount.merge(category, 1L, Long::sum);
            }
            String typeKey = tx.getType() == null ? "UNKNOWN" : tx.getType().name();
            Map<String, Object> t = byType.computeIfAbsent(typeKey, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("type", k);
                m.put("points", 0L);
                m.put("cash", BigDecimal.ZERO);
                m.put("count", 0L);
                return m;
            });
            t.put("points", (Long) t.get("points") + amount);
            t.put("cash", ((BigDecimal) t.get("cash")).add(cash));
            t.put("count", (Long) t.get("count") + 1);
        }

        // 补齐没有流水的日期，折线不会断
        List<Map<String, Object>> series = new ArrayList<>(dayBuckets.values());
        if (from != null && to != null && !from.isAfter(to)) {
            List<Map<String, Object>> filled = new ArrayList<>();
            Map<String, Map<String, Object>> byDay = new HashMap<>();
            series.forEach(s -> byDay.put((String) s.get("date"), s));
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                String key = d.format(DAY);
                filled.add(byDay.getOrDefault(key, emptyBucket(key)));
            }
            series = filled;
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byCategory.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", e.getKey());
            m.put("label", categoryLabel(e.getKey()));
            m.put("amount", e.getValue().setScale(2, RoundingMode.HALF_UP));
            m.put("count", byCategoryCount.getOrDefault(e.getKey(), 0L));
            categories.add(m);
        }
        categories.sort(Comparator.comparing(
                (Map<String, Object> m) -> ((BigDecimal) m.get("amount")).abs()).reversed());

        List<Map<String, Object>> types = new ArrayList<>(byType.values());
        types.sort(Comparator.comparing((Map<String, Object> m) -> (Long) m.get("count")).reversed());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from == null ? null : from.format(DAY));
        result.put("to", to == null ? null : to.format(DAY));
        result.put("income", income.setScale(2, RoundingMode.HALF_UP));
        result.put("expense", expense.setScale(2, RoundingMode.HALF_UP));
        result.put("net", income.subtract(expense).setScale(2, RoundingMode.HALF_UP));
        result.put("pointsIn", pointsIn);
        result.put("pointsOut", pointsOut);
        result.put("pointsNet", pointsIn - pointsOut);
        result.put("count", (long) rows.size());
        result.put("series", series);
        result.put("byCategory", categories);
        result.put("byType", types);
        return result;
    }

    private static Map<String, Object> emptyBucket(String date) {
        Map<String, Object> b = new HashMap<>();
        b.put("date", date);
        b.put("income", BigDecimal.ZERO);
        b.put("expense", BigDecimal.ZERO);
        b.put("net", BigDecimal.ZERO);
        b.put("pointsIn", 0L);
        b.put("pointsOut", 0L);
        b.put("count", 0L);
        return b;
    }

    /** 现金类别 → 中文标签（前端图表与表格共用） */
    public static String categoryLabel(String category) {
        if (category == null) return "无现金影响";
        return switch (category) {
            case CATEGORY_SUBSCRIPTION -> "订阅收入";
            case CATEGORY_REFUND -> "订单退款";
            case CATEGORY_AI_COST -> "模型调用成本";
            case CATEGORY_MANUAL -> "手工记账";
            default -> category;
        };
    }

    /** 供控制器复用：把 LocalDateTime 归到「天」 */
    static LocalDate dayOf(LocalDateTime t) {
        return t == null ? null : t.toLocalDate();
    }
}
