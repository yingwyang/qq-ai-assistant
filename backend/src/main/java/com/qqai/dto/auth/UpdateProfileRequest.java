package com.qqai.dto.auth;

import jakarta.validation.constraints.Email;

public record UpdateProfileRequest(
        String nickname,

        @Email(message = "邮箱格式不正确")
        String email
) {
}