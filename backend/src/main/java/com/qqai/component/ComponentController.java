package com.qqai.component;

import com.qqai.dto.common.ApiResponse;
import com.qqai.exception.BizException;
import com.qqai.security.AuthPrincipal;
import com.qqai.service.AuditLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一组件 API：状态、启停（ADMIN）。 */
@RestController
@RequestMapping("/api/system/components")
public class ComponentController {

    private final ComponentOrchestrator orchestrator;
    private final AuditLogService auditLogService;

    public ComponentController(ComponentOrchestrator orchestrator, AuditLogService auditLogService) {
        this.orchestrator = orchestrator;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("components", orchestrator.list());
        data.put("serverTime", OffsetDateTime.now().toString());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<ApiResponse<Map<String, Object>>> start(@PathVariable String id) {
        String operator = currentUsername();
        try {
            ComponentModel.ComponentView view = orchestrator.start(id, false);
            auditLogService.log(operator, "COMPONENT_START", id, "SUCCESS",
                    "state=" + view.state + ", pid=" + view.pid);
            return ResponseEntity.ok(ApiResponse.success(single(view)));
        } catch (BizException e) {
            auditLogService.log(operator, "COMPONENT_START", id, "FAILURE", e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<ApiResponse<Map<String, Object>>> stop(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        String operator = currentUsername();
        try {
            ComponentModel.ComponentView view = orchestrator.stop(id, force);
            auditLogService.log(operator, "COMPONENT_STOP", id, "SUCCESS",
                    "force=" + force + ", state=" + view.state);
            return ResponseEntity.ok(ApiResponse.success(single(view)));
        } catch (BizException e) {
            auditLogService.log(operator, "COMPONENT_STOP", id, "FAILURE", e.getMessage());
            throw e;
        }
    }

    private Map<String, Object> single(ComponentModel.ComponentView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("component", view);
        return data;
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal ap) {
            return ap.username();
        }
        return "unknown";
    }
}
