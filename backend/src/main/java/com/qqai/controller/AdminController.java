package com.qqai.controller;

import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.User;
import com.qqai.security.AuthPrincipal;
import com.qqai.service.AuditLogService;
import com.qqai.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserService userService;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * 获取当前登录管理员用户名（用于审计）
     */
    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal ap) {
            return ap.username();
        }
        return "unknown";
    }

    /**
     * 获取用户列表（分页+搜索，管理员专用）
     */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> userPage;
        if (keyword != null && !keyword.isBlank()) {
            userPage = userService.searchUsers(keyword, pageable);
        } else {
            userPage = userService.findAllPaginated(pageable);
        }

        List<Map<String, Object>> content = new ArrayList<>();
        for (User user : userPage.getContent()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", user.getId());
            map.put("username", user.getUsername());
            map.put("nickname", user.getNickname());
            map.put("avatar", user.getAvatar());
            map.put("role", user.getRole());
            map.put("active", user.isActive());
            map.put("lastLoginTime", user.getLastLoginTime());
            map.put("createdAt", user.getCreatedAt());
            content.add(map);
        }

        Map<String, Object> pageData = new HashMap<>();
        pageData.put("content", content);
        pageData.put("totalElements", userPage.getTotalElements());
        pageData.put("totalPages", userPage.getTotalPages());
        pageData.put("number", userPage.getNumber());
        pageData.put("size", userPage.getSize());
        pageData.put("first", userPage.isFirst());
        pageData.put("last", userPage.isLast());

        auditLogService.log(currentUsername(), "ADMIN_LIST_USERS", "users", "SUCCESS", "查看用户列表");
        return ResponseEntity.ok(ApiResponse.success(pageData));
    }

    /**
     * 更新用户角色
     */
    @PutMapping("/users/{id}/role")
    public ResponseEntity<?> updateUserRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String role = body.get("role");
        if (role == null || (!role.equals("ADMIN") && !role.equals("USER"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的角色"));
        }

        Optional<User> existing = userService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String prevRole = existing.get().getRole();
        String targetUsername = existing.get().getUsername();

        Optional<User> userOpt = userService.updateUserRole(id, role);
        if (userOpt.isEmpty()) {
            auditLogService.log(currentUsername(), "ROLE_CHANGE", "user:" + targetUsername,
                    "FAILURE", "目标角色=" + role + ", 旧角色=" + prevRole);
            return ResponseEntity.notFound().build();
        }

        auditLogService.log(currentUsername(), "ROLE_CHANGE", "user:" + targetUsername,
                "SUCCESS", "角色变更: " + prevRole + " -> " + role + " (userId=" + id + ")");
        return ResponseEntity.ok(Map.of("message", "角色更新成功"));
    }

    /**
     * 禁用/启用用户
     */
    @PutMapping("/users/{id}/active")
    public ResponseEntity<?> updateUserActive(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        Boolean active = body.get("active");
        if (active == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少active参数"));
        }

        Optional<User> existing = userService.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String targetUsername = existing.get().getUsername();

        Optional<User> userOpt = userService.updateUserActive(id, active);
        if (userOpt.isEmpty()) {
            auditLogService.log(currentUsername(), "USER_ACTIVE_CHANGE", "user:" + targetUsername,
                    "FAILURE", "目标状态=" + (active ? "启用" : "禁用"));
            return ResponseEntity.notFound().build();
        }

        auditLogService.log(currentUsername(), "USER_ACTIVE_CHANGE", "user:" + targetUsername,
                "SUCCESS", (active ? "启用" : "禁用") + "用户 (userId=" + id + ")");
        return ResponseEntity.ok(Map.of("message", "用户状态更新成功"));
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        Optional<User> userOpt = userService.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String targetUsername = userOpt.get().getUsername();
        try {
            userService.delete(userOpt.get());
            auditLogService.log(currentUsername(), "USER_DELETE", "user:" + targetUsername,
                    "SUCCESS", "删除用户 (userId=" + id + ")");
            return ResponseEntity.ok(Map.of("message", "用户删除成功"));
        } catch (Exception e) {
            auditLogService.log(currentUsername(), "USER_DELETE", "user:" + targetUsername,
                    "FAILURE", "删除失败: " + e.getMessage());
            throw e;
        }
    }
}
