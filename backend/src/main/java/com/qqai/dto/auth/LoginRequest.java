package com.qqai.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password,

        // 记住我：使用更长的登录有效期（默认 30 天）。为 null / false 时用默认 24 小时
        Boolean rememberMe
) {
}
