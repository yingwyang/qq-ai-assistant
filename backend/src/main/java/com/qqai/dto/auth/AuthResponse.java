package com.qqai.dto.auth;

public record AuthResponse(
        String token,
        String username,
        String nickname,
        String role,
        String avatar,
        String email,
        String createdAt,
        String lastLoginTime
) {
}
