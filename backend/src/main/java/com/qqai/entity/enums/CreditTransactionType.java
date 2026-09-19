package com.qqai.entity.enums;

public enum CreditTransactionType {
    NEW_USER_BONUS,
    SIGN_IN,
    AI_CONSUMPTION,
    AI_CHAT,
    AI_ANALYZE,
    TTS_SYNTHESIS,
    SUBSCRIPTION_PURCHASE,
    REFUND,
    ADMIN_GRANT,
    ADMIN_DEDUCT,
    EXPIRE,
    MONTHLY_CARD_DAILY,

    /** 模拟现金记账：管理员手工记一笔现金收入 */
    CASH_INCOME,

    /** 模拟现金记账：管理员手工记一笔现金支出 */
    CASH_EXPENSE
}
