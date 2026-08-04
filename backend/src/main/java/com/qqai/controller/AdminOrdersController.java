package com.qqai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.CreditTransaction;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.repository.UserRepository;
import com.qqai.service.AuditLogService;
import com.qqai.service.CreditService;
import com.qqai.service.SubscriptionService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/credits/admin")
public class AdminOrdersController {

    private static final Logger log = LoggerFactory.getLogger(AdminOrdersController.class);

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private CreditService creditService;

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> searchOrders(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        securityHelper.requireAdmin();
        List<OrderStatus> statuses = subscriptionService.parseStatuses(status);
        LocalDateTime startDt = parseStart(start);
        LocalDateTime endDt = parseEnd(end);
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));

        Page<SubscriptionOrder> orderPage = subscriptionService.pageAdminAdvanced(
                orderNo, keyword, statuses, startDt, endDt, minPrice, maxPrice, pageable);

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
        return ApiResponse.success(pageData);
    }

    @GetMapping("/orders/export")
    public void exportOrders(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            HttpServletResponse response) throws Exception {
        securityHelper.requireAdmin();
        List<OrderStatus> statuses = subscriptionService.parseStatuses(status);
        LocalDateTime startDt = parseStart(start);
        LocalDateTime endDt = parseEnd(end);

        List<SubscriptionOrder> all = subscriptionService.listAllAdminAdvanced(
                orderNo, keyword, statuses, startDt, endDt, minPrice, maxPrice);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SubscriptionOrder o : all) rows.add(orderToMap(o));

        String filename = "subscription-orders-" + LocalDate.now() + ".json";
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(response.getOutputStream(), rows);
    }

    @PostMapping("/orders/manual-create")
    public ApiResponse<Map<String, Object>> manualCreate(@RequestBody Map<String, Object> body) {
        Long adminUserId = securityHelper.requireAdminUserId();
        Number userIdNum = (Number) body.get("userId");
        String planCode = (String) body.get("planCode");
        Number priceNum = body.get("priceCents") != null ? (Number) body.get("priceCents") : (Number) body.get("priceCentsOverride");
        Number creditNum = body.get("pointsGranted") != null ? (Number) body.get("pointsGranted") : (Number) body.get("creditOverride");
        Number durationNum = body.get("durationDays") != null ? (Number) body.get("durationDays") : (Number) body.get("durationOverride");
        String orderNoOverride = (String) body.get("orderNo");
        String remark = (String) body.get("remark");

        if (userIdNum == null) throw new BizException("userId不能为空");
        Long userId = userIdNum.longValue();
        if (!userRepository.existsById(userId)) throw new BizException("目标用户不存在");

        SubscriptionTier tier = subscriptionService.planCodeToTier(planCode);
        BigDecimal priceOverride = null;
        if (priceNum != null) {
            priceOverride = new BigDecimal(priceNum.toString()).movePointLeft(2);
        }
        Integer creditOverride = creditNum != null ? creditNum.intValue() : null;
        Integer durationOverride = durationNum != null ? durationNum.intValue() : null;

        SubscriptionOrder order = subscriptionService.manualCreateOrder(
                adminUserId, userId, tier, priceOverride, creditOverride, durationOverride, orderNoOverride, remark);

        Map<String, Object> data = orderToMap(order);
        String adminUsername = securityHelper.getCurrentUsername();
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "ADMIN_MANUAL_ORDER",
                    "order:" + order.getOrderNo(), "SUCCESS",
                    "user=" + userId + " plan=" + tier + " credit=" + order.getCreditAmount());
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<Map<String, Object>> getOrder(@PathVariable String orderNo) {
        securityHelper.requireAdmin();
        SubscriptionOrder order = subscriptionService.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        Map<String, Object> data = orderToMap(order);
        List<CreditTransaction> relatedTxs = creditService.findRelatedTransactions(orderNo);
        String refundRelatedId = "REFUND-" + orderNo;
        List<CreditTransaction> refundTxs = creditService.findRelatedTransactions(refundRelatedId);
        List<Map<String, Object>> txs = new ArrayList<>();
        for (CreditTransaction tx : relatedTxs) txs.add(txToMap(tx));
        for (CreditTransaction tx : refundTxs) txs.add(txToMap(tx));
        data.put("relatedTransactions", txs);
        return ApiResponse.success(data);
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String orderNo,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Long adminUserId = securityHelper.requireAdminUserId();
        String reason = body != null ? (String) body.get("reason") : null;
        SubscriptionOrder cancelled = subscriptionService.cancelOrder(orderNo, adminUserId, reason);
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", cancelled.getOrderNo());
        data.put("status", cancelled.getStatus() != null ? cancelled.getStatus().name() : null);
        String adminUsername = securityHelper.getCurrentUsername();
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "ADMIN_CANCEL_ORDER",
                    "order:" + orderNo, "SUCCESS", reason == null ? "" : reason);
        }
        return ApiResponse.success(data);
    }

    @PostMapping("/orders/{orderNo}/refund")
    public ApiResponse<Map<String, Object>> refund(@PathVariable String orderNo,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Long adminUserId = securityHelper.requireAdminUserId();
        String reason = body != null ? (String) body.get("reason") : null;
        double ratio = 1.0;
        if (body != null && body.get("refundRatio") != null) {
            Number r = (Number) body.get("refundRatio");
            ratio = r.doubleValue();
        }
        if (reason == null || reason.isBlank()) reason = "管理员强制退款";

        SubscriptionOrder order = subscriptionService.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        int refundPoints = subscriptionService.refundedPointsLastRefund(order, ratio);

        subscriptionService.refundOrderWithRatio(orderNo, reason, adminUserId, ratio);
        UserCredit after = creditService.getBalanceWithTier(order.getUserId());

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", orderNo);
        data.put("refundPoints", refundPoints);
        data.put("newBalance", after.getBalance());

        String adminUsername = securityHelper.getCurrentUsername();
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "ADMIN_REFUND_ORDER",
                    "order:" + orderNo, "SUCCESS",
                    "refundPoints=" + refundPoints + " ratio=" + ratio + " reason=" + reason);
        }
        return ApiResponse.success(data);
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
        m.put("clientIp", o.getClientIp());
        m.put("userAgent", o.getUserAgent());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        return m;
    }

    private Map<String, Object> txToMap(CreditTransaction tx) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", tx.getId());
        m.put("userId", tx.getUserId());
        m.put("type", tx.getType() != null ? tx.getType().name() : null);
        m.put("direction", tx.getDirection() != null ? tx.getDirection().name() : null);
        m.put("amount", tx.getAmount());
        m.put("balanceAfter", tx.getBalanceAfter());
        m.put("remark", tx.getRemark());
        m.put("relatedId", tx.getRelatedId());
        m.put("adminUserId", tx.getAdminUserId());
        m.put("createdAt", tx.getCreatedAt());
        return m;
    }

    private LocalDateTime parseStart(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) { return null; }
    }

    private LocalDateTime parseEnd(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atTime(LocalTime.MAX);
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) { return null; }
    }
}
