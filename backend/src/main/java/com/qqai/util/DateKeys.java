package com.qqai.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 原生 SQL 聚合结果的日期归一化工具。
 *
 * <p><b>为什么需要它</b>：MySQL 的 {@code DATE_FORMAT(col, '%Y-%m-%d')} 返回的是
 * <b>字符串</b>，而 {@code DATE(col)} 在 Hibernate 下可能返回 {@code java.sql.Date}
 * 或 {@code java.time.LocalDate}。过去管理员端「AI 对话趋势」直接写了
 * {@code (java.sql.Date) row[0]}，遇到字符串就抛 ClassCastException，又被
 * {@code catch (Exception e) { // ignore }} 吞掉 —— 结果图表 7 天全是 0，
 * 而同一张页面上的「AI 消息总数」却是对的（那条走的是 COUNT 查询）。</p>
 *
 * <p>这里统一把各种可能的返回类型转成 {@code yyyy-MM-dd} 字符串，转不出来返回 null，
 * 由调用方决定如何处理（通常是跳过该行而不是让整段统计清空）。</p>
 */
public final class DateKeys {

    public static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateKeys() {
    }

    /**
     * 把聚合查询返回的日期列归一化成 {@code yyyy-MM-dd}。
     *
     * @param value 可能是 String / java.sql.Date / java.time.LocalDate / LocalDateTime / java.util.Date
     * @return yyyy-MM-dd，无法识别时返回 null
     */
    public static String toIsoDate(Object value) {
        if (value == null) return null;

        if (value instanceof LocalDate date) {
            return date.format(ISO_DATE);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate().format(ISO_DATE);
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().format(ISO_DATE);
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate().format(ISO_DATE);
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().format(ISO_DATE);
        }

        String text = value.toString().trim();
        if (text.isEmpty()) return null;

        // 已经是 yyyy-MM-dd，或带时间的 yyyy-MM-dd HH:mm:ss[.SSS] / ISO 风格
        if (text.length() >= 10) {
            try {
                return LocalDate.parse(text.substring(0, 10)).format(ISO_DATE);
            } catch (Exception ignored) {
                // 落到下面继续尝试其它格式
            }
        }
        // 形如 MM-dd：无法确定年份时不猜，交给调用方处理
        return null;
    }

    /** 归一化成 {@code MM-dd}（图表横轴用）；无法识别返回 null */
    public static String toMonthDay(Object value) {
        String iso = toIsoDate(value);
        if (iso == null) return null;
        return iso.substring(5);
    }
}
