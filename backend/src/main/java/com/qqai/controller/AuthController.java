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
import com.qqai.repository.UserRepository;
import com.qqai.security.AuthPrincipal;
import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.security.JwtUtil;
import com.qqai.security.TokenBlacklistService;
import com.qqai.service.CreditService;
import com.qqai.service.PluginEnsureService;
import com.qqai.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
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

    private static final String COOKIE_NAME = JwtAuthenticationFilter.COOKIE_NAME;

    /** 登录限流:同一 (IP+用户名) 每分钟最多 5 次,防暴力破解 */
    private static final int LOGIN_MAX_PER_MINUTE = 5;
    private static final int LOGIN_WINDOW_MINUTES = 1;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

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
    private PluginEnsureService pluginEnsureService;

    @Autowired
    private CreditService creditService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@RequestBody @Valid LoginRequest req,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) {
        String username = req.username();
        String password = req.password();
        String clientIp = resolveClientIp(request);

        // 防暴力破解:IP+用户名维度限流
        if (!rateLimiterService.isAllowed("login:" + clientIp + ":" + username,
                LOGIN_MAX_PER_MINUTE, LOGIN_WINDOW_MINUTES)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(429, "登录尝试过于频繁，请 1 分钟后再试"));
        }

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

        // 设置 HttpOnly Cookie(前端同源请求自动携带,不再需要 localStorage 存 token)
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(false) // 生产走 HTTPS 时应配置为 true
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofMillis(jwtUtil.getExpirationTime()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // 异步检查并启动三个插件（延迟 3 秒，不阻塞登录响应）
        pluginEnsureService.ensurePluginsStartedAsync();

        // 月卡每日登录额外积分（幂等，每日仅发放一次）
        try {
            creditService.grantMonthlyCardDailyBonus(user.getId());
        } catch (Exception e) {
            log.warn("用户{} 月卡每日登录奖励发放失败（忽略）: {}", user.getId(), e.getMessage());
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        AuthResponse authResponse = new AuthResponse(
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

        return ResponseEntity.ok(ApiResponse.success(authResponse));
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

        String clientIp = resolveClientIp(request);

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
        user.setTokenVersion(0);

        userService.save(user);

        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 获取当前登录用户信息(基于 SecurityContext,不再手动解析 token)
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCurrentUser() {
        Long userId = currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未提供有效的认证令牌"));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "用户不存在"));
        }

        User user = userOpt.get();
        Map<String, Object> responseMap = buildUserMap(user);

        // 自动登录场景（token 仍有效，未走 /login 接口）：补充触发插件启动
        // PluginEnsureService 有幂等性检查，不会重复启动已运行的插件
        pluginEnsureService.ensurePluginsStartedAsync();

        // 月卡每日登录额外积分（幂等，每日仅发放一次，覆盖自动登录场景）
        try {
            creditService.grantMonthlyCardDailyBonus(user.getId());
        } catch (Exception e) {
            log.warn("用户{} 月卡每日登录奖励发放失败（忽略）: {}", user.getId(), e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(responseMap));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(
            @RequestBody @Valid UpdateProfileRequest req) {
        Long userId = currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未提供有效的认证令牌"));
        }

        Optional<User> userOpt = userRepository.findById(userId);
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

        return ResponseEntity.ok(ApiResponse.success(buildUserMap(user)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@RequestBody @Valid ChangePasswordRequest req) {
        Long userId = currentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "未提供有效的认证令牌"));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "用户不存在"));
        }

        String newPassword = req.newPassword();
        if (newPassword == null || !newPassword.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,64}$")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "新密码强度不足，至少 8 位且包含大小写字母与数字"));
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(req.oldPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "旧密码错误"));
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        Integer currentTv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        user.setTokenVersion(currentTv + 1);
        userService.save(user);

        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 登出:仅注销当前 JWT 并清除 Cookie。
     * 不再停止 AstrBot/NapCat/GPT-SoVITS —— 这些是全体用户共享的基础设施,
     * 任何单个用户登出都不应关停它们(由管理员通过 /api/system/stop-* 显式控制)。
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response) {
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        if (token == null) {
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (jakarta.servlet.http.Cookie c : cookies) {
                    if (COOKIE_NAME.equals(c.getName())) {
                        token = c.getValue();
                        break;
                    }
                }
            }
        }

        // 注销 JWT(best-effort,token 过期也不报错)
        if (token != null && !token.isEmpty()) {
            try {
                if (jwtUtil.validateToken(token)) {
                    String jti = jwtUtil.getJtiFromToken(token);
                    tokenBlacklistService.invalidateJti(jti);
                }
            } catch (Exception ignored) {
                // ignore
            }
        }

        // 清除 HttpOnly Cookie
        ResponseCookie expired = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());

        return ResponseEntity.ok(ApiResponse.success());
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private Map<String, Object> buildUserMap(User user) {
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("id", user.getId());
        responseMap.put("username", user.getUsername());
        responseMap.put("nickname", user.getNickname());
        responseMap.put("role", user.getRole());
        responseMap.put("avatar", avatarResolver.resolveAvatarUrl(user.getAvatar()));
        responseMap.put("email", user.getEmail());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        responseMap.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().format(formatter) : null);
        responseMap.put("lastLoginTime", user.getLastLoginTime() != null ? user.getLastLoginTime().format(formatter) : null);
        return responseMap;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) clientIp = xff.split(",")[0].trim();
        return clientIp;
    }
}
