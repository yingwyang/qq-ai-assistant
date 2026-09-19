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
import org.springframework.http.HttpHeaders;
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

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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
     * 获取用户列表（分页 + 关键字 + 角色 + 状态 + 排序，管理员专用）
     */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> userPage = userService.queryUsers(keyword, role, active, sort, order, pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (User user : userPage.getContent()) {
            content.add(userToMap(user));
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
     * 导出用户列表为 CSV（沿用列表页的筛选与排序，最多 {@link #USER_EXPORT_MAX_ROWS} 行）。
     * 超出上限时在响应头 X-Truncated 标记，前端据此提示收窄筛选条件。
     */
    @GetMapping("/users/export")
    public ResponseEntity<byte[]> exportUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        UserService.UserExportResult result =
                userService.exportUsers(keyword, role, active, sort, order, USER_EXPORT_MAX_ROWS);

        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("ID,账号,昵称,角色,状态,最后登录,注册时间\n");
        for (User user : result.users()) {
            sb.append(user.getId()).append(',')
                    .append(csv(user.getUsername())).append(',')
                    .append(csv(user.getNickname())).append(',')
                    .append("ADMIN".equalsIgnoreCase(user.getRole()) ? "管理员" : "普通用户").append(',')
                    .append(user.isActive() ? "正常" : "已禁用").append(',')
                    .append(csv(formatTime(user.getLastLoginTime()))).append(',')
                    .append(csv(formatTime(user.getCreatedAt()))).append('\n');
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8));
        headers.setContentDispositionFormData("attachment", "users.csv");
        headers.add("X-Truncated", String.valueOf(result.truncated()));
        auditLogService.log(currentUsername(), "ADMIN_EXPORT_USERS", "users", "SUCCESS",
                "导出 " + result.users().size() + " 条用户");
        return new ResponseEntity<>(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headers, org.springframework.http.HttpStatus.OK);
    }

    /** 用户导出上限（超过时截断并在响应头标记） */
    private static final int USER_EXPORT_MAX_ROWS = 100000;

    private Map<String, Object> userToMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("nickname", user.getNickname());
        map.put("avatar", user.getAvatar());
        map.put("role", user.getRole());
        map.put("active", user.isActive());
        map.put("lastLoginTime", user.getLastLoginTime());
        map.put("createdAt", user.getCreatedAt());
        return map;
    }

    private static String formatTime(java.time.LocalDateTime time) {
        return time == null ? "" : time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String csv(String value) {
        if (value == null) return "";
        String v = value.replace("\r", " ").replace("\n", " ").trim();
        if (v.contains(",") || v.contains("\"")) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
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
     * 管理员重置指定用户的密码（用户"忘记密码"时由管理员处理）。
     * 密码规则与用户自助改密一致：8-64 位且同时包含大写字母、小写字母与数字。
     * 重置后 tokenVersion + 1 → 该用户已签发的登录态立即失效，需用新密码重新登录。
     */
    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<?> resetUserPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newPassword = body.get("newPassword");
        if (newPassword == null
                || !newPassword.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,64}$")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "密码需 8-64 位，且同时包含大写字母、小写字母与数字"));
        }

        Optional<User> userOpt = userService.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userService.save(user);

        auditLogService.log(currentUsername(), "ADMIN_RESET_PASSWORD", "user:" + user.getUsername(),
                "SUCCESS", "管理员重置密码 (userId=" + id + ")");
        return ResponseEntity.ok(Map.of("message", "密码已重置，该用户的登录状态已失效，请用新密码重新登录"));
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
