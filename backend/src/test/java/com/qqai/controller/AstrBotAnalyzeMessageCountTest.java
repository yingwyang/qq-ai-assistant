package com.qqai.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 分析的取样条数解析测试。
 *
 * <p>背景：{@code /api/astrbot/analyze} 声明了 {@code messageCount} 却从未使用，
 * 实际按「整群历史」加载，大群会 OOM。现在该参数真正生效并收敛到 [1, 200]，
 * 且要兼容客户端传数字或数字字符串。</p>
 */
class AstrBotAnalyzeMessageCountTest {

    @Test
    @DisplayName("缺省 / null / 非法值 → 用默认 50")
    void fallsBackToDefault() {
        assertEquals(50, AstrBotController.resolveAnalyzeMessageCount(null));
        assertEquals(50, AstrBotController.resolveAnalyzeMessageCount(""));
        assertEquals(50, AstrBotController.resolveAnalyzeMessageCount("  "));
        assertEquals(50, AstrBotController.resolveAnalyzeMessageCount("abc"));
        assertEquals(50, AstrBotController.resolveAnalyzeMessageCount(new Object()));
    }

    @Test
    @DisplayName("正常值：数字与数字字符串都接受")
    void acceptsNumberAndNumericString() {
        assertEquals(20, AstrBotController.resolveAnalyzeMessageCount(20));
        assertEquals(20, AstrBotController.resolveAnalyzeMessageCount("20"));
        assertEquals(20, AstrBotController.resolveAnalyzeMessageCount(" 20 "));
        assertEquals(20, AstrBotController.resolveAnalyzeMessageCount(20L));
    }

    @Test
    @DisplayName("越界收敛：>200 截到 200，<=0 抬到 1（不允许 0 条或超大值进内存）")
    void clampsOutOfRangeValues() {
        assertEquals(200, AstrBotController.resolveAnalyzeMessageCount(201));
        assertEquals(200, AstrBotController.resolveAnalyzeMessageCount(Integer.MAX_VALUE));
        assertEquals(200, AstrBotController.resolveAnalyzeMessageCount("99999"));
        assertEquals(1, AstrBotController.resolveAnalyzeMessageCount(0));
        assertEquals(1, AstrBotController.resolveAnalyzeMessageCount(-5));
    }
}
