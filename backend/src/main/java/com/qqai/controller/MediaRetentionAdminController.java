package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.service.AuditLogService;
import com.qqai.service.FilePurgeService;
import com.qqai.service.MediaRetentionSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 媒体保留策略（管理端）：{@code GET/PUT /api/admin/media-retention/config}、{@code GET .../preview}
 *
 * <p>字段与前端冻结契约一致：{@code { enabled, days, types }}。</p>
 *
 * <p><b>preview 是 dry-run</b>：只统计「按当前/指定天数会被删掉多少文件、多少体积」，不删任何东西。
 * 这是自动删除功能的安全前提——管理员在打开开关前应该先看清影响面。</p>
 *
 * <p>权限：URL 层 {@code SecurityConfig} 已对 {@code /api/admin/**} 要求 ADMIN，
 * 这里再加 {@link PreAuthorize} 做方法级兜底（与 {@code AiSummaryAdminController} 风格一致）。</p>
 */
@RestController
@RequestMapping("/api/admin/media-retention")
public class MediaRetentionAdminController {

    private static final Logger log = LoggerFactory.getLogger(MediaRetentionAdminController.class);

    @Autowired
    private MediaRetentionSettingsService retentionSettings;

    @Autowired
    private FilePurgeService filePurgeService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private SecurityHelper securityHelper;

    /** 读取当前保留策略 */
    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(retentionSettings.toView()));
    }

    /**
     * 更新保留策略（部分字段更新：只处理请求体里出现的键）。
     * 写操作记审计日志（改动前后完整快照）。
     */
    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> before = retentionSettings.toView();
        Map<String, Object> after = retentionSettings.update(body);

        String detail = "旧=" + before + " 新=" + after;
        auditLogService.log(currentUsername(), "MEDIA_RETENTION_CONFIG_UPDATE", "file.retention", "SUCCESS", detail);
        log.info("管理员更新媒体保留策略 by {}: {}", currentUsername(), detail);
        return ResponseEntity.ok(ApiResponse.success(after));
    }

    /**
     * 清理预览（dry-run，不删除任何文件）。
     *
     * @param days 可选；不传则用当前生效的天数。用于「改了天数但还没保存」时先看影响面。
     */
    @GetMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(@RequestParam(required = false) Integer days) {
        int effectiveDays = days == null ? retentionSettings.getDays() : days;
        FilePurgeService.RetentionPreview preview =
                filePurgeService.previewRetention(effectiveDays, retentionSettings.typeList());
        // 同时回传当前生效的开关状态，让前端一次请求就能渲染整块面板
        preview.getView().put("enabled", retentionSettings.isEnabled());
        return ResponseEntity.ok(ApiResponse.success(preview.getView()));
    }

    /**
     * 立即执行一次清理（手动触发，一次性的）。
     *
     * <p><b>与自动任务的分工</b>：定时任务只看 {@code enabled} 开关、每天 4:00 跑；
     * 这个接口不看开关、由管理员当场触发，用来「立刻生效一次」或「验收时验证链路」。
     * 与归档功能的 {@code GET /admin/archive/preview} + {@code POST /messages/archive} 是同一对搭配：
     * 先 dry-run 看清影响面，再手动执行。</p>
     *
     * <p>可用 {@code days} 临时覆盖保留天数（不修改已保存的设置），
     * 便于「按 30 天试跑一次」而不用先改配置再改回来。</p>
     *
     * @param days 可选；不传则用当前生效的保留天数
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runNow(@RequestParam(required = false) Integer days) {
        int effectiveDays = days == null ? retentionSettings.getDays() : days;
        FilePurgeService.PurgeResult result = filePurgeService.purgeMediaOlderThan(
                effectiveDays, retentionSettings.typeList(), currentUsername());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("days", effectiveDays);
        data.put("totalDeleted", result.getTotalDeleted());
        data.put("freedBytes", result.getFreedBytes());
        data.put("freedMB", result.getFreedMB());
        data.put("note", result.getNote() == null ? "" : result.getNote());
        data.put("message", "已清理 " + result.getTotalDeleted() + " 个文件（" + result.getFreedMB() + "）");
        log.info("管理员手动执行媒体保留清理 by {}: days={}, deleted={}, freed={}",
                currentUsername(), effectiveDays, result.getTotalDeleted(), result.getFreedMB());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    /** 当前管理员用户名（用于审计；无认证上下文时返回 unknown） */
    private String currentUsername() {
        try {
            return securityHelper.getCurrentUsername();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
