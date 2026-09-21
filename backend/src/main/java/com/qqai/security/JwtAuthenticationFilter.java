package com.qqai.security;

import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "qqai_token";

    private static final String[] PUBLIC_PATHS = {
            // 注意:聊天媒体 /images/**、/uploads/** 不在此列表 —— 它们必须经过本过滤器,
            // 让浏览器同源请求携带的 qqai_token Cookie 建立认证上下文后由静态资源处理器放行。
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/logout",
            "/api/system/health",
            // 注意:/api/system/napcat/qrcode-image 已从白名单移除(二维码=机器人账号接管入口,仅 ADMIN)。
            // 保留下面两个纯状态接口:登录页要在「Cookie 已过期」时仍能显示组件状态灯。
            "/api/system/napcat/login-status",
            "/api/system/component-status",
            "/",
            "/webhook",
            "/api/napcat",
            "/api/napcat/**",
            "/ws",
            "/ws/**",
            "/uploads/avatars/**",
    };

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private UserRepository userRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 公共路径(登录/注册/健康检查/头像等)完全跳过本过滤器:
        // 避免携带过期 Cookie 时拦截登录请求本身。
        String path = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        for (String pattern : PUBLIC_PATHS) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (!StringUtils.hasText(jwt)) {
                filterChain.doFilter(request, response);
                return;
            }

            // Token present but invalid - return 401 to trigger frontend re-login
            if (!jwtUtil.validateToken(jwt)) {
                reject(response);
                return;
            }

            String jti = jwtUtil.getJtiFromToken(jwt);
            if (tokenBlacklistService.isJtiBlacklisted(jti)) {
                reject(response);
                return;
            }

            Long userId = jwtUtil.getUserIdFromToken(jwt);
            Integer tvClaim = jwtUtil.getTokenVersionFromToken(jwt);
            String username = jwtUtil.getUsernameFromToken(jwt);
            String role = jwtUtil.getRoleFromToken(jwt);

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                reject(response);
                return;
            }

            User user = userOpt.get();
            if (!user.isActive()) {
                reject(response);
                return;
            }

            Integer dbTv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
            if (!dbTv.equals(tvClaim)) {
                reject(response);
                return;
            }

            AuthPrincipal principal = new AuthPrincipal(userId, username, role, tvClaim);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"登录已过期，请重新登录\",\"code\":401}");
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        // 1. Authorization: Bearer <token>
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // 2. HttpOnly Cookie(前端浏览器同源请求自动携带)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())
                        && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
