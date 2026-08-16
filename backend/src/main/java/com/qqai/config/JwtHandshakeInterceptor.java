package com.qqai.config;

import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import com.qqai.security.JwtUtil;
import com.qqai.security.TokenBlacklistService;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

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
        if (token == null || token.isEmpty() || !jwtUtil.validateToken(token)) {
            return false;
        }

        String jti = jwtUtil.getJtiFromToken(token);
        if (jti == null || tokenBlacklistService.isJtiBlacklisted(jti)) {
            return false;
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        Integer tvClaim = jwtUtil.getTokenVersionFromToken(token);
        if (userId == null) {
            return false;
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            return false;
        }
        Integer dbTv = userOpt.get().getTokenVersion() != null ? userOpt.get().getTokenVersion() : 0;
        if (!dbTv.equals(tvClaim)) {
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_USERNAME, jwtUtil.getUsernameFromToken(token));
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
