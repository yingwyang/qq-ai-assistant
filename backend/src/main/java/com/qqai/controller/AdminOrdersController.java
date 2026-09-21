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
import com.qqai.repository.SubscriptionOrderRepository;
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

    /** 订单出参映射（与用户端 SubscriptionsController 共用同一实现） */
    @Autowired
    private com.qqai.service.SubscriptionOrderMapper orderMapper;

    /** 流水出参映射（与积分页/资金流水共用同一实现） */
    @Autowired
    private com.qqai.service.CreditTransactionMapper txMapper;

    @Autowired
    private CreditService creditService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionOrderRepository subscriptionOrderRepository;

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
    public ApiResponse<Map<String, Object>> manualCreate(
            @RequestBody @jakarta.validation.Valid com.qqai.dto.admin.ManualCreateOrderRequest req) {
        Long adminUserId = securityHelper.requireAdminUserId();

        Long userId = req.userId();
        if (!userRepository.existsById(userId)) throw new BizException("目标用户不存在");

        SubscriptionTier tier = subscriptionService.planCodeToTier(req.planCode());
        BigDecimal priceOverride = req.priceCents() != null
                ? new BigDecimal(req.priceCents().toString()).movePointLeft(2) : null;
        Integer creditOverride = req.pointsGranted();
        Integer durationOverride = req.durationDays();

        SubscriptionOrder order = subscriptionService.manualCreateOrder(
                adminUserId, userId, tier, priceOverride, creditOverride, durationOverride,
                req.orderNo(), req.remark());

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

    /**
     * 管理员确认支付：将 PENDING 订单标记为 PAID，发放权益。
     */
    @PostMapping("/orders/{orderNo}/approve-payment")
    public ApiResponse<Map<String, Object>> approvePayment(@PathVariable String orderNo) {
        Long adminUserId = securityHelper.requireAdminUserId();
        SubscriptionOrder paid = subscriptionService.markPaid(orderNo, "MANUAL",
                "ADMIN-" + adminUserId + "-" + System.currentTimeMillis());

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", paid.getOrderNo());
        data.put("status", paid.getStatus() != null ? paid.getStatus().name() : null);
        data.put("expiresAt", paid.getExpiresAt());
        data.put("pointsGranted", paid.getCreditAmount());

        String adminUsername = securityHelper.getCurrentUsername();
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "ADMIN_APPROVE_PAYMENT",
                    "order:" + orderNo, "SUCCESS",
                    "adminUserId=" + adminUserId + " plan=" + paid.getPlanTier()
                    + " points=" + paid.getCreditAmount() + " price=" + paid.getPrice());
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

    @PostMapping("/orders/{orderNo}/resolve-dispute")
    public ApiResponse<Map<String, Object>> resolveDispute(@PathVariable String orderNo,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        Long adminUserId = securityHelper.requireAdminUserId();
        boolean agree = body != null && Boolean.TRUE.equals(body.get("agree"));
        String reason = body != null ? (String) body.get("reason") : null;
        if (reason == null || reason.isBlank()) reason = agree ? "管理员同意退款" : "管理员驳回纠纷";

        SubscriptionOrder order = subscriptionService.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));

        if (agree) {
            int refundPoints = subscriptionService.refundedPointsLastRefund(order, 1.0);
            subscriptionService.resolveDispute(orderNo, true, reason, adminUserId);
            UserCredit after = creditService.getBalanceWithTier(order.getUserId());
            Map<String, Object> data = new HashMap<>();
            data.put("orderNo", orderNo);
            data.put("refundPoints", refundPoints);
            data.put("newBalance", after.getBalance());
            data.put("status", "REFUNDED");
            String adminUsername = securityHelper.getCurrentUsername();
            if (auditLogService != null) {
                auditLogService.log(adminUsername, "ADMIN_RESOLVE_DISPUTE_REFUND",
                        "order:" + orderNo, "SUCCESS", "refundPoints=" + refundPoints + " reason=" + reason);
            }
            return ApiResponse.success(data);
        } else {
            SubscriptionOrder resolved = subscriptionService.resolveDispute(orderNo, false, reason, adminUserId);
            Map<String, Object> data = new HashMap<>();
            data.put("orderNo", orderNo);
            data.put("status", resolved.getStatus() != null ? resolved.getStatus().name() : null);
            String adminUsername = securityHelper.getCurrentUsername();
            if (auditLogService != null) {
                auditLogService.log(adminUsername, "ADMIN_RESOLVE_DISPUTE_REJECT",
                        "order:" + orderNo, "SUCCESS", reason);
            }
            return ApiResponse.success(data);
        }
    }

    @PostMapping("/orders/{orderNo}/approve-refund")
    public ApiResponse<Map<String, Object>> approveRefund(@PathVariable String orderNo,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        Long adminUserId = securityHelper.requireAdminUserId();
        String reason = body != null ? (String) body.get("reason") : null;
        if (reason == null || reason.isBlank()) reason = "管理员同意退款";

        SubscriptionOrder order = subscriptionService.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, CreditErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo));
        if (order.getStatus() != OrderStatus.PENDING_REFUND) {
            throw new BizException(400, CreditErrorCode.ORDER_STATUS_INVALID,
                    "订单状态" + order.getStatus() + "不是退款待审批，无法审批通过");
        }

        int refundPoints = subscriptionService.refundedPointsLastRefund(order, 1.0);
        subscriptionService.refundOrderWithRatio(orderNo, "退款审批通过: " + reason, adminUserId, 1.0);
        UserCredit after = creditService.getBalanceWithTier(order.getUserId());

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", orderNo);
        data.put("status", "REFUNDED");
        data.put("refundPoints", refundPoints);
        data.put("newBalance", after.getBalance());

        String adminUsername = securityHelper.getCurrentUsername();
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "ADMIN_APPROVE_REFUND",
                    "order:" + orderNo, "SUCCESS",
                    "refundPoints=" + refundPoints + " reason=" + reason);
        }
        return ApiResponse.success(data);
    }

    @PostMapping("/orders/{orderNo}/reject-refund")
    public ApiResponse<Map<String, Object>> rejectRefund(@PathVariable String orderNo,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        Long adminUserId = securityHelper.requireAdminUserId();
        String reason = body != null ? (String) body.get("reason") : null;
        if (reason == null || reason.isBlank()) reason = "管理员驳回退款申请";

        SubscriptionOrder rejected = subscriptionService.rejectRefund(orderNo, reason, adminUserId);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", orderNo);
        data.put("status", rejected.getStatus() != null ? rejected.getStatus().name() : null);

        String adminUsername = securityHelper.getCurrentUsername();
        if (auditLogService != null) {
            auditLogService.log(adminUsername, "ADMIN_REJECT_REFUND",
                    "order:" + orderNo, "SUCCESS", reason);
        }
        return ApiResponse.success(data);
    }

    @GetMapping("/orders/pending-count")
    public ApiResponse<Map<String, Object>> pendingCount() {
        securityHelper.requireAdmin();
        long pendingRefunds = subscriptionOrderRepository.countByStatus(OrderStatus.PENDING_REFUND);
        long pendingDisputes = subscriptionOrderRepository.countByStatus(OrderStatus.DISPUTED);
        Map<String, Object> data = new HashMap<>();
        // 双写字段：pendingRefundCount/pendingDisputeCount 为前端约定主字段，其他为兼容
        data.put("pendingRefundCount", pendingRefunds);
        data.put("pendingDisputeCount", pendingDisputes);
        data.put("pendingRefunds", pendingRefunds);
        data.put("pendingDisputes", pendingDisputes);
        return ApiResponse.success(data);
    }

    /**
     * 订单出参统一由 {@link com.qqai.service.SubscriptionOrderMapper} 生成（管理端视图），
     * 与用户端共用同一实现，避免同名字段两边不一致。
     */
    private Map<String, Object> orderToMap(SubscriptionOrder o) {
        return orderMapper.toAdminView(o);
    }

    /** 流水出参统一由 {@link com.qqai.service.CreditTransactionMapper} 生成（与积分页共用一份） */
    private Map<String, Object> txToMap(CreditTransaction tx) {
        return txMapper.toMap(tx);
    }

    private LocalDateTime parseStart(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            // 解析失败按"无起始时间"处理，但要留下线索（原先静默吞掉，排查时无从下手）
            log.warn("订单查询起始时间解析失败，按不限制处理: value={}", s);
            return null;
        }
    }

    private LocalDateTime parseEnd(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            if (s.length() <= 10) return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE).atTime(LocalTime.MAX);
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("订单查询结束时间解析失败，按不限制处理: value={}", s);
            return null;
        }
    }
}
