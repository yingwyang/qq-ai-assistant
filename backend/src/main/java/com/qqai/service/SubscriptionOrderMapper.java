package com.qqai.service;

import com.qqai.entity.CreditRule;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 订阅订单的出参映射（用户端 / 管理端共用一份实现）。
 *
 * 为什么抽出来：原先 `orderToMap` + `planTierToName` + `refundStatusName` 在
 * `SubscriptionsController` 与 `AdminOrdersController` 里各写一遍（共三处近似复制），
 * 改一个字段要改多处、且极易漂移（例如管理端多返回 disputeReason 而用户端漏掉）。
 *
 * 套餐名动态化：不再硬编码「直购积分·600」，而是读「规则配置」里的 plan*Credit，
 * 管理员在后台改了积分值，页面上的套餐名与实发积分始终一致。
 */
@Component
public class SubscriptionOrderMapper {

    private final CreditRuleService creditRuleService;

    public SubscriptionOrderMapper(CreditRuleService creditRuleService) {
        this.creditRuleService = creditRuleService;
    }

    /** 用户端订单视图：不含管理员专属字段（客户端 IP / UA / 元数据明细） */
    public Map<String, Object> toUserView(SubscriptionOrder order) {
        return base(order, false);
    }

    /** 管理端订单视图：在用户端字段基础上追加排查用字段 */
    public Map<String, Object> toAdminView(SubscriptionOrder order) {
        return base(order, true);
    }

    private Map<String, Object> base(SubscriptionOrder o, boolean adminView) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("userId", o.getUserId());
        m.put("planTier", o.getPlanTier() != null ? o.getPlanTier().name() : null);
        // 前端兼容字段
        m.put("planName", planTierName(o.getPlanTier()));
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

        if (adminView) {
            m.put("disputeReason", o.getDisputeReason());
            // 从 metadata 解析申请时间等扩展字段
            String meta = o.getMetadata();
            if (meta != null && !meta.isEmpty()) {
                m.put("refundRequestedAt", extractMeta(meta, "refundRequestedAt"));
                m.put("disputedAt", extractMeta(meta, "disputedAt"));
                m.put("refundRejectReason", extractMeta(meta, "refundRejectReason"));
            }
            m.put("clientIp", o.getClientIp());
            m.put("userAgent", o.getUserAgent());
        }
        return m;
    }

    /**
     * 套餐展示名：直购积分读「规则配置」的 plan*Credit（改配置即改展示，不再硬编码）。
     * 规则读取失败或无配置时退回不带数字的名称，保证出参永不为 null。
     */
    public String planTierName(SubscriptionTier tier) {
        if (tier == null) {
            return "免费版";
        }
        return switch (tier) {
            case FREE -> "免费版";
            case LITE -> directPointsName("planLiteCredit", 2000);
            case PRO -> directPointsName("planProCredit", 4000);
            case PROPLUS -> directPointsName("planProPlusCredit", 12000);
            case ULTRA -> directPointsName("planUltraCredit", 40000);
            case MEGA -> directPointsName("planMegaCredit", 100000);
            case SMALL_MONTH_CARD -> "小月卡";
            case LARGE_MONTH_CARD -> "大月卡";
            case ALL -> "全功能版";
        };
    }

    private String directPointsName(String fieldName, int fallbackCredits) {
        Integer credits = null;
        try {
            CreditRule rule = creditRuleService.getSafeRule();
            if (rule != null) {
                credits = switch (fieldName) {
                    case "planLiteCredit" -> rule.getPlanLiteCredit();
                    case "planProCredit" -> rule.getPlanProCredit();
                    case "planProPlusCredit" -> rule.getPlanProPlusCredit();
                    case "planUltraCredit" -> rule.getPlanUltraCredit();
                    case "planMegaCredit" -> rule.getPlanMegaCredit();
                    default -> null;
                };
            }
        } catch (Exception e) {
            // 规则读取失败不应影响订单出参，退回默认积分值（下单时的实发积分仍以规则为准）
            credits = null;
        }
        int value = (credits != null && credits > 0) ? credits : fallbackCredits;
        return "直购积分·" + value;
    }

    /** 退款状态展示：仅退款相关状态有文案，其余为空（与前端约定一致） */
    public String refundStatusName(OrderStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case REFUNDED -> "已退款";
            case PENDING_REFUND -> "退款审批中";
            default -> "";
        };
    }

    /** 从 metadata（key=value;key=value）里取一个键的值 */
    private String extractMeta(String meta, String key) {
        for (String part : meta.split(";")) {
            String trimmed = part.trim();
            int idx = trimmed.indexOf('=');
            if (idx > 0 && trimmed.substring(0, idx).equals(key)) {
                return trimmed.substring(idx + 1);
            }
        }
        return null;
    }
}
