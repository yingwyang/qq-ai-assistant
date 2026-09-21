package com.qqai.service;

import com.qqai.entity.CreditTransaction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 积分流水出参映射（用户端 / 管理端共用一份实现）。
 *
 * 为什么抽出来：`txToMap` 原先在 `CreditsController`、`AdminOrdersController`、
 * `AdminCreditsController` 各写一份，10 个字段逐一重复；任何字段调整都要三处同步，
 * 极易漂移（例如订单详情漏掉 relatedId 就再也跳不到流水）。
 *
 * 管理端资金流水比用户端多三列"模拟现金"（金额/类别/类别文案），没有现金信息时
 * 显式置 null（前端表格与图表都按这三个 key 取值，缺 key 会导致列错位）。
 */
@Component
public class CreditTransactionMapper {

    /** 基础流水视图：用户端「我的积分」、订单详情「关联流水」共用 */
    public Map<String, Object> toMap(CreditTransaction tx) {
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

    /** 管理端资金流水视图：基础字段 + 模拟现金三列（cashInfo 为空则三列显式 null） */
    public Map<String, Object> toAdminCashView(CreditTransaction tx, Map<String, Object> cashInfo) {
        Map<String, Object> m = toMap(tx);
        m.put("cashAmount", cashInfo == null ? null : cashInfo.get("cashAmount"));
        m.put("cashCategory", cashInfo == null ? null : cashInfo.get("cashCategory"));
        m.put("cashCategoryLabel", cashInfo == null ? null : cashInfo.get("cashCategoryLabel"));
        return m;
    }

    /** 兼容旧调用（导出等只需积分字段的场景） */
    public Map<String, Object> toAdminCashView(CreditTransaction tx) {
        return toAdminCashView(tx, null);
    }
}
