package com.qqai;

import com.qqai.entity.CreditTransaction;
import com.qqai.entity.enums.CreditDirection;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.service.CreditTransactionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 积分流水出参映射回归（企业级化批次 F）。
 *
 * 背景：`txToMap` 原先在 CreditsController / AdminOrdersController / AdminCreditsController
 * 各写一份；收敛到 {@link CreditTransactionMapper} 后，这里把"出参契约"固定下来：
 * 基础 10 个字段一个不能少（订单详情靠 relatedId 跳流水、管理端靠 adminUserId 追溯操作人），
 * 管理端多出的三个现金列在无现金信息时必须是 null（缺 key 会让前端表格列错位）。
 */
class CreditTransactionMapperTest {

    private final CreditTransactionMapper mapper = new CreditTransactionMapper();

    private CreditTransaction sampleTx() {
        CreditTransaction tx = new CreditTransaction();
        tx.setId(99L);
        tx.setUserId(25L);
        tx.setType(CreditTransactionType.AI_CHAT);
        tx.setDirection(CreditDirection.OUT);
        tx.setAmount(30);
        tx.setBalanceAfter(470);
        tx.setRemark("gpt-4/ctx:3");
        tx.setRelatedId("conv-1");
        tx.setAdminUserId(null);
        tx.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 0));
        return tx;
    }

    @Test
    @DisplayName("基础视图包含契约要求的 10 个字段，枚举输出为名称字符串")
    void baseViewHasContractFields() {
        Map<String, Object> m = mapper.toMap(sampleTx());

        assertEquals(99L, m.get("id"));
        assertEquals(25L, m.get("userId"));
        assertEquals("AI_CHAT", m.get("type"));
        assertEquals("OUT", m.get("direction"));
        assertEquals(30, m.get("amount"));
        assertEquals(470, m.get("balanceAfter"));
        assertEquals("gpt-4/ctx:3", m.get("remark"));
        assertEquals("conv-1", m.get("relatedId"));
        assertNull(m.get("adminUserId"), "管理员流水才带 adminUserId，普通消费为 null");
        assertEquals(LocalDateTime.of(2026, 9, 21, 10, 0), m.get("createdAt"));
        assertEquals(10, m.size(), "字段数量变化必须同步前端契约，实际：" + m.keySet());
    }

    @Test
    @DisplayName("管理端视图：无现金信息时三个现金列必须存在且为 null")
    void adminViewHasCashColumnsEvenWhenAbsent() {
        Map<String, Object> m = mapper.toAdminCashView(sampleTx());

        assertTrue(m.containsKey("cashAmount"), "cashAmount 必须存在（前端按 key 取列）");
        assertTrue(m.containsKey("cashCategory"));
        assertTrue(m.containsKey("cashCategoryLabel"));
        assertNull(m.get("cashAmount"));
        assertNull(m.get("cashCategory"));
        assertNull(m.get("cashCategoryLabel"));
        assertEquals(13, m.size());
    }

    @Test
    @DisplayName("管理端视图：有现金信息时原样带出（含类别文案）")
    void adminViewCopiesCashInfo() {
        Map<String, Object> cash = new HashMap<>();
        cash.put("cashAmount", "-30.00");
        cash.put("cashCategory", "MANUAL");
        cash.put("cashCategoryLabel", "手工记账");

        Map<String, Object> m = mapper.toAdminCashView(sampleTx(), cash);

        assertEquals("-30.00", m.get("cashAmount"));
        assertEquals("MANUAL", m.get("cashCategory"));
        assertEquals("手工记账", m.get("cashCategoryLabel"));
        assertEquals("conv-1", m.get("relatedId"), "现金列不影响基础字段");
    }
}
