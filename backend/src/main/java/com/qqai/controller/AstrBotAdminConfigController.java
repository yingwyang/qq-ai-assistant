package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.service.AstrBotAdminConfigService;
import com.qqai.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台「AstrBot 配置」：提供商、模型、配置档案与摘要模型的增删改查。
 *
 * <p>全部写操作都走 AstrBot 的开放 API（{@code /api/v1/provider-sources}、
 * {@code /api/v1/providers}、{@code /api/v1/config-profiles}），由 AstrBot 自己热重载，
 * 不需要重启组件；密钥只以缩写形式返回前端。</p>
 *
 * <p>权限：{@code /api/admin/**} 在 {@code SecurityConfig} 已要求 ADMIN，
 * 这里再加方法级 {@link PreAuthorize} 兜底；写操作记审计日志。</p>
 */
@RestController
@RequestMapping("/api/admin/astrbot/config")
public class AstrBotAdminConfigController {

    private static final Logger log = LoggerFactory.getLogger(AstrBotAdminConfigController.class);

    @Autowired
    private AstrBotAdminConfigService configService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private com.qqai.service.AstrBotSessionMemoryService sessionMemoryService;

    /**
     * 手动清扫「摘要/分析」留下的一次性会话（AstrBot 回复后才异步落库，接口内即时删可能扑空；
     * 后台每 2 分钟也会自动扫一遍，这个接口用于立即生效/排障）。
     */
    @PostMapping("/sessions/cleanup")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cleanupOneShotSessions() {
        Map<String, Object> result = sessionMemoryService.purgeOneShotSessions();
        audit("ASTRBOT_ONESHOT_SESSION_CLEANUP", "webchat-sessions", String.valueOf(result));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** 总览：摘要模型 + 提供商(key 缩写) + 模型 + 配置档案 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(configService.inventory()));
    }

    /** 设置摘要模型（空字符串=清除，回到档案默认模型） */
    @PutMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSummary(@RequestBody Map<String, Object> body) {
        Object raw = body.get("model");
        String modelId = raw == null ? "" : String.valueOf(raw);
        Map<String, Object> summary = configService.updateSummaryModel(modelId);
        audit("SUMMARY_MODEL_UPDATE", "astrbot.summary", "model=" + modelId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @PostMapping("/sources")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSource(@RequestBody Map<String, Object> body) {
        configService.createSource(body);
        audit("ASTRBOT_SOURCE_CREATE", String.valueOf(body.get("id")), "base=" + body.get("apiBase"));
        return ok("提供商已新增");
    }

    @PutMapping("/sources")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSource(@RequestBody Map<String, Object> body) {
        configService.updateSource(body);
        // 审计日志里不落密钥，只记录"是否改了 key"
        boolean keyChanged = body.get("apiKey") != null && !String.valueOf(body.get("apiKey")).isBlank();
        audit("ASTRBOT_SOURCE_UPDATE", String.valueOf(body.get("id")), "keyChanged=" + keyChanged);
        return ok("提供商已更新");
    }

    @DeleteMapping("/sources")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteSource(@RequestParam("id") String id) {
        configService.deleteSource(id);
        audit("ASTRBOT_SOURCE_DELETE", id, null);
        return ok("提供商已删除");
    }

    @PostMapping("/providers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createProvider(@RequestBody Map<String, Object> body) {
        configService.createProvider(body);
        audit("ASTRBOT_PROVIDER_CREATE", body.get("sourceId") + "/" + body.get("model"), null);
        return ok("模型已新增");
    }

    @PatchMapping("/providers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> patchProvider(@RequestBody Map<String, Object> body) {
        String id = body.get("id") == null ? "" : String.valueOf(body.get("id"));
        boolean enable = Boolean.parseBoolean(String.valueOf(body.get("enable")));
        configService.setProviderEnabled(id, enable);
        audit("ASTRBOT_PROVIDER_TOGGLE", id, "enable=" + enable);
        return ok(enable ? "模型已启用" : "模型已停用");
    }

    @DeleteMapping("/providers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteProvider(@RequestParam("id") String id) {
        configService.deleteProvider(id);
        audit("ASTRBOT_PROVIDER_DELETE", id, null);
        return ok("模型已删除");
    }

    /** 同步全局提供商/模型进所有配置档案；apply=false 只做 dry-run */
    @PostMapping("/profiles/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncProfiles(@RequestBody(required = false) Map<String, Object> body) {
        boolean apply = body != null && Boolean.parseBoolean(String.valueOf(body.get("apply")));
        String mainModelId = body == null || body.get("mainModelId") == null ? "" : String.valueOf(body.get("mainModelId"));
        Map<String, Object> result = configService.syncProfiles(apply, mainModelId);
        audit("ASTRBOT_PROFILE_SYNC", "all-profiles", "apply=" + apply + ", changed=" + result.get("changedCount") + ", mainModel=" + mainModelId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @DeleteMapping("/profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteProfile(@RequestParam("id") String id) {
        configService.deleteProfile(id);
        audit("ASTRBOT_PROFILE_DELETE", id, null);
        return ok("配置档案已删除");
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> ok(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("message", message);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private void audit(String action, String target, String detail) {
        String username;
        try {
            username = securityHelper.getCurrentUsername();
        } catch (Exception e) {
            username = "unknown";
        }
        auditLogService.log(username, action, target, "SUCCESS", detail);
        log.info("管理端 AstrBot 配置操作 {} by {} target={} {}", action, username, target, detail == null ? "" : detail);
    }
}
