package com.qqai;

import com.qqai.repository.AstrBotMessageRepository;
import com.qqai.service.DashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 对话趋势统计回归测试。
 *
 * <p>历史 bug：{@code DashboardService.getAiTrend} 把原生 SQL
 * {@code DATE_FORMAT(time_created, '%Y-%m-%d')} 的返回值硬转成 {@code java.sql.Date}，
 * 而 MySQL 返回的是字符串 → ClassCastException → 被空 catch 吞掉 → 图表 7 天全是 0，
 * 同一页的「AI 消息总数」（走 COUNT 查询）却是对的。</p>
 *
 * <p>本测试直接打真实仓储与真实 Service，确保聚合结果的类型处理不会再退化：
 * 只要有人把归一化逻辑改回硬转型，这里就会红。</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminAiTrendTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private AstrBotMessageRepository astrBotMessageRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

    @BeforeEach
    void setUp() {
        astrBotMessageRepository.deleteAll();
    }

    /**
     * 用 JDBC 直接插入指定 time_created 的记录。
     *
     * 不能走 repository.save()：AstrBotMessage 的 @PrePersist 会把 timeCreated 无条件改写成
     * LocalDateTime.now()，那样所有测试数据都会落在「今天」，趋势按天分布就无从验证。
     */
    private void saveMessage(String messageId, LocalDateTime timeCreated) {
        jdbcTemplate.update(
                "INSERT INTO astrbot_messages (message_id, conversation_id, role, content, time_created, time_updated) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                messageId, "conv-1", "USER", "hi", timeCreated, timeCreated);
    }

    /** 查某一天的 count 值 */
    private long countOf(List<Map<String, Object>> trend, LocalDate date) {
        String label = date.format(fmt);
        return trend.stream()
                .filter(item -> label.equals(item.get("date")))
                .map(item -> ((Number) item.get("count")).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("趋势里没有 " + label + " 这一天: " + trend));
    }

    @Test
    @DisplayName("7 天趋势按天给出真实条数，而不是一片 0")
    void trendCarriesRealCounts() {
        LocalDate today = LocalDate.now();
        saveMessage("m-1", today.minusDays(6).atTime(10, 0));
        saveMessage("m-2", today.minusDays(6).atTime(22, 30));
        saveMessage("m-3", today.minusDays(3).atTime(9, 15));
        saveMessage("m-4", today.atTime(8, 0));

        List<Map<String, Object>> trend = dashboardService.getAiTrend(7);

        assertEquals(7, trend.size(), "近 7 天应固定返回 7 个点");
        assertEquals(2, countOf(trend, today.minusDays(6)));
        assertEquals(0, countOf(trend, today.minusDays(5)), "没有数据的那天补 0");
        assertEquals(1, countOf(trend, today.minusDays(3)));
        assertEquals(1, countOf(trend, today));
        long total = trend.stream().mapToLong(i -> ((Number) i.get("count")).longValue()).sum();
        assertEquals(4, total, "趋势合计应等于插入条数");
    }

    @Test
    @DisplayName("区间外的数据不计入，边界（当天 00:00）算当天")
    void respectsRangeBoundaries() {
        LocalDate today = LocalDate.now();
        saveMessage("out-1", today.minusDays(10).atTime(12, 0));   // 超出 7 天窗口
        saveMessage("edge-1", today.atStartOfDay());               // 当天 00:00 应算当天
        saveMessage("future-1", today.plusDays(1).atTime(12, 0));  // 明天，超出窗口

        List<Map<String, Object>> trend = dashboardService.getAiTrend(7);
        long total = trend.stream().mapToLong(i -> ((Number) i.get("count")).longValue()).sum();
        assertEquals(1, total);
        assertEquals(1, countOf(trend, today));
    }

    @Test
    @DisplayName("30 天范围返回 30 个点且合计正确")
    void supportsLongerRanges() {
        LocalDate today = LocalDate.now();
        saveMessage("d-1", today.minusDays(29).atTime(1, 0));
        saveMessage("d-2", today.minusDays(15).atTime(1, 0));
        saveMessage("d-3", today.minusDays(15).atTime(23, 0));

        List<Map<String, Object>> trend = dashboardService.getAiTrend(30);
        assertEquals(30, trend.size());
        assertEquals(1, countOf(trend, today.minusDays(29)));
        assertEquals(2, countOf(trend, today.minusDays(15)));
        assertEquals(3, trend.stream().mapToLong(i -> ((Number) i.get("count")).longValue()).sum());
    }

    @Test
    @DisplayName("没有任何数据时返回全 0 序列而不是报错")
    void emptyDataReturnsZeroSeries() {
        List<Map<String, Object>> trend = dashboardService.getAiTrend(7);
        assertEquals(7, trend.size());
        assertTrue(trend.stream().allMatch(i -> ((Number) i.get("count")).longValue() == 0));
    }

    @Test
    @DisplayName("原生聚合查询的日期列确实不是 java.sql.Date（说明必须做归一化）")
    void nativeAggregationReturnsNonDateColumn() {
        LocalDate today = LocalDate.now();
        saveMessage("t-1", today.atTime(10, 0));

        List<Object[]> rows = astrBotMessageRepository.countDailyBetween(
                today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());
        assertFalse(rows.isEmpty(), "聚合查询应返回一行");
        Object dayColumn = rows.get(0)[0];
        assertNotNull(dayColumn);
        // 这里刻意不断言具体类型（不同数据库/方言可能不同），只断言「能归一化出正确日期」；
        // 如果哪天有人改回 (java.sql.Date) 强转，getAiTrend 的测试会先失败。
        assertEquals(today.toString(), com.qqai.util.DateKeys.toIsoDate(dayColumn));
    }

    @Test
    @DisplayName("用户级 AI 趋势的聚合查询同样可归一化成 MM-dd（客户端同款查询）")
    void conversationScopedAggregationIsNormalizable() {
        LocalDate today = LocalDate.now();
        saveMessage("c-1", today.minusDays(2).atTime(9, 0));
        saveMessage("c-2", today.minusDays(2).atTime(20, 0));

        List<Object[]> rows = astrBotMessageRepository.countDailyByConversationIdInAndTimeCreatedBetween(
                List.of("conv-1"), today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());
        assertEquals(1, rows.size(), "同一天应聚合成一行");
        assertEquals(today.minusDays(2).format(fmt), com.qqai.util.DateKeys.toMonthDay(rows.get(0)[0]));
        assertEquals(2L, ((Number) rows.get(0)[1]).longValue());
    }
}
