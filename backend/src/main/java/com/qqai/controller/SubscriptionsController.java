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
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPlans() {
        Long userId = securityHelper.requireCurrentUserId();
        CreditRule rule = creditRuleService.getRule();
        List<Map<String, Object>> plans = new ArrayList<>();
        plans.add(planToMap("LITE", "轻享版", rule.getPlanLitePrice(), rule.getPlanLiteCredit(),
                rule.getPlanDurationDays(), SubscriptionTier.LITE,
                Arrays.asList("2000积分/月", "基础模型支持", "标准响应速度")));
        plans.add(planToMap("PRO", "专业版", rule.getPlanProPrice(), rule.getPlanProCredit(),
                rule.getPlanDurationDays(), SubscriptionTier.PRO,
                Arrays.asList("4000积分/月", "全模型支持", "优先响应", "历史消息记忆增强")));
        plans.add(planToMap("PROPLUS", "旗舰版", rule.getPlanProPlusPrice(), rule.getPlanProPlusCredit(),
                rule.getPlanDurationDays(), SubscriptionTier.PROPLUS,
                Arrays.asList("12000积分/月", "全模型支持", "高优先级队列", "高级分析功能", "群消息AI总结")));
        plans.add(planToMap("ULTRA", "至尊版", rule.getPlanUltraPrice(), rule.getPlanUltraCredit(),
                rule.getPlanDurationDays(), SubscriptionTier.ULTRA,
                Arrays.asList("40000积分/月", "全模型支持", "最高优先级", "全部高级功能", "专属客服支持")));
        return ResponseEntity.ok(ApiResponse.success(plans));
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
        SubscriptionOrder paid = subscriptionService.markPaid(order.getOrderNo(), paymentMethod,
                "MANUAL-" + userId + "-" + System.currentTimeMillis());

        UserCredit after = creditService.getBalanceWithTier(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", paid.getOrderNo());
        data.put("status", paid.getStatus() != null ? paid.getStatus().name() : null);
        data.put("newBalance", after.getBalance());
        data.put("expiresAt", paid.getExpiresAt());
        data.put("pointsGranted", paid.getCreditAmount());

        if (auditLogService != null) {
            auditLogService.log(securityHelper.getCurrentUsername(), "SUBSCRIPTION_PURCHASE",
                    "order:" + paid.getOrderNo(), "SUCCESS",
                    "plan=" + tier + " points=" + paid.getCreditAmount() + " price=" + paid.getPrice());
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
        SubscriptionOrder order = subscriptionService.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (!order.getUserId().equals(userId)) {
            throw new BizException(403, CreditErrorCode.FORBIDDEN_ORDER, "无权操作该订单");
        }
        String reason = body != null ? (String) body.get("reason") : null;
        if (reason == null || reason.isBlank()) reason = "用户申请退款";
        log.info("用户{} 申请订单退款 orderNo={} reason={}", userId, orderNo, reason);

        SubscriptionOrder refunded = subscriptionService.refundOrder(orderNo,
                "用户申请: " + reason, null);

        int refundPoints = subscriptionService.refundedPointsLastRefund(order, 1.0);
        UserCredit after = creditService.getBalanceWithTier(userId);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", refunded.getOrderNo());
        data.put("status", refunded.getStatus() != null ? refunded.getStatus().name() : null);
        data.put("refundPoints", refundPoints);
        data.put("newBalance", after.getBalance());

        if (auditLogService != null) {
            auditLogService.log(securityHelper.getCurrentUsername(), "USER_REFUND_REQUEST",
                    "order:" + orderNo, "SUCCESS",
                    "refundPoints=" + refundPoints + " reason=" + reason);
        }
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private Map<String, Object> planToMap(String planCode, String planName, BigDecimal price,
                                          Integer credits, Integer durationDays, SubscriptionTier tier,
                                          List<String> benefits) {
        Map<String, Object> m = new HashMap<>();
        m.put("planCode", planCode);
        m.put("planName", planName);
        m.put("priceCents", price == null ? 0 : price.movePointRight(2).intValue());
        m.put("priceYuan", price);
        m.put("pointsGranted", credits);
        m.put("durationDays", durationDays);
        m.put("tier", tier.name());
        m.put("benefits", benefits);
        return m;
    }

    private Map<String, Object> orderToMap(SubscriptionOrder o) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("userId", o.getUserId());
        m.put("planTier", o.getPlanTier() != null ? o.getPlanTier().name() : null);
        m.put("price", o.getPrice());
        m.put("creditAmount", o.getCreditAmount());
        m.put("durationDays", o.getDurationDays());
        m.put("status", o.getStatus() != null ? o.getStatus().name() : null);
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("paymentTransactionId", o.getPaymentTransactionId());
        m.put("paidAt", o.getPaidAt());
        m.put("expiresAt", o.getExpiresAt());
        m.put("refundedAt", o.getRefundedAt());
        m.put("refundAmount", o.getRefundAmount());
        m.put("refundReason", o.getRefundReason());
        m.put("refundAdminUserId", o.getRefundAdminUserId());
        m.put("metadata", o.getMetadata());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        return m;
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
