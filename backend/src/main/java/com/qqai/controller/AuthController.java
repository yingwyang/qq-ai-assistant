package com.qqai.controller;

import com.qqai.common.AvatarResolver;
import com.qqai.common.RateLimiterService;
import com.qqai.config.AppRegistrationProperties;
import com.qqai.dto.auth.AuthResponse;
import com.qqai.dto.auth.ChangePasswordRequest;
import com.qqai.dto.auth.LoginRequest;
import com.qqai.dto.auth.RegisterRequest;
import com.qqai.dto.auth.UpdateProfileRequest;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.User;
import com.qqai.service.AstrBotService;
import com.qqai.service.GptSovitsService;
import com.qqai.service.NapCatService;
import com.qqai.service.PluginEnsureService;
import com.qqai.service.UserService;
import com.qqai.security.JwtUtil;
import com.qqai.security.TokenBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private AvatarResolver avatarResolver;

    @Autowired
    private AppRegistrationProperties registrationProperties;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private NapCatService napCatService;

    @Autowired
    private GptSovitsService gptSovitsService;

    @Autowired
    private PluginEnsureService pluginEnsureService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody @Valid LoginRequest req) {
        String username = req.username();
        String password = req.password();

        Optional<User> userOpt = userService.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "用户名或密码错误"));
        }

        User user = userOpt.get();

        if (!user.isActive()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "账号已被禁用"));
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "用户名或密码错误"));
        }

        user.setLastLoginTime(LocalDateTime.now());
        userService.save(user);

        Integer tv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), tv);

        // 异步检查并启动三个插件（延迟 3 秒，不阻塞登录响应）
        pluginEnsureService.ensurePluginsStartedAsync();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        AuthResponse response = new AuthResponse(
                user.getId(),
                token,
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                avatarResolver.resolveAvatarUrl(user.getAvatar()),
                user.getEmail(),
                user.getCreatedAt() != null ? user.getCreatedAt().format(formatter) : null,
                user.getLastLoginTime() != null ? user.getLastLoginTime().format(formatter) : null
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@RequestBody @Valid RegisterRequest req, HttpServletRequest request) {
        if (!registrationProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "注册暂未开放"));
        }

        String pwd = req.password();
        if (pwd == null || !pwd.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,64}$")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "密码强度不足，至少 8 位且包含大小写字母与数字"));
        }

        String clientIp = request.getRemoteAddr();
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) clientIp = xff.split(",")[0].trim();

        String rateKey = "register:" + clientIp;
        int maxReq = registrationProperties.getRateLimitPerIp();
        int winMin = registrationProperties.getRateLimitWindowMinutes();
        if (!rateLimiterService.isAllowed(rateKey, maxReq, winMin)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, "注册请求过于频繁，请稍后再试"));
        }

        String username = req.username();
        String password = req.password();
        String nickname = req.nickname();

        if (userService.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "用户名已存在"));
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setRole("USER");
        user.setActive(true);

        userService.save(user);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentUser(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未提供有效的认证令牌"));
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "认证令牌无效或已过期"));
        }

        String username = jwtUtil.getUsernameFromToken(token);
        Optional<User> userOpt = userService.findByUsername(username);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "用户不存在"));
        }

        User user = userOpt.get();
        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("nickname", user.getNickname());
        response.put("role", user.getRole());
        response.put("avatar", avatarResolver.resolveAvatarUrl(user.getAvatar()));
        response.put("email", user.getEmail());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        response.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().format(formatter) : null);
        response.put("lastLoginTime", user.getLastLoginTime() != null ? user.getLastLoginTime().format(formatter) : null);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody @Valid UpdateProfileRequest req) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未提供有效的认证令牌"));
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "认证令牌无效或已过期"));
        }

        String username = jwtUtil.getUsernameFromToken(token);
        Optional<User> userOpt = userService.findByUsername(username);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "用户不存在"));
        }

        User user = userOpt.get();
        if (req.nickname() != null && !req.nickname().isBlank()) {
            user.setNickname(req.nickname());
        }
        if (req.email() != null) {
            user.setEmail(req.email().isBlank() ? null : req.email());
        }
        userService.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("id", user.getId());
        response.put("username", user.getUsername());
        response.put("nickname", user.getNickname());
        response.put("role", user.getRole());
        response.put("avatar", avatarResolver.resolveAvatarUrl(user.getAvatar()));
        response.put("email", user.getEmail());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        response.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().format(formatter) : null);
        response.put("lastLoginTime", user.getLastLoginTime() != null ? user.getLastLoginTime().format(formatter) : null);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody @Valid ChangePasswordRequest req) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未提供有效的认证令牌"));
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "认证令牌无效或已过期"));
        }

        String oldPassword = req.oldPassword();
        String newPassword = req.newPassword();

        String username = jwtUtil.getUsernameFromToken(token);
        Optional<User> userOpt = userService.findByUsername(username);

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "用户不存在"));
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "旧密码错误"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        Integer currentTv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        user.setTokenVersion(currentTv + 1);
        userService.save(user);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        // 1. 停止三个插件（best-effort，每个独立捕获异常）
        stopComponentSafe("AstrBot", () -> {
            try { astrBotService.stopAstrBot(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        stopComponentSafe("NapCat", () -> {
            try { napCatService.stopNapCat(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        stopComponentSafe("GPT-SoVITS", () -> {
            try { gptSovitsService.stopGptSovits(); } catch (Exception e) { throw new RuntimeException(e); }
        });

        // 2. 注销 JWT（best-effort，token 过期也不报错）
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                if (jwtUtil.validateToken(token)) {
                    String jti = jwtUtil.getJtiFromToken(token);
                    tokenBlacklistService.invalidateJti(jti);
                }
            } catch (Exception ignored) {
            }
        }

        return ResponseEntity.ok(ApiResponse.success());
    }

    private void stopComponentSafe(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("登出时停止 {} 失败（忽略）: {}", name, e.getMessage());
        }
    }
}
