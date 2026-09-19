package com.qqai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 聚合结果日期归一化测试。
 *
 * 这个类的存在意义就是「不要对聚合查询的日期列做硬转型」：
 * 同一条 SQL 在不同数据库/方言下可能回来 String / java.sql.Date / LocalDate / LocalDateTime。
 */
class DateKeysTest {

    @Test
    @DisplayName("字符串（MySQL DATE_FORMAT 的返回值）能归一化")
    void handlesString() {
        assertEquals("2026-09-19", DateKeys.toIsoDate("2026-09-19"));
        assertEquals("2026-09-19", DateKeys.toIsoDate("2026-09-19 23:59:59"));
        assertEquals("2026-09-19", DateKeys.toIsoDate("2026-09-19T23:59:59.123"));
        assertEquals("2026-09-19", DateKeys.toIsoDate("  2026-09-19  "));
    }

    @Test
    @DisplayName("日期/时间类型都能归一化")
    void handlesTemporalTypes() {
        assertEquals("2026-09-19", DateKeys.toIsoDate(LocalDate.of(2026, 9, 19)));
        assertEquals("2026-09-19", DateKeys.toIsoDate(LocalDateTime.of(2026, 9, 19, 22, 30)));
        assertEquals("2026-09-19", DateKeys.toIsoDate(java.sql.Date.valueOf("2026-09-19")));
        assertEquals("2026-09-19", DateKeys.toIsoDate(java.sql.Timestamp.valueOf("2026-09-19 22:30:00")));
        assertEquals("2026-09-19", DateKeys.toIsoDate(java.util.Date.from(
                LocalDate.of(2026, 9, 19).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())));
    }

    @Test
    @DisplayName("无法识别时返回 null 而不是抛异常")
    void returnsNullForUnknownValues() {
        assertNull(DateKeys.toIsoDate(null));
        assertNull(DateKeys.toIsoDate(""));
        assertNull(DateKeys.toIsoDate("   "));
        assertNull(DateKeys.toIsoDate("09-19"));            // 只有月日，年份不明，不猜
        assertNull(DateKeys.toIsoDate("not-a-date"));
        assertNull(DateKeys.toIsoDate(12345));              // 数字日期不猜语义
    }

    @Test
    @DisplayName("MM-dd 输出用于图表横轴")
    void toMonthDay() {
        assertEquals("09-19", DateKeys.toMonthDay("2026-09-19"));
        assertEquals("01-01", DateKeys.toMonthDay(LocalDate.of(2027, 1, 1)));
        assertNull(DateKeys.toMonthDay("09-19"));
        assertNull(DateKeys.toMonthDay(null));
    }
}
