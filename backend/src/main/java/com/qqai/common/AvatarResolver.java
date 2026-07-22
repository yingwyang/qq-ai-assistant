package com.qqai.common;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class AvatarResolver {

    public String resolveAvatarUrl(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            return null;
        }
        if (avatar.startsWith("http")) {
            return avatar;
        }
        String relative = avatar.startsWith("/") ? avatar.substring(1) : avatar;
        Path filePath = Paths.get(relative).toAbsolutePath().normalize();
        Path basePath = Paths.get("uploads/avatars").toAbsolutePath().normalize();
        if (!filePath.startsWith(basePath)) {
            return null;
        }
        return Files.exists(filePath) ? avatar : null;
    }
}
