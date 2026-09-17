package com.qqai.controller;

import com.qqai.common.SecurityHelper;
import com.qqai.dto.common.ApiResponse;
import com.qqai.service.AiSummarySettingsService;
import com.qqai.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 摘要配置（管理端）：{@code GET/PUT /api/admin/ai-summary/config}
 *
 * <p>字段与前端冻结契约完全一致：
 * {@code { enabled, dailyLimit, minLength, maxInputChars, groupWhitelist, digestCron, digestGroups }}。
 * 前端 {@code adminApi.getAiSummaryConfig()} / {@code updateAiSummaryConfig()} 已按此约定调用
 * （见 {@code AdminBackup.vue} 的「摘要设置」区块）。</p>
 *
 * <p>响应统一用 {@code ApiResponse.success(data)} 包裹：前端 {@code request()} 会自动取 {@code data}，
 * 因此前端拿到的就是配置对象本身。</p>
 *
 * <p>权限：URL 层 {@code SecurityConfig} 已对 {@code /api/admin/**} 要求 ADMIN，
 * 这里再加 {@link PreAuthorize} 做方法级兜底（与 {@code MessageController#processAllMessages} 风格一致）。</p>
 *
 * <p>值在 {@link AiSummarySettingsService} 内存态里保存并落盘（复用
 * {@code ConfigService} 的 {@code data/application-override.properties} 机制），
 * 保存后立即生效、无需重启：</p>
 * <ul>
 *   <li>{@code enabled=false} → 单条摘要 / 批量摘要 / 群日报（含读取）全部 403；</li>
 *   <li>{@code dailyLimit} / {@code minLength} → 批量投递与日报取数（{@code GroupDigestService}）；</li>
 *   <li>{@code maxInputChars} → 日报拼接上限；</li>
 *   <li>{@code groupWhitelist} → 日报生成/读取与批量群校验；</li>
 *   <li>{@code digestCron} / {@code digestGroups} → {@code GroupDigestScheduler}（改完自动重新注册 cron）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/ai-summary")
public class AiSummaryAdminController {

    private static final Logger log = LoggerFactory.getLogger(AiSummaryAdminController.class);

    @Autowired
    private AiSummarySettingsService aiSummarySettingsService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private SecurityHelper securityHelper;

    /**
     * 读取当前 AI 摘要配置。
     *
     * @return {@code ApiResponse.success({enabled, dailyLimit, minLength, maxInputChars, groupWhitelist, digestCron, digestGroups})}
     */
    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(aiSummarySettingsService.toView()));
    }

    /**
     * 更新 AI 摘要配置（部分字段更新：只处理请求体里出现的键）。
     * 写操作记审计日志（操作人、改动前后的完整快照）。
     *
     * @param body 字段子集，值可为 Boolean / Number / String
     * @return 更新后的完整配置快照
     */
    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> before = aiSummarySettingsService.toView();
        Map<String, Object> after = aiSummarySettingsService.update(body);

        String detail = "旧=" + before + " 新=" + after;
        auditLogService.log(currentUsername(), "AI_SUMMARY_CONFIG_UPDATE", "ai.summary", "SUCCESS", detail);
        log.info("管理员更新 AI 摘要配置 by {}: {}", currentUsername(), detail);
        return ResponseEntity.ok(ApiResponse.success(after));
    }

    /** 当前管理员用户名（用于审计；无认证上下文时 SecurityHelper 返回 "system"） */
    private String currentUsername() {
        try {
            return securityHelper.getCurrentUsername();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
