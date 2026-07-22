package com.qqai.controller;

import com.qqai.common.AvatarResolver;
import com.qqai.common.SecurityHelper;
import com.qqai.entity.User;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.service.QqVerificationService;
import com.qqai.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/user")
public class UserProfileController {

    private static final Logger log = LoggerFactory.getLogger(UserProfileController.class);

    @Autowired
    private UserService userService;

    @Autowired
    private AvatarResolver avatarResolver;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private QqVerificationService qqVerificationService;

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/profile")
    public ResponseEntity<?> getUserProfile() {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<User> userOpt = userService.findById(userId);
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
        profile.put("avatar", avatarResolver.resolveAvatarUrl(user.getAvatar()));
        profile.put("createdAt", user.getCreatedAt());

        return ResponseEntity.ok(profile);
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/profile")
    public ResponseEntity<?> updateUserProfile(@RequestBody Map<String, String> updates) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<User> userOpt = userService.updateProfile(userId, updates);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "用户不存在", "code", 404));
        }

        return ResponseEntity.ok(Map.of("message", "更新成功"));
    }

    /**
     * 获取用户绑定的所有QQ账号
     */
    @GetMapping("/qq-bindings")
    public ResponseEntity<?> getUserQqBindings() {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        List<UserQqBinding> bindings = userService.findQqBindingsByUserIdAndActiveTrue(userId);
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
     * 发送QQ绑定验证码
     */
    @PostMapping("/qq-bindings/send-code")
    public ResponseEntity<?> sendQqBindingCode(@RequestBody Map<String, String> request) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        String qqNumber = request.get("qqNumber");
        if (qqNumber == null || qqNumber.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "QQ号不能为空", "code", 400));
        }

        // 检查是否已绑定（只检查激活状态的）
        boolean alreadyBound = userService.existsByUserIdAndQqNumberAndActiveTrue(userId, qqNumber);
        log.info("发送验证码前检查 - userId={}, qqNumber={}, alreadyBound={}", userId, qqNumber, alreadyBound);
        if (alreadyBound) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "该QQ号已绑定", "code", 400));
        }

        // 检查该QQ是否已被其他用户绑定（全局唯一）
        List<UserQqBinding> existingBindings = userQqBindingRepository.findByQqNumberAndActiveTrue(qqNumber);
        log.info("全局绑定检查 - qqNumber={}, existingBindings数量={}", qqNumber, existingBindings.size());
        for (UserQqBinding binding : existingBindings) {
            log.info("绑定记录 - id={}, userId={}, active={}", binding.getId(), binding.getUserId(), binding.isActive());
            if (!binding.getUserId().equals(userId)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "该QQ号已被其他用户绑定", "code", 400));
            }
        }

        // 检查是否已有未过期的验证码
        if (qqVerificationService.hasValidCode(qqNumber)) {
            return ResponseEntity.ok(Map.of("message", "验证码已发送，请检查QQ私信", "code", 200));
        }

        // 发送验证码
        boolean sent = qqVerificationService.sendVerificationCode(qqNumber);
        if (sent) {
            return ResponseEntity.ok(Map.of(
                "message", "验证码已发送到您的QQ私信，请查收",
                "expiresIn", 300  // 5分钟 = 300秒
            ));
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "验证码发送失败，请检查QQ号是否正确或稍后重试", "code", 500));
        }
    }

    /**
     * 绑定QQ账号（需要验证码）
     */
    @PostMapping("/qq-bindings")
    public ResponseEntity<?> bindQqAccount(@RequestBody Map<String, String> request) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        String qqNumber = request.get("qqNumber");
        String nickname = request.get("nickname");
        String avatar = request.get("avatar");
        String verificationCode = request.get("verificationCode");

        if (qqNumber == null || qqNumber.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "QQ号不能为空", "code", 400));
        }

        if (verificationCode == null || verificationCode.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "请输入验证码", "code", 400));
        }

        // 检查是否已绑定（只检查激活状态的）
        boolean alreadyBound = userService.existsByUserIdAndQqNumberAndActiveTrue(userId, qqNumber);
        log.info("发送验证码前检查 - userId={}, qqNumber={}, alreadyBound={}", userId, qqNumber, alreadyBound);
        if (alreadyBound) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "该QQ号已绑定", "code", 400));
        }

        // 检查该QQ是否已被其他用户绑定（全局唯一）
        List<UserQqBinding> existingBindings = userQqBindingRepository.findByQqNumberAndActiveTrue(qqNumber);
        log.info("全局绑定检查 - qqNumber={}, existingBindings数量={}", qqNumber, existingBindings.size());
        for (UserQqBinding binding : existingBindings) {
            log.info("绑定记录 - id={}, userId={}, active={}", binding.getId(), binding.getUserId(), binding.isActive());
            if (!binding.getUserId().equals(userId)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "该QQ号已被其他用户绑定", "code", 400));
            }
        }

        // 验证验证码
        QqVerificationService.VerificationResult result = qqVerificationService.verifyCode(qqNumber, verificationCode);
        switch (result) {
            case NO_CODE:
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "请先获取验证码", "code", 400));
            case EXPIRED:
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "验证码已过期，请重新获取", "code", 400));
            case INVALID:
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "验证码错误，请重新输入", "code", 400));
            case SUCCESS:
                // 验证通过，执行绑定
                userService.bindQqAccount(userId, qqNumber, nickname, avatar);
                return ResponseEntity.ok(Map.of("message", "绑定成功", "qqNumber", qqNumber));
            default:
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "验证失败", "code", 400));
        }
    }

    /**
     * 解绑QQ账号
     */
    @DeleteMapping("/qq-bindings/{bindingId}")
    public ResponseEntity<?> unbindQqAccount(@PathVariable Long bindingId) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        try {
            userService.unbindQqAccount(userId, bindingId);
            return ResponseEntity.ok(Map.of("message", "解绑成功"));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if ("绑定记录不存在".equals(msg)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", msg, "code", 404));
            }
            if ("无权操作".equals(msg)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", msg, "code", 403));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg, "code", 400));
        }
    }

    /**
     * 设置默认QQ账号
     */
    @PutMapping("/qq-bindings/{bindingId}/default")
    public ResponseEntity<?> setDefaultQqAccount(@PathVariable Long bindingId) {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        try {
            userService.setDefaultQqAccount(userId, bindingId);
            return ResponseEntity.ok(Map.of("message", "设置成功"));
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if ("绑定记录不存在".equals(msg)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", msg, "code", 404));
            }
            if ("无权操作".equals(msg)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", msg, "code", 403));
            }
            return ResponseEntity.badRequest().body(Map.of("error", msg, "code", 400));
        }
    }

    /**
     * 获取当前用户的默认QQ账号
     */
    @GetMapping("/qq-bindings/default")
    public ResponseEntity<?> getDefaultQqBinding() {
        Long userId = securityHelper.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "未登录", "code", 401));
        }

        Optional<UserQqBinding> bindingOpt = userService.findDefaultQqBindingByUserId(userId);
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
        Long userId = securityHelper.getCurrentUserId();
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
            String avatarUrl = userService.uploadAvatar(userId, file);
            return ResponseEntity.ok(Map.of(
                    "message", "上传成功",
                    "avatarUrl", avatarUrl
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "上传失败: " + e.getMessage(), "code", 500));
        }
    }
}
