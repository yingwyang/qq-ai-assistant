package com.qqai.controller;

import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.AuditLog;
import com.qqai.service.AuditLogService;
import com.qqai.util.AppLogFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 审计日志导出的行数上限 */
    private static final int EXPORT_MAX_ROWS = 20000;

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
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
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
            int fromIdx = Math.max(0, all.size() - MAX_READ_LINES);
            for (int i = all.size() - 1; i >= fromIdx; i--) {
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

        List<Map<String, Object>> filtered = filterApplicationLogs(recentLines, level, keyword, from, to);

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
        data.put("level", level == null || level.isBlank() ? "ALL" : level.toUpperCase());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 获取审计日志（从 audit_log 表查询，支持搜索和分页）。
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAuditLogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 分页上限统一收口在 common/PageLimits
        PageRequest pageRequest = (PageRequest) com.qqai.common.PageLimits.of(page, size,
                Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<AuditLog> resultPage = auditLogService.getAuditLogs(
                keyword, action, target, result, parseStart(from), parseEnd(to), pageRequest);

        Map<String, Object> data = new HashMap<>();
        data.put("content", resultPage.getContent());
        data.put("totalElements", resultPage.getTotalElements());
        data.put("totalPages", resultPage.getTotalPages());
        data.put("page", page);
        data.put("size", size);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /**
     * 导出应用日志：沿用 /logs 的过滤条件，最多导出最近 {@link #MAX_READ_LINES} 行。
     * 日志文件缺失或读取失败时返回 400 + 可读说明，而不是给一个空文件。
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportApplicationLogs(
            @RequestParam(defaultValue = "ALL") String level,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        Path logPath = Paths.get(logFilePath).toAbsolutePath().normalize();
        if (!Files.exists(logPath) || !Files.isRegularFile(logPath)) {
            return textError(400, "日志文件尚未生成: " + logPath);
        }

        List<String> recentLines = new ArrayList<>();
        try (Stream<String> lines = Files.lines(logPath, StandardCharsets.UTF_8)) {
            List<String> all = new ArrayList<>();
            lines.forEach(all::add);
            int fromIdx = Math.max(0, all.size() - MAX_READ_LINES);
            for (int i = all.size() - 1; i >= fromIdx; i--) {
                recentLines.add(all.get(i));
            }
        } catch (IOException e) {
            return textError(400, "读取日志失败: " + e.getMessage());
        }

        List<Map<String, Object>> filtered = filterApplicationLogs(recentLines, level, keyword, from, to);
        StringBuilder sb = new StringBuilder();
        sb.append("# 应用日志导出 级别=").append(level == null ? "ALL" : level.toUpperCase());
        if (keyword != null && !keyword.isBlank()) sb.append(" 关键字=").append(keyword.trim());
        if (from != null && !from.isBlank()) sb.append(" 起=").append(from.trim());
        if (to != null && !to.isBlank()) sb.append(" 止=").append(to.trim());
        sb.append(" 行数=").append(filtered.size())
                .append("（最多回溯最近 ").append(MAX_READ_LINES).append(" 行）\n");
        for (Map<String, Object> entry : filtered) {
            sb.append(entry.get("message")).append('\n');
        }
        return attachment(sb.toString().getBytes(StandardCharsets.UTF_8), "application-log.txt", MediaType.TEXT_PLAIN);
    }

    /** 导出审计日志为 CSV（带 BOM，Excel 直接打开不乱码）。 */
    @GetMapping("/audit-logs/export")
    public ResponseEntity<byte[]> exportAuditLogs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        PageRequest pageRequest = PageRequest.of(0, EXPORT_MAX_ROWS, Sort.by(Sort.Direction.DESC, "timestamp"));
        Page<AuditLog> page = auditLogService.getAuditLogs(
                keyword, action, target, result, parseStart(from), parseEnd(to), pageRequest);

        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("时间,操作人,操作类型,操作目标,结果,详情\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (AuditLog item : page.getContent()) {
            sb.append(csv(item.getTimestamp() == null ? "" : item.getTimestamp().format(fmt))).append(',')
                    .append(csv(item.getUsername())).append(',')
                    .append(csv(item.getAction())).append(',')
                    .append(csv(item.getTarget())).append(',')
                    .append(csv(item.getResult())).append(',')
                    .append(csv(item.getDetail())).append('\n');
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "audit-log.csv");
        boolean truncated = page.getTotalElements() > page.getContent().size();
        headers.add("X-Truncated", String.valueOf(truncated));
        return new ResponseEntity<>(sb.toString().getBytes(StandardCharsets.UTF_8), headers, org.springframework.http.HttpStatus.OK);
    }

    /** 把 error 说明以 text/plain 返回，便于前端直接展示后端原因 */
    private ResponseEntity<byte[]> textError(int status, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                .body(message.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> attachment(byte[] body, String fileName, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setContentDispositionFormData("attachment", fileName);
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    private static String csv(String value) {
        if (value == null) return "";
        String v = value.replace("\r", " ").replace("\n", " ").trim();
        if (v.contains(",") || v.contains("\"")) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** 应用日志过滤（级别 + 关键字 + 时间范围），逻辑在 AppLogFilter 里，便于单测 */
    private List<Map<String, Object>> filterApplicationLogs(
            List<String> lines, String level, String keyword, String from, String to) {
        return AppLogFilter.filter(lines, level, keyword, from, to);
    }

    private LocalDateTime parseStart(String value) {
        return AppLogFilter.parseStart(value);
    }

    private LocalDateTime parseEnd(String value) {
        return AppLogFilter.parseEnd(value);
    }

    private String extractLevel(String line) {
        return AppLogFilter.extractLevel(line);
    }

    private boolean lineContainsError(String line) {
        return AppLogFilter.lineContainsError(line);
    }
}
