package com.qqai.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record QqBindRequest(
        @NotBlank
        @Pattern(regexp = "^[1-9]\\d{4,11}$", message = "QQ号格式不正确")
        String qqNumber,

        String nickname,

        String avatar
) {
}
