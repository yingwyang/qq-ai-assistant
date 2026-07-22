package com.qqai.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 20, message = "用户名长度为 3-20 个字符")
        String username,

        @NotBlank
        @Size(min = 8, max = 64, message = "密码长度为 8-64 个字符")
        String password,

        String nickname
) {
}
