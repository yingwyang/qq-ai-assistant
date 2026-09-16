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

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPlans() {
        Long userId = securityHelper.requireCurrentUserId();
        CreditRule rule = creditRuleService.getRule();
        Integer durationDays = rule.getPlanDurationDays();

        List<Map<String, Object>> directPlans = new ArrayList<>();
        // LITE/PRO/PROPLUS/ULTRA 从 creditRuleService 读取价格和积分
        directPlans.add(planToMap("LITE", "直购积分·" + rule.getPlanLiteCredit(), rule.getPlanLitePrice(), rule.getPlanLiteCredit(),
                durationDays, SubscriptionTier.LITE, "DIRECT",
                Arrays.asList(rule.getPlanLiteCredit() + " 积分", "基础模型支持", "标准响应速度")));
        directPlans.add(planToMap("PRO", "直购积分·" + rule.getPlanProCredit(), rule.getPlanProPrice(), rule.getPlanProCredit(),
                durationDays, SubscriptionTier.PRO, "DIRECT",
                Arrays.asList(rule.getPlanProCredit() + " 积分", "全模型支持", "优先响应", "30 天文件存储")));
        directPlans.add(planToMap("PROPLUS", "直购积分·" + rule.getPlanProPlusCredit(), rule.getPlanProPlusPrice(), rule.getPlanProPlusCredit(),
                durationDays, SubscriptionTier.PROPLUS, "DIRECT",
                Arrays.asList(rule.getPlanProPlusCredit() + " 积分", "全模型支持", "高优先级队列", "高级分析功能", "90 天文件存储")));
        directPlans.add(planToMap("ULTRA", "直购积分·" + rule.getPlanUltraCredit(), rule.getPlanUltraPrice(), rule.getPlanUltraCredit(),
                durationDays, SubscriptionTier.ULTRA, "DIRECT",
                Arrays.asList(rule.getPlanUltraCredit() + " 积分", "全模型支持", "最高优先级", "全部高级功能", "永久文件存储")));
        // MEGA 在 CreditRule 中没有对应字段，保留硬编码
        directPlans.add(planToMap("MEGA", "直购积分·100000", new BigDecimal("648"), 100000,
                30, SubscriptionTier.MEGA, "DIRECT",
                Arrays.asList("100000 积分", "全模型支持", "最高优先级", "全部高级功能", "永久文件存储", "专属客服支持")));

        List<Map<String, Object>> cardPlans = new ArrayList<>();
        // 月卡档位（SMALL_MONTH_CARD/LARGE_MONTH_CARD）在 CreditRule 中没有对应字段，保留硬编码
        cardPlans.add(planToMap("SMALL_MONTH_CARD", "小月卡", new BigDecimal("30"), 3000,
                30, SubscriptionTier.SMALL_MONTH_CARD, "MONTHLY_CARD",
                Arrays.asList("3000 积分基础", "每日签到额外 +100 积分", "基础模型支持", "专属折扣 9 折", "30 天有效")));
        cardPlans.add(planToMap("LARGE_MONTH_CARD", "大月卡", new BigDecimal("68"), 8000,
                30, SubscriptionTier.LARGE_MONTH_CARD, "MONTHLY_CARD",
                Arrays.asList("8000 积分基础", "每日签到额外 +300 积分", "全模型支持", "专属折扣 8 折", "优先响应队列", "30 天有效")));

        Map<String, Object> data = new HashMap<>();
        data.put("plans", directPlans);
        data.put("directPlans", directPlans);
        data.put("monthlyCards", cardPlans);
        data.put("groups", Arrays.asList(
                groupMap("DIRECT", "直购积分", "按档购买，立即到账", directPlans),
                groupMap("MONTHLY_CARD", "会员月卡", "30 天权益，超值更省", cardPlans)
        ));
        return ResponseEntity.ok(ApiResponse.success(data));
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
        SubscriptionOrder order = subscriptionService.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (!order.getUserId().equals(userId)) {
            throw new BizException(403, CreditErrorCode.FORBIDDEN_ORDER, "无权访问该订单");
        }
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
        SubscriptionOrder order = subscriptionService.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (!order.getUserId().equals(userId)) {
            throw new BizException(403, CreditErrorCode.FORBIDDEN_ORDER, "无权操作该订单");
        }
        String reason = body != null ? (String) body.get("reason") : null;
        SubscriptionOrder cancelled = subscriptionService.cancelOrder(orderNo, null, reason);
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

    private Map<String, Object> orderToMap(SubscriptionOrder o) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("userId", o.getUserId());
        m.put("planTier", o.getPlanTier() != null ? o.getPlanTier().name() : null);
        // 前端兼容字段
        m.put("planName", planTierToName(o.getPlanTier()));
        m.put("price", o.getPrice());
        m.put("amount", o.getPrice());
        m.put("paidAmount", o.getPrice());
        m.put("creditAmount", o.getCreditAmount());
        m.put("credits", o.getCreditAmount());
        m.put("durationDays", o.getDurationDays());
        m.put("planDurationDays", o.getDurationDays());
        m.put("status", o.getStatus() != null ? o.getStatus().name() : null);
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("paymentTransactionId", o.getPaymentTransactionId());
        m.put("paidAt", o.getPaidAt());
        m.put("validFrom", o.getPaidAt());
        m.put("expiresAt", o.getExpiresAt());
        m.put("refundedAt", o.getRefundedAt());
        m.put("refundAmount", o.getRefundAmount());
        m.put("refundReason", o.getRefundReason());
        m.put("refundStatus", refundStatusName(o.getStatus()));
        m.put("refundAdminUserId", o.getRefundAdminUserId());
        m.put("metadata", o.getMetadata());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        m.put("autoRenew", false);
        m.put("source", "WEB");
        return m;
    }

    private String planTierToName(SubscriptionTier tier) {
        if (tier == null) return "免费版";
        return switch (tier) {
            case FREE -> "免费版";
            case LITE -> "直购积分·600";
            case PRO -> "直购积分·3500";
            case PROPLUS -> "直购积分·16000";
            case ULTRA -> "直购积分·45000";
            case MEGA -> "直购积分·100000";
            case SMALL_MONTH_CARD -> "小月卡";
            case LARGE_MONTH_CARD -> "大月卡";
            case ALL -> "全功能版";
        };
    }

    private String refundStatusName(OrderStatus status) {
        if (status == null) return "";
        return switch (status) {
            case REFUNDED -> "已退款";
            case PENDING_REFUND -> "退款审批中";
            default -> "";
        };
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
