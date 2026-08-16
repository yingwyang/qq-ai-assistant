package com.qqai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * NapCat 上行 WebSocket (/ws) 握手拦截器。
 *
 * 校验方式(任一满足即通过):
 * 1. query 参数 access_token = napcat.webhook-token
 * 2. header Authorization = "Bearer <token>" 或直接等于 token
 *
 * 未配置 webhook token 时直接拒绝握手(fail closed),
 * 防止无认证的外部客户端通过 /ws 伪造 QQ 消息写入数据库。
 */
@Component
public class NapCatHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(NapCatHandshakeInterceptor.class);

    @Value("${napcat.webhook-token:}")
    private String webhookToken;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (webhookToken == null || webhookToken.isBlank()) {
            log.error("【安全】napcat.webhook-token 未配置,拒绝 NapCat WebSocket 握手");
            return false;
        }

        // query 参数 access_token
        if (request.getURI() != null && request.getURI().getQuery() != null) {
            String query = request.getURI().getQuery();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "access_token".equals(pair.substring(0, eq))
                        && webhookToken.equals(pair.substring(eq + 1))) {
                    return true;
                }
            }
        }

        // header Authorization
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String auth = servletRequest.getServletRequest().getHeader("Authorization");
            if (auth != null && (auth.equals("Bearer " + webhookToken) || auth.equals(webhookToken))) {
                return true;
            }
        }

        log.warn("【安全】NapCat WebSocket 握手 token 校验失败,拒绝连接");
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
