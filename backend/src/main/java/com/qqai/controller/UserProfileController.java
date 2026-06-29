package com.qqai.controller;

import com.qqai.entity.User;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@RestController
@RequestMapping("/api/user")
public class UserProfileController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    /**
     * 校验头像文件是否真实存在，不存在则返回 null，避免前端请求 404
     */
    private String resolveAvatarUrl(String avatar) {
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

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            String username = authentication.getName();
            // 从数据库查询用户ID
            Optional<User> userOpt = userRepository.findByUsername(username);
            return userOpt.map(User::getId).orElse(null);
        }
        return null;
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getUserProfile() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "用户不存在", "code", 404));
        }

        User user = userOpt.get();
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId());
        profile.put("username", user.getUsername());
        profile.put("nickname", user.getNickname());
        profile.put("role", user.getRole());
        profile.put("avatar", resolveAvatarUrl(user.getAvatar()));
        profile.put("createdAt", user.getCreatedAt());

        return ResponseEntity.ok(profile);
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateUserProfile(@RequestBody Map<String, String> updates) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "用户不存在", "code", 404));
        }

        User user = userOpt.get();
        
        if (updates.containsKey("nickname")) {
            user.setNickname(updates.get("nickname"));
        }
        if (updates.containsKey("avatar")) {
            user.setAvatar(updates.get("avatar"));
        }

        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "更新成功"));
    }

    /**
     * 获取用户绑定的所有QQ账号
     */
    @GetMapping("/qq-bindings")
    public ResponseEntity<?> getUserQqBindings() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        List<UserQqBinding> bindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (UserQqBinding binding : bindings) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", binding.getId());
            item.put("qqNumber", binding.getQqNumber());
            item.put("nickname", binding.getNickname());
            // 如果没有存储头像，使用QQ官方头像CDN
            String avatar = binding.getAvatar();
            if (avatar == null || avatar.isEmpty()) {
                avatar = "https://q.qlogo.cn/headimg_dl?dst_uin=" + binding.getQqNumber() + "&spec=100";
            }
            item.put("avatar", avatar);
            item.put("isDefault", binding.isDefault());
            item.put("createdAt", binding.getCreatedAt());
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 绑定QQ账号
     */
    @PostMapping("/qq-bindings")
    public ResponseEntity<?> bindQqAccount(@RequestBody Map<String, String> request) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        String qqNumber = request.get("qqNumber");
        String nickname = request.get("nickname");
        String avatar = request.get("avatar");

        if (qqNumber == null || qqNumber.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "QQ号不能为空", "code", 400));
        }

        // 检查是否已绑定（只检查激活状态的）
        if (userQqBindingRepository.existsByUserIdAndQqNumberAndActiveTrue(userId, qqNumber)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "该QQ号已绑定", "code", 400));
        }

        // 检查是否有已解绑的记录，有则重新激活
        Optional<UserQqBinding> existingBindingOpt = userQqBindingRepository.findByUserIdAndQqNumber(userId, qqNumber);
        if (existingBindingOpt.isPresent()) {
            UserQqBinding existing = existingBindingOpt.get();
            existing.setActive(true);
            existing.setNickname(nickname);
            existing.setAvatar(avatar);
            userQqBindingRepository.save(existing);
            return ResponseEntity.ok(Map.of("message", "绑定成功", "qqNumber", qqNumber));
        }

        // 创建绑定记录
        UserQqBinding binding = new UserQqBinding();
        binding.setUserId(userId);
        binding.setQqNumber(qqNumber);
        binding.setNickname(nickname);
        binding.setAvatar(avatar);
        binding.setActive(true);
        
        // 如果是第一个绑定的账号，设为默认
        long bindingCount = userQqBindingRepository.countByUserIdAndActiveTrue(userId);
        binding.setDefault(bindingCount == 0);

        userQqBindingRepository.save(binding);

        return ResponseEntity.ok(Map.of("message", "绑定成功", "qqNumber", qqNumber));
    }

    /**
     * 解绑QQ账号
     */
    @DeleteMapping("/qq-bindings/{bindingId}")
    public ResponseEntity<?> unbindQqAccount(@PathVariable Long bindingId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<UserQqBinding> bindingOpt = userQqBindingRepository.findById(bindingId);
        if (bindingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "绑定记录不存在", "code", 404));
        }

        UserQqBinding binding = bindingOpt.get();
        if (!binding.getUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权操作", "code", 403));
        }

        // 软删除
        binding.setActive(false);
        userQqBindingRepository.save(binding);

        // 如果解绑的是默认账号，且还有其他激活的账号，将第一个设为默认
        if (binding.isDefault()) {
            List<UserQqBinding> remainingBindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
            if (!remainingBindings.isEmpty()) {
                remainingBindings.get(0).setDefault(true);
                userQqBindingRepository.save(remainingBindings.get(0));
            }
        }

        return ResponseEntity.ok(Map.of("message", "解绑成功"));
    }

    /**
     * 设置默认QQ账号
     */
    @PutMapping("/qq-bindings/{bindingId}/default")
    public ResponseEntity<?> setDefaultQqAccount(@PathVariable Long bindingId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<UserQqBinding> bindingOpt = userQqBindingRepository.findById(bindingId);
        if (bindingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "绑定记录不存在", "code", 404));
        }

        UserQqBinding binding = bindingOpt.get();
        if (!binding.getUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "无权操作", "code", 403));
        }

        // 取消其他默认账号（只考虑激活的）
        List<UserQqBinding> userBindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
        for (UserQqBinding b : userBindings) {
            if (b.isDefault()) {
                b.setDefault(false);
                userQqBindingRepository.save(b);
            }
        }

        // 设置新的默认账号
        binding.setDefault(true);
        userQqBindingRepository.save(binding);

        return ResponseEntity.ok(Map.of("message", "设置成功"));
    }

    /**
     * 获取当前用户的默认QQ账号
     */
    @GetMapping("/qq-bindings/default")
    public ResponseEntity<?> getDefaultQqBinding() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<UserQqBinding> bindingOpt = userQqBindingRepository.findByUserIdAndIsDefaultTrue(userId);
        if (bindingOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("qqNumber", null, "message", "未设置默认QQ账号"));
        }

        UserQqBinding binding = bindingOpt.get();
        Map<String, Object> result = new HashMap<>();
        result.put("id", binding.getId());
        result.put("qqNumber", binding.getQqNumber());
        result.put("nickname", binding.getNickname());
        result.put("avatar", binding.getAvatar());

        return ResponseEntity.ok(result);
    }

    /**
     * 上传用户头像
     */
    @PostMapping("/avatar")
    public ResponseEntity<?> uploadAvatar(@RequestPart("file") MultipartFile file) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "请选择文件", "code", 400));
        }

        // 检查文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "只支持图片文件", "code", 400));
        }

        // 检查文件大小 (最大 5MB)
        if (file.getSize() > 5 * 1024 * 1024) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "文件大小不能超过5MB", "code", 400));
        }

        try {
            // 创建上传目录（用户头像隔离到 uploads/avatars/users）
            String uploadDir = "uploads/avatars/users";
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 生成文件名
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".jpg";
            String filename = userId + "_" + System.currentTimeMillis() + extension;

            // 保存文件
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // 生成访问URL
            String avatarUrl = "/uploads/avatars/users/" + filename;

            // 更新用户头像
            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                user.setAvatar(avatarUrl);
                userRepository.save(user);
            }

            return ResponseEntity.ok(Map.of(
                    "message", "上传成功",
                    "avatarUrl", avatarUrl
            ));

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "上传失败: " + e.getMessage(), "code", 500));
        }
    }
}
