package com.qqai.security;

public record AuthPrincipal(
        Long userId,
        String username,
        String role,
        Integer tokenVersion
) {
}
