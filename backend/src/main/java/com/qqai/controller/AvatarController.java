package com.qqai.controller;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
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

    private static final String AVATAR_DIR = "uploads/avatars";

    /**
     * 上传头像文件
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadAvatar(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "type", defaultValue = "bot") String type) {
        JSONObject result = new JSONObject();

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
            // 确保上传目录存在
            Path uploadPath = Paths.get(AVATAR_DIR);
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
            String avatarUrl = "/uploads/avatars/" + filename;

            result.put("status", "ok");
            result.put("message", "上传成功");
            result.put("url", avatarUrl);

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
        JSONObject result = new JSONObject();

        try {
            // 提取文件名
            String filename = url.substring(url.lastIndexOf("/") + 1);
            Path filePath = Paths.get(AVATAR_DIR, filename);

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