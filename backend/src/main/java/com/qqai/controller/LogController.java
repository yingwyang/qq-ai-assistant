package com.qqai.controller;

import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.AuditLog;
import com.qqai.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 系统日志控制器 - 提供应用日志与审计日志查询
 */
@RestController
@RequestMapping("/api/admin")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    /** 应用日志文件路径（与 logback-spring.xml 中的 LOG_FILE 一致） */
    @Value("${logging.file.name:./logs/application.log}")
    private String logFilePath;

    /** 单次读取应用日志的最大行数（避免内存溢出） */
    private static final int MAX_READ_LINES = 5000;

    /** 日志级别正则：从行首提取 INFO / WARN / ERROR / DEBUG / TRACE */
    private static final Pattern LEVEL_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.?\\d*\\s+\\[[^]]*]\\s+(\\w+)\\s+");

    @Autowired
    private AuditLogService auditLogService;

    /**
     * 获取应用日志（按行解析，支持 level 过滤与分页）。
     *
     * @param level 日志级别过滤：INFO / WARN / ERROR / DEBUG / ALL（默认 ALL）
     * @param page  页码（从 0 开始）
     * @param size  每页大小
     */
    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getApplicationLogs(
            @RequestParam(defaultValue = "ALL") String level,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        Map<String, Object> data = new HashMap<>();
        Path logPath = Paths.get(logFilePath).toAbsolutePath().normalize();

        if (!Files.exists(logPath) || !Files.isRegularFile(logPath)) {
            data.put("content", new ArrayList<>());
            data.put("totalElements", 0);
            data.put("totalPages", 0);
            data.put("page", page);
            data.put("size", size);
            data.put("level", level);
            data.put("message", "日志文件尚未生成: " + logPath);
            return ResponseEntity.ok(ApiResponse.success(data));
        }

        // 倒序读取最近的若干行
        List<String> recentLines = new ArrayList<>();
        try (Stream<String> lines = Files.lines(logPath, StandardCharsets.UTF_8)) {
            // 读取全部行后再倒序（文件通常不会太大，单文件已限制 20MB）
            List<String> all = new ArrayList<>();
            lines.forEach(all::add);
            int from = Math.max(0, all.size() - MAX_READ_LINES);
            for (int i = all.size() - 1; i >= from; i--) {
                recentLines.add(all.get(i));
            }
        } catch (IOException e) {
            log.warn("读取应用日志文件失败: {}", e.getMessage());
            data.put("content", new ArrayList<>());
            data.put("totalElements", 0);
            data.put("totalPages", 0);
            data.put("page", page);
            data.put("size", size);
            data.put("level", level);
            data.put("message", "读取日志失败: " + e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(data));
        }

        // 按级别过滤
        String upperLevel = level == null ? "ALL" : level.toUpperCase();
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (String line : recentLines) {
            String parsedLevel = extractLevel(line);
            if ("ALL".equals(upperLevel) || upperLevel.equals(parsedLevel)
                    || (parsedLevel == null && upperLevel.equals("ERROR") && lineContainsError(line))) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("level", parsedLevel != null ? parsedLevel : "UNKNOWN");
                entry.put("message", line);
                filtered.add(entry);
            }
        }

        // 分页
        int total = filtered.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Map<String, Object>> pageContent = fromIndex < total
                ? filtered.subList(fromIndex, toIndex)
                : new ArrayList<>();

        data.put("content", pageContent);
        data.put("totalElements", total);
        data.put("totalPages", totalPages);
        data.put("page", page);
        data.put("size", size);
        data.put("level", upperLevel);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 获取审计日志（从 audit_log 表查询，支持搜索和分页）。
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAuditLogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<AuditLog> result = auditLogService.getAuditLogs(keyword, action, pageRequest);

        Map<String, Object> data = new HashMap<>();
        data.put("content", result.getContent());
        data.put("totalElements", result.getTotalElements());
        data.put("totalPages", result.getTotalPages());
        data.put("page", page);
        data.put("size", size);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private String extractLevel(String line) {
        if (line == null || line.isEmpty()) return null;
        Matcher m = LEVEL_PATTERN.matcher(line);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        return null;
    }

    private boolean lineContainsError(String line) {
        return line != null && (line.contains("ERROR") || line.contains("Exception"));
    }
}
