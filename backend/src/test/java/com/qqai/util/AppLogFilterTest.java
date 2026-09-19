package com.qqai.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 应用日志过滤逻辑测试。
 *
 * 背景：日志以文本文件形式提供，后端只取「最近 5000 行」的窗口，然后按级别 / 关键字 /
 * 时间范围过滤。这里覆盖窗口内的过滤语义与边界。
 */
class AppLogFilterTest {

    private static final String INFO_LINE =
            "2026-09-19 10:00:00.123 [http-nio-8081-exec-1] INFO  c.qqai.controller.LogController - 读取日志成功";
    private static final String ERROR_LINE =
            "2026-09-19 11:30:00.500 [http-nio-8081-exec-2] ERROR c.qqai.service.XService - traceId=abc123 处理失败";
    private static final String WARN_LINE =
            "2026-09-19 12:00:00.000 [http-nio-8081-exec-3] WARN  c.qqai.config.SecurityConfig - 401 未登录";
    private static final String OTHER_DAY_INFO =
            "2026-09-10 09:00:00.000 [main] INFO  c.qqai.Application - 启动完成";
    private static final String STACK_LINE =
            "\tat com.qqai.service.XService.handle(XService.java:42)";

    private List<String> sample() {
        // 调用方按时间倒序传入
        return List.of(WARN_LINE, ERROR_LINE, INFO_LINE, OTHER_DAY_INFO, STACK_LINE);
    }

    @Test
    @DisplayName("level=ALL 时不过滤级别")
    void shouldNotFilterByLevelWhenAll() {
        assertEquals(5, AppLogFilter.filter(sample(), "ALL", null, null, null).size());
        assertEquals(5, AppLogFilter.filter(sample(), null, null, null, null).size());
        assertEquals(5, AppLogFilter.filter(sample(), "  ", null, null, null).size());
    }

    @Test
    @DisplayName("按级别过滤只保留对应级别")
    void shouldFilterByLevel() {
        List<Map<String, Object>> result = AppLogFilter.filter(sample(), "ERROR", null, null, null);
        assertEquals(1, result.size());
        assertEquals("ERROR", result.get(0).get("level"));
        assertEquals(ERROR_LINE, result.get(0).get("message"));
    }

    @Test
    @DisplayName("解析不出级别的行在 ERROR 过滤下靠内容兜底")
    void shouldKeepUnparsedLinesForErrorLevel() {
        List<String> lines = List.of(STACK_LINE, "something Exception happened");
        List<Map<String, Object>> result = AppLogFilter.filter(lines, "ERROR", null, null, null);
        assertEquals(1, result.size());
        assertEquals("UNKNOWN", result.get(0).get("level"));
    }

    @Test
    @DisplayName("关键字过滤忽略大小写且不区分位置")
    void shouldFilterByKeyword() {
        assertEquals(1, AppLogFilter.filter(sample(), "ALL", "TRACEID=ABC123", null, null).size());
        // 类名也是关键字：只有 SecurityConfig 那一行命中
        assertEquals(1, AppLogFilter.filter(sample(), "ALL", "securityconfig", null, null).size());
        // 所有样例行的 logger 名都含 qqai，所以命中全部
        assertEquals(5, AppLogFilter.filter(sample(), "ALL", "qqai", null, null).size());
        assertEquals(0, AppLogFilter.filter(sample(), "ALL", "不存在的关键字", null, null).size());
    }

    @Test
    @DisplayName("时间范围按天过滤且含首尾当天")
    void shouldFilterByDateRange() {
        assertEquals(3, AppLogFilter.filter(sample(), "ALL", null, "2026-09-19", "2026-09-19").size());
        assertEquals(1, AppLogFilter.filter(sample(), "ALL", null, "2026-09-10", "2026-09-10").size());
        assertEquals(3, AppLogFilter.filter(sample(), "ALL", null, "2026-09-19", null).size());
        assertEquals(1, AppLogFilter.filter(sample(), "ALL", null, null, "2026-09-10").size());
        assertEquals(4, AppLogFilter.filter(sample(), "ALL", null, "2026-09-10", "2026-09-19").size());
    }

    @Test
    @DisplayName("带时间条件时排除无法解析时间戳的堆栈续行")
    void shouldDropUnparsableLinesWhenDateFilterPresent() {
        // 无时间条件时堆栈行保留
        assertEquals(5, AppLogFilter.filter(sample(), "ALL", null, null, null).size());
        // 有时间条件时堆栈行无法归属到某一天，被排除
        assertEquals(4, AppLogFilter.filter(sample(), "ALL", null, "2026-09-01", "2026-09-30").size());
    }

    @Test
    @DisplayName("非法日期参数被忽略而不是抛异常")
    void shouldIgnoreInvalidDates() {
        assertEquals(5, AppLogFilter.filter(sample(), "ALL", null, "not-a-date", "2026/09/19").size());
        assertNull(AppLogFilter.parseDate(""));
        assertNull(AppLogFilter.parseDate("2026-13-45"));
    }

    @Test
    @DisplayName("时间戳解析：logback 多行格式与 ISO 风格都能识别")
    void shouldParseTimestamps() {
        assertEquals(LocalDateTime.of(2026, 9, 19, 10, 0, 0), AppLogFilter.extractTimestamp(INFO_LINE));
        assertEquals(LocalDateTime.of(2026, 9, 19, 11, 30, 0), AppLogFilter.extractTimestamp(ERROR_LINE));
        assertNull(AppLogFilter.extractTimestamp(STACK_LINE));
        assertNull(AppLogFilter.extractTimestamp(null));
    }

    @Test
    @DisplayName("null 与空列表安全返回")
    void shouldHandleEmptyInput() {
        assertTrue(AppLogFilter.filter(null, "ALL", null, null, null).isEmpty());
        assertTrue(AppLogFilter.filter(List.of(), "ALL", null, null, null).isEmpty());
        assertNull(AppLogFilter.extractLevel(null));
    }

    @Test
    @DisplayName("起始/结束边界：结束日期覆盖当天 23:59:59")
    void shouldCoverWholeEndDay() {
        LocalDateTime end = AppLogFilter.parseEnd("2026-09-19");
        assertEquals(LocalDateTime.of(2026, 9, 19, 23, 59, 59, 999_999_999), end);
        LocalDateTime start = AppLogFilter.parseStart("2026-09-19");
        assertEquals(LocalDateTime.of(2026, 9, 19, 0, 0, 0), start);
    }
}
