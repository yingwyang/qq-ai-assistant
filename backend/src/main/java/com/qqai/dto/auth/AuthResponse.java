package com.qqai.dto.auth;

/**
 * 登录响应体。
 *
 * 安全约定（安全收口）：**不再返回 JWT 明文**。
 * 令牌只通过 HttpOnly Cookie 下发，前端不持有也无法读取；一旦把 token 放进响应体，
 * 任何 XSS、前端日志或浏览器插件都能直接读走令牌，HttpOnly 的防 XSS 价值会被完全抵消。
 */
public record AuthResponse(
        Long id,
        String username,
        String nickname,
        String role,
        String avatar,
        String email,
        String createdAt,
        String lastLoginTime
) {
}
