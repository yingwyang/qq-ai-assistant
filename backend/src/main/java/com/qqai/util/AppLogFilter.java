package com.qqai.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用日志行的过滤逻辑（从 LogController 抽出，便于单测且不依赖 Spring）。
 *
 * <p>日志是文本文件而不是数据库记录：调用方先取出「最近 N 行」的窗口，本类只在这个窗口内
 * 按级别 / 关键字 / 时间范围过滤。带时间条件时，解析不出时间戳的行（异常堆栈续行）会被排除，
 * 因为无法判断它属于哪一天。</p>
 */
public final class AppLogFilter {

    /** 日志级别 + 时间戳正则（与 logback 默认格式一致） */
    public static final Pattern LEVEL_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.?\\d*\\s+\\[[^]]*]\\s+(\\w+)\\s+");

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AppLogFilter() {
    }

    /**
     * @param lines   日志行（已按时间倒序）
     * @param level   INFO / WARN / ERROR / DEBUG / ALL（null 或空白视为 ALL）
     * @param keyword 关键字（忽略大小写，可为空）
     * @param from    起始日期 yyyy-MM-dd（含当天 00:00:00，可为空）
     * @param to      结束日期 yyyy-MM-dd（含当天 23:59:59.999999999，可为空）
     * @return 过滤后的日志条目 [{level, message}]
     */
    public static List<Map<String, Object>> filter(List<String> lines, String level,
                                                  String keyword, String from, String to) {
        String upperLevel = level == null || level.isBlank() ? "ALL" : level.trim().toUpperCase();
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        LocalDateTime fromTs = parseStart(from);
        LocalDateTime toTs = parseEnd(to);

        List<Map<String, Object>> filtered = new ArrayList<>();
        if (lines == null) return filtered;

        for (String line : lines) {
            String parsedLevel = extractLevel(line);
            boolean levelOk = "ALL".equals(upperLevel)
                    || upperLevel.equals(parsedLevel)
                    || (parsedLevel == null && upperLevel.equals("ERROR") && lineContainsError(line));
            if (!levelOk) continue;

            if (kw != null && !kw.isEmpty() && !line.toLowerCase().contains(kw)) continue;

            if (fromTs != null || toTs != null) {
                LocalDateTime ts = extractTimestamp(line);
                if (ts == null) continue;
                if (fromTs != null && ts.isBefore(fromTs)) continue;
                if (toTs != null && ts.isAfter(toTs)) continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("level", parsedLevel != null ? parsedLevel : "UNKNOWN");
            entry.put("message", line);
            filtered.add(entry);
        }
        return filtered;
    }

    /** 从行首解析级别；解析不出返回 null */
    public static String extractLevel(String line) {
        if (line == null || line.isEmpty()) return null;
        Matcher m = LEVEL_PATTERN.matcher(line);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        return null;
    }

    /** 从行首解析 logback 时间戳；解析不出返回 null */
    public static LocalDateTime extractTimestamp(String line) {
        if (line == null || line.length() < 19) return null;
        Matcher m = LEVEL_PATTERN.matcher(line);
        if (!m.find()) return null;
        String stamp = m.group(0).trim();
        String dateTime = stamp.substring(0, 19).replace('T', ' ');
        try {
            return LocalDateTime.parse(dateTime, TIMESTAMP_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean lineContainsError(String line) {
        return line != null && (line.contains("ERROR") || line.contains("Exception"));
    }

    /** 起始日期：当天 00:00:00；无法解析返回 null */
    public static LocalDateTime parseStart(String value) {
        LocalDate date = parseDate(value);
        return date == null ? null : date.atStartOfDay();
    }

    /** 结束日期：当天最后一刻（含当天）；无法解析返回 null */
    public static LocalDateTime parseEnd(String value) {
        LocalDate date = parseDate(value);
        return date == null ? null : LocalDateTime.of(date, LocalTime.MAX);
    }

    /** 解析 yyyy-MM-dd（容错处理带时间的 ISO 串）；无法解析返回 null 而不是抛异常 */
    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed);
        } catch (Exception e) {
            return null;
        }
    }
}
