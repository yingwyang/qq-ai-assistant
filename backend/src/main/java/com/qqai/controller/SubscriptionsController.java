package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.CreditRule;
import com.qqai.entity.CreditTransaction;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.service.AuditLogService;
import com.qqai.service.CreditRuleService;
import com.qqai.service.CreditService;
import com.qqai.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionsController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionsController.class);

    @Autowired
    private SubscriptionService subscriptionService;

    /** 订单出参映射（与 AdminOrdersController 共用同一实现，避免字段漂移） */
    @Autowired
    private com.qqai.service.SubscriptionOrderMapper orderMapper;

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    /**
     * 订阅页档位清单（客户端「订阅管理」的数据源）。
     *
     * <p>命名与文案全部由 {@code credit_rule} 生成，不再有硬编码数字：
     * 直购档位叫「直购积分·N」，月卡叫「小月卡 / 大月卡」，权益里的积分数、每日签到加成、
     * 折扣都取当前配置 —— 后台改一次，这里和「资金流水」的模拟现金账一起变。</p>
     */
    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPlans() {
        Long userId = securityHelper.requireCurrentUserId();
        CreditRule rule = creditRuleService.getRule();
        Integer durationDays = rule.getPlanDurationDays();

        List<Map<String, Object>> directPlans = new ArrayList<>();
        directPlans.add(directPlan("LITE", "基础模型支持", "标准响应速度"));
        directPlans.add(directPlan("PRO", "全模型支持", "优先响应", "30 天文件存储"));
        directPlans.add(directPlan("PROPLUS", "全模型支持", "高优先级队列", "高级分析功能", "90 天文件存储"));
        directPlans.add(directPlan("ULTRA", "全模型支持", "最高优先级", "全部高级功能", "永久文件存储"));
        directPlans.add(directPlan("MEGA", "全模型支持", "最高优先级", "全部高级功能", "永久文件存储", "专属客服支持"));

        String smallDiscount = discountLabel(rule.getSmallMonthCardDiscount());
        String largeDiscount = discountLabel(rule.getLargeMonthCardDiscount());
        List<Map<String, Object>> cardPlans = new ArrayList<>();
        cardPlans.add(monthlyCard("SMALL_MONTH_CARD", "小月卡", smallDiscount,
                rule.getPlanSmallMonthCardDailyBonus()));
        cardPlans.add(monthlyCard("LARGE_MONTH_CARD", "大月卡", largeDiscount,
                rule.getPlanLargeMonthCardDailyBonus()));

        Map<String, Object> data = new HashMap<>();
        data.put("plans", directPlans);
        data.put("directPlans", directPlans);
        data.put("monthlyCards", cardPlans);
        // 同时持有大小月卡（ALL 全功能版）时实际生效的折扣，取自 credit_rule.all_tier_discount。
        // 前端用它渲染"双持折扣"，避免界面写死"8 折"而实际按另一个数值计费。
        data.put("allTierDiscount", rule.getAllTierDiscount());
        data.put("smallCardDiscount", rule.getSmallMonthCardDiscount());
        data.put("largeCardDiscount", rule.getLargeMonthCardDiscount());
        // 双持每日加成 = 小 + 大（叠加），前端展示「+400」时直接用这个值
        data.put("allTierDailyBonus", safeBonus(rule.getPlanSmallMonthCardDailyBonus())
                + safeBonus(rule.getPlanLargeMonthCardDailyBonus()));
        data.put("groups", Arrays.asList(
                groupMap("DIRECT", "直购积分", "按档购买，立即到账", directPlans),
                groupMap("MONTHLY_CARD", "会员月卡", durationDays + " 天权益，超值更省", cardPlans)
        ));
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** 一张直购积分卡：名称、价格、积分、权益全部读当前规则 */
    private Map<String, Object> directPlan(String tierCode, String... extraFeatures) {
        CreditRule rule = creditRuleService.getRule();
        SubscriptionTier tier = SubscriptionTier.valueOf(tierCode);
        BigDecimal price = subscriptionService.getPlanPrice(tier, rule);
        Integer credit = subscriptionService.getPlanCredit(tier, rule);
        List<String> features = new ArrayList<>();
        features.add(credit + " 积分");
        features.addAll(Arrays.asList(extraFeatures));
        return planToMap(tierCode, "直购积分·" + credit, price, credit, rule.getPlanDurationDays(), tier, "DIRECT", features);
    }

    /** 一张月卡：权益里的积分数、每日签到加成、折扣全部读当前规则 */
    private Map<String, Object> monthlyCard(String tierCode, String name, String discountText, Integer dailyBonus) {
        CreditRule rule = creditRuleService.getRule();
        SubscriptionTier tier = SubscriptionTier.valueOf(tierCode);
        BigDecimal price = subscriptionService.getPlanPrice(tier, rule);
        Integer credit = subscriptionService.getPlanCredit(tier, rule);
        List<String> features = new ArrayList<>();
        features.add(credit + " 积分基础");
        features.add("每日签到额外 +" + safeBonus(dailyBonus) + " 积分");
        if (SubscriptionTier.SMALL_MONTH_CARD.name().equals(tierCode)) features.add("基础模型支持");
        else features.add("全模型支持");
        features.add("专属折扣 " + discountText);
        if (SubscriptionTier.LARGE_MONTH_CARD.name().equals(tierCode)) features.add("优先响应队列");
        features.add(rule.getPlanDurationDays() + " 天有效");
        return planToMap(tierCode, name, price, credit, rule.getPlanDurationDays(), tier, "MONTHLY_CARD", features);
    }

    /** 折扣数值 → 「9 折」这种文案；1.0 表示不打折 */
    private static String discountLabel(Double discount) {
        if (discount == null || discount >= 1.0) return "无折扣";
        double zhe = discount * 10;
        String text = (Math.abs(zhe - Math.round(zhe)) < 1e-9)
                ? String.valueOf((long) Math.round(zhe))
                : String.format("%.1f", zhe);
        return text + " 折";
    }

    private static int safeBonus(Integer v) {
        return v == null || v < 0 ? 0 : v;
    }

    private Map<String, Object> groupMap(String key, String title, String subtitle, List<Map<String, Object>> items) {
        Map<String, Object> g = new HashMap<>();
        g.put("key", key);
        g.put("title", title);
        g.put("subtitle", subtitle);
        g.put("plans", items);
        return g;
    }

    @PostMapping("/purchase")
    public ResponseEntity<ApiResponse<Map<String, Object>>> purchase(@RequestBody Map<String, Object> body,
                                                                     HttpServletRequest request) {
        Long userId = securityHelper.requireCurrentUserId();
        String planCode = (String) body.get("planCode");
        String paymentMethod = (String) body.getOrDefault("paymentMethod", "MANUAL");
        if (paymentMethod == null || paymentMethod.isBlank()) paymentMethod = "MANUAL";

        SubscriptionTier tier = subscriptionService.planCodeToTier(planCode);
        String clientIp = getClientIp(request);
        String userAgent = request != null ? request.getHeader("User-Agent") : null;
        if (userAgent == null) userAgent = "Unknown-UA";

        SubscriptionOrder order = subscriptionService.createOrder(userId, tier, clientIp, userAgent);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("status", order.getStatus() != null ? order.getStatus().name() : "PENDING");
        data.put("price", order.getPrice());
        data.put("creditAmount", order.getCreditAmount());
        data.put("message", "订单已创建，待管理员确认到账后发放权益");

        if (auditLogService != null) {
            auditLogService.log(securityHelper.getCurrentUsername(), "SUBSCRIPTION_PURCHASE",
                    "order:" + order.getOrderNo(), "SUCCESS",
                    "创建订单待确认 plan=" + tier + " points=" + order.getCreditAmount() + " price=" + order.getPrice());
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        Long userId = securityHelper.requireCurrentUserId();
        List<OrderStatus> statuses = subscriptionService.parseStatuses(status);
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        Page<SubscriptionOrder> orderPage = subscriptionService.pageByUserAndStatuses(userId, statuses, pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (SubscriptionOrder o : orderPage.getContent()) {
            content.add(orderToMap(o));
        }
        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", content);
        pageData.put("totalElements", orderPage.getTotalElements());
        pageData.put("totalPages", orderPage.getTotalPages());
        pageData.put("number", orderPage.getNumber());
        pageData.put("size", orderPage.getSize());
        pageData.put("first", orderPage.isFirst());
        pageData.put("last", orderPage.isLast());
        return ResponseEntity.ok(ApiResponse.success(pageData));
    }

    @GetMapping("/orders/{orderNo}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrder(@PathVariable String orderNo) {
        Long userId = securityHelper.requireCurrentUserId();
        // 归属校验收口在 Service（controller 不再各写一遍查找 + 比对）
        SubscriptionOrder order = subscriptionService.getOrderForUser(orderNo, userId);
        Map<String, Object> data = orderToMap(order);
        List<CreditTransaction> related = creditService.findRelatedTransactions(orderNo);
        List<Map<String, Object>> relatedTxs = new ArrayList<>();
        for (CreditTransaction tx : related) {
            Map<String, Object> t = new HashMap<>();
            t.put("id", tx.getId());
            t.put("type", tx.getType() != null ? tx.getType().name() : null);
            t.put("direction", tx.getDirection() != null ? tx.getDirection().name() : null);
            t.put("amount", tx.getAmount());
            t.put("balanceAfter", tx.getBalanceAfter());
            t.put("remark", tx.getRemark());
            t.put("createdAt", tx.getCreatedAt());
            relatedTxs.add(t);
        }
        String refundRelatedId = "REFUND-" + orderNo;
        List<CreditTransaction> refundTxs = creditService.findRelatedTransactions(refundRelatedId);
        for (CreditTransaction tx : refundTxs) {
            Map<String, Object> t = new HashMap<>();
            t.put("id", tx.getId());
            t.put("type", tx.getType() != null ? tx.getType().name() : null);
            t.put("direction", tx.getDirection() != null ? tx.getDirection().name() : null);
            t.put("amount", tx.getAmount());
            t.put("balanceAfter", tx.getBalanceAfter());
            t.put("remark", tx.getRemark());
            t.put("createdAt", tx.getCreatedAt());
            relatedTxs.add(t);
        }
        data.put("relatedTransactions", relatedTxs);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancelOrder(@PathVariable String orderNo,
                                                                        @RequestBody(required = false) Map<String, Object> body) {
        Long userId = securityHelper.requireCurrentUserId();
        String reason = body != null ? (String) body.get("reason") : null;
        // 行锁 + 归属校验 + 状态校验全部在 Service 内完成
        SubscriptionOrder cancelled = subscriptionService.cancelOrderAsUser(orderNo, userId, reason);
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", cancelled.getOrderNo());
        data.put("status", cancelled.getStatus() != null ? cancelled.getStatus().name() : null);
        if (auditLogService != null) {
            auditLogService.log(securityHelper.getCurrentUsername(), "ORDER_CANCEL",
                    "order:" + orderNo, "SUCCESS", reason == null ? "" : reason);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/orders/{orderNo}/refund-request")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestRefund(@PathVariable String orderNo,
                                                                          @RequestBody(required = false) Map<String, Object> body) {
        Long userId = securityHelper.requireCurrentUserId();
        String reason = body != null ? (String) body.get("reason") : null;
        if (reason == null || reason.isBlank()) reason = "用户申请退款";
        log.info("用户{} 申请订单退款 orderNo={} reason={}", userId, orderNo, reason);

        SubscriptionOrder pending = subscriptionService.requestRefund(orderNo, reason, userId);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", pending.getOrderNo());
        data.put("status", pending.getStatus() != null ? pending.getStatus().name() : null);

        if (auditLogService != null) {
            auditLogService.log(securityHelper.getCurrentUsername(), "USER_REFUND_REQUEST",
                    "order:" + orderNo, "SUCCESS", "reason=" + reason);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/orders/{orderNo}/dispute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disputeOrder(@PathVariable String orderNo,
                                                                         @RequestBody(required = false) Map<String, Object> body) {
        Long userId = securityHelper.requireCurrentUserId();
        String reason = body != null ? (String) body.get("reason") : null;
        if (reason == null || reason.isBlank()) reason = "用户申请纠纷处理";
        SubscriptionOrder disputed = subscriptionService.disputeOrder(orderNo, reason, userId);
        Map<String, Object> data = orderToMap(disputed);
        if (auditLogService != null) {
            auditLogService.log(securityHelper.getCurrentUsername(), "USER_DISPUTE",
                    "order:" + orderNo, "SUCCESS", reason);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private Map<String, Object> planToMap(String planCode, String planName, BigDecimal price,
                                          Integer credits, Integer durationDays, SubscriptionTier tier,
                                          String category, List<String> benefits) {
        Map<String, Object> m = new HashMap<>();
        m.put("planCode", planCode);
        m.put("planName", planName);
        m.put("priceCents", price == null ? 0 : price.movePointRight(2).intValue());
        m.put("priceYuan", price);
        m.put("pointsGranted", credits);
        m.put("durationDays", durationDays);
        m.put("tier", tier.name());
        m.put("category", category);
        m.put("benefits", benefits);
        m.put("features", benefits);
        return m;
    }

    /**
     * 订单出参统一由 {@link com.qqai.service.SubscriptionOrderMapper} 生成
     * （原先本文件与 AdminOrdersController 各写一份近似复制，字段极易漂移）。
     */
    private Map<String, Object> orderToMap(SubscriptionOrder o) {
        return orderMapper.toUserView(o);
    }

    private String getClientIp(HttpServletRequest request) {
        if (request == null) return "127.0.0.1";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return (ip == null || ip.isBlank()) ? "127.0.0.1" : ip;
    }
}
