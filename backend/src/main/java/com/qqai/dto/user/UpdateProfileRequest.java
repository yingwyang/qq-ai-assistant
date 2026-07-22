package com.qqai.dto.user;

public record UpdateProfileRequest(
        String nickname,

        String avatar
) {
}
