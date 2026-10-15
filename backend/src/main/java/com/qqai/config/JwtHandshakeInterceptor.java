package com.qqai.config;

import com.qqai.repository.UserRepository;
import com.qqai.security.JwtUtil;
import com.qqai.security.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 前端下行 WebSocket (/ws/messages) 握手拦截器。
 *
 * 认证方式:优先读取 HttpOnly Cookie `qqai_token`(同源浏览器自动携带);
 * 兼容旧客户端通过 query 参数 token 传递。
 * 校验:签名/过期 + JTI 黑名单 + 用户存在 + tokenVersion 一致,
 * 全部通过后将 userId/username 写入 handshake attributes,
 * 供 FrontendMessageWebSocketHandler 做订阅鉴权。
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String COOKIE_NAME = "qqai_token";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }

        String token = readToken(servletRequest);
        if (token == null || token.isEmpty()) {
            return false;
        }

        // 一次解析拿全部声明（原先 validateToken + 逐个 getXxxFromToken 会对同一 token 解析 4 遍）
        Claims claims = jwtUtil.parseTokenSafely(token);
        if (claims == null) {
            return false;
        }

        String jti = claims.getId();
        if (jti == null || tokenBlacklistService.isJtiBlacklisted(jti)) {
            return false;
        }

        Long userId = claims.get("uid", Long.class);
        Integer tvClaim = claims.get("tv", Integer.class);
        if (userId == null) {
            return false;
        }

        UserRepository.AuthSnapshot snapshot = userRepository.findAuthSnapshotById(userId).orElse(null);
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.getActive())) {
            return false;
        }
        Integer dbTv = snapshot.getTokenVersion() != null ? snapshot.getTokenVersion() : 0;
        if (!dbTv.equals(tvClaim)) {
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_USERNAME, claims.getSubject());
        return true;
    }

    private String readToken(ServletServerHttpRequest servletRequest) {
        // 1. HttpOnly Cookie(前端标准路径)
        Cookie[] cookies = servletRequest.getServletRequest().getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        // 2. 兼容旧客户端:query 参数 token
        String queryToken = servletRequest.getServletRequest().getParameter("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
