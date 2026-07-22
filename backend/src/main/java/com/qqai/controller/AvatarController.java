package com.qqai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 头像上传控制器
 */
@RestController
@RequestMapping("/api/avatar")
public class AvatarController {

    private static final String AVATAR_BASE_DIR = "uploads/avatars";
    private static final String GROUP_AVATAR_DIR = "uploads/avatars/groups";
    private static final String USER_AVATAR_DIR = "uploads/avatars/users";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 上传头像文件
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadAvatar(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "type", defaultValue = "bot") String type) {
        ObjectNode result = objectMapper.createObjectNode();

        if (file.isEmpty()) {
            result.put("status", "error");
            result.put("message", "请选择要上传的文件");
            return ResponseEntity.ok(result);
        }

        // 验证文件类型
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !isValidImage(originalFilename)) {
            result.put("status", "error");
            result.put("message", "只支持图片格式（jpg, jpeg, png, gif, webp）");
            return ResponseEntity.ok(result);
        }

        try {
            // 根据类型选择隔离目录：group 类型存到 groups，其他存到 users
            String uploadDir = "group".equalsIgnoreCase(type) ? GROUP_AVATAR_DIR : USER_AVATAR_DIR;
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 生成唯一文件名
            String extension = getFileExtension(originalFilename);
            String filename = type + "_" + UUID.randomUUID().toString() + extension;
            Path filePath = uploadPath.resolve(filename);

            // 保存文件
            Files.write(filePath, file.getBytes());

            // 返回文件访问路径
            String subPath = "group".equalsIgnoreCase(type) ? "groups" : "users";
            String avatarUrl = "/uploads/avatars/" + subPath + "/" + filename;

            result.put("status", "ok");
            result.put("message", "上传成功");
            result.put("url", avatarUrl);
            result.put("avatarUrl", avatarUrl);

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", "文件保存失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 删除头像文件
     */
    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteAvatar(@RequestParam("url") String url) {
        ObjectNode result = objectMapper.createObjectNode();

        try {
            // 从 URL 中解析相对于 uploads/avatars 的路径，支持 groups/users 子目录及旧路径
            String prefix = "/uploads/avatars/";
            int prefixIndex = url.indexOf(prefix);
            if (prefixIndex < 0) {
                result.put("status", "error");
                result.put("message", "非法头像地址");
                return ResponseEntity.ok(result);
            }

            String relativePath = url.substring(prefixIndex + prefix.length());
            if (relativePath.isEmpty() || relativePath.contains("..")) {
                result.put("status", "error");
                result.put("message", "非法头像地址");
                return ResponseEntity.ok(result);
            }

            Path filePath = Paths.get(AVATAR_BASE_DIR, relativePath).normalize();
            Path basePath = Paths.get(AVATAR_BASE_DIR).toAbsolutePath().normalize();
            if (!filePath.startsWith(basePath)) {
                result.put("status", "error");
                result.put("message", "非法头像地址");
                return ResponseEntity.ok(result);
            }

            if (Files.exists(filePath)) {
                Files.delete(filePath);
                result.put("status", "ok");
                result.put("message", "删除成功");
            } else {
                result.put("status", "error");
                result.put("message", "文件不存在");
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    private boolean isValidImage(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".gif") ||
               lower.endsWith(".webp");
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf(".");
        if (lastDot == -1) {
            return ".png";
        }
        return filename.substring(lastDot);
    }
}