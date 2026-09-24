package com.qqai.config;

import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.websocket.FrontendMessageWebSocketHandler;
import com.qqai.websocket.NapCatWebSocketHandler;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 安全配置。
 *
 * 权限矩阵原则:
 * - 全局共享基础设施(组件启停、NapCat 配置、后台管理)一律 ADMIN;
 * - 聊天媒体 /images/**, /uploads/** 不再公开,必须登录后经同源 Cookie 访问,
 *   仅头像目录 /uploads/avatars/** 保持公开(群头像/用户头像展示场景);
 * - WebSocket 握手本身放行,鉴权由各自的 HandshakeInterceptor 完成
 *   (/ws = NapCat 上行,校验 napcat.webhook-token;/ws/messages = 前端下行,校验 JWT Cookie)。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableWebSocket
public class SecurityConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private NapCatWebSocketHandler napCatWebSocketHandler;

    @Autowired
    private FrontendMessageWebSocketHandler frontendMessageWebSocketHandler;

    @Autowired
    private JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Autowired
    private NapCatHandshakeInterceptor napCatHandshakeInterceptor;

    @Bean
    public Filter requestLoggingFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                            jakarta.servlet.http.HttpServletResponse response,
                                            jakarta.servlet.FilterChain filterChain)
                    throws jakarta.servlet.ServletException, java.io.IOException {
                long start = System.currentTimeMillis();
                String method = request.getMethod();
                String uri = request.getRequestURI();
                String query = request.getQueryString();
                try {
                    filterChain.doFilter(request, response);
                } finally {
                    int status = response.getStatus();
                    long ms = System.currentTimeMillis() - start;
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    String user = (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal()))
                            ? auth.getName() : "(anon)";
                    if (status >= 400) {
                        log.warn("HTTP {} {} {} -> {} user={} cost={}ms",
                                method, uri, (query != null ? "?" + query : ""), status, user, ms);
                    } else if (log.isDebugEnabled()) {
                        log.debug("HTTP {} {} {} -> {} user={} cost={}ms",
                                method, uri, (query != null ? "?" + query : ""), status, user, ms);
                    }
                }
            }
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) -> {
                    String uri = request.getRequestURI();
                    String user = request.getRemoteUser();
                    log.warn("401 UNAUTHORIZED: {} {} (remoteUser={}, reason={})",
                            request.getMethod(), uri, user,
                            authException != null ? authException.getMessage() : "none");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"未登录或登录已过期\",\"code\":401,\"path\":\""
                            + escapeJson(uri) + "\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    String uri = request.getRequestURI();
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    String user = (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal()))
                            ? auth.getName() : "(anon)";
                    String authorities = (auth != null) ? auth.getAuthorities().toString() : "[]";
                    log.error("403 FORBIDDEN: {} {} user={} authorities={} reason={}",
                            request.getMethod(), uri, user, authorities,
                            accessDeniedException != null ? accessDeniedException.getMessage() : "none");
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"权限不足，无法执行此操作\",\"code\":403,"
                            + "\"path\":\"" + escapeJson(uri) + "\","
                            + "\"user\":\"" + escapeJson(user) + "\","
                            + "\"authorities\":" + authorities + "}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                // 认证与健康检查
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/logout").permitAll()
                .requestMatchers("/api/system/health").permitAll()
                // NapCat 上行入口(token 在控制器内校验)
                .requestMatchers("/", "/webhook", "/api/napcat", "/api/napcat/**").permitAll()
                // WebSocket 握手(鉴权在拦截器中)
                .requestMatchers("/ws", "/ws/**").permitAll()
                // 头像:读取公开,上传/删除仅管理员
                .requestMatchers(HttpMethod.GET, "/api/avatar/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/avatar/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/avatar/**").hasRole("ADMIN")
                .requestMatchers("/uploads/avatars/**").permitAll()
                // 聊天媒体不再公开:任何登录用户经同源 Cookie 访问(前端 <img>/<audio> 自动携带 Cookie)
                // /images/** 与 /uploads/** 均落入 anyRequest().authenticated()
                // 但 <video> <img> 标签无法携带 JWT Header，需要允许同源 Cookie 访问
                .requestMatchers("/images/**").permitAll()
                .requestMatchers("/uploads/**").permitAll()
                // 管理后台
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/credits/admin/**").hasRole("ADMIN")
                // Actuator：健康/指标含依赖详情（DB、RabbitMQ），只给管理员看
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // 组件启停/配置:统一收口为 ADMIN,避免逐接口枚举遗漏
                .requestMatchers("/api/system/start-*", "/api/system/stop-*", "/api/system/restart-*").hasRole("ADMIN")
                .requestMatchers("/api/system/napcat/auto-configure").hasRole("ADMIN")
                // TTS 等资源消耗接口:登录即可用(积分在服务内扣减)
                .requestMatchers("/api/system/tts", "/api/system/tts/**", "/api/system/convert-voice").authenticated()
                // 机器人登录二维码:登录用户即可读 —— 扫码登录 QQ 是普通用户也要用的功能,
                // 此前收成 ADMIN-only 导致普通用户登录不了 QQ。
                // 匿名仍然拦住(此前是 permitAll,任何人无需登录即可拉二维码 = 接管机器人账号)。
                .requestMatchers("/api/system/napcat/qrcode", "/api/system/napcat/qrcode-path",
                        "/api/system/napcat/qrcode-image").authenticated()
                // 登录页状态灯:只暴露「运行中 / 已登录」这类布尔量,不含二维码与服务器路径,故保持公开
                .requestMatchers("/api/system/napcat/login-status", "/api/system/component-status").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(requestLoggingFilter(), org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // NapCat 上行通道:握手时校验 webhook token,防止伪造 QQ 消息
        registry.addHandler(napCatWebSocketHandler, "/ws")
                .addInterceptors(napCatHandshakeInterceptor)
                .setAllowedOrigins("*");

        // 前端下行通道:握手时校验 JWT Cookie/黑名单/tokenVersion
        registry.addHandler(frontendMessageWebSocketHandler, "/ws/messages")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
