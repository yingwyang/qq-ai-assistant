package com.qqai.service;

import com.qqai.common.AvatarResolver;
import com.qqai.entity.User;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserQqBindingRepository userQqBindingRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AvatarResolver avatarResolver;

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public Page<User> findAllPaginated(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    public Page<User> searchUsers(String keyword, Pageable pageable) {
        return userRepository.findByUsernameContainingOrNicknameContaining(keyword, keyword, pageable);
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    public void delete(User user) {
        userRepository.delete(user);
    }

    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    public User register(String username, String password, String nickname) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setRole("USER");
        user.setActive(true);
        return userRepository.save(user);
    }

    public void updateLastLoginTime(User user) {
        user.setLastLoginTime(LocalDateTime.now());
        userRepository.save(user);
    }

    public boolean changePassword(User user, String oldPassword, String newPassword) {
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return false;
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        Integer currentTv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        user.setTokenVersion(currentTv + 1);
        userRepository.save(user);
        return true;
    }

    public Optional<User> updateUserRole(Long id, String role) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setRole(role);
            userRepository.save(user);
        }
        return userOpt;
    }

    public Optional<User> updateUserActive(Long id, boolean active) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setActive(active);
            Integer currentTv = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
            user.setTokenVersion(currentTv + 1);
            userRepository.save(user);
        }
        return userOpt;
    }

    public Optional<User> updateProfile(Long userId, Map<String, String> updates) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (updates.containsKey("nickname")) {
                user.setNickname(updates.get("nickname"));
            }
            if (updates.containsKey("avatar")) {
                user.setAvatar(updates.get("avatar"));
            }
            userRepository.save(user);
        }
        return userOpt;
    }

    public String uploadAvatar(Long userId, MultipartFile file) throws IOException {
        String uploadDir = "uploads/avatars/users";
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".jpg";
        String filename = userId + "_" + System.currentTimeMillis() + extension;

        Path filePath = uploadPath.resolve(filename);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        String avatarUrl = "/uploads/avatars/users/" + filename;

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setAvatar(avatarUrl);
            userRepository.save(user);
        }
        return avatarUrl;
    }

    // ========== QQ Binding methods ==========

    public List<UserQqBinding> findQqBindingsByUserIdAndActiveTrue(Long userId) {
        return userQqBindingRepository.findByUserIdAndActiveTrue(userId);
    }

    public Optional<UserQqBinding> findQqBindingById(Long bindingId) {
        return userQqBindingRepository.findById(bindingId);
    }

    public Optional<UserQqBinding> findDefaultQqBindingByUserId(Long userId) {
        return userQqBindingRepository.findByUserIdAndIsDefaultTrue(userId);
    }

    public boolean existsByUserIdAndQqNumberAndActiveTrue(Long userId, String qqNumber) {
        return userQqBindingRepository.existsByUserIdAndQqNumberAndActiveTrue(userId, qqNumber);
    }

    public UserQqBinding bindQqAccount(Long userId, String qqNumber, String nickname, String avatar) {
        Optional<UserQqBinding> existingBindingOpt = userQqBindingRepository.findByUserIdAndQqNumber(userId, qqNumber);
        if (existingBindingOpt.isPresent()) {
            UserQqBinding existing = existingBindingOpt.get();
            existing.setActive(true);
            existing.setNickname(nickname);
            existing.setAvatar(avatar);
            return userQqBindingRepository.save(existing);
        }

        UserQqBinding binding = new UserQqBinding();
        binding.setUserId(userId);
        binding.setQqNumber(qqNumber);
        binding.setNickname(nickname);
        binding.setAvatar(avatar);
        binding.setActive(true);
        long bindingCount = userQqBindingRepository.countByUserIdAndActiveTrue(userId);
        binding.setDefault(bindingCount == 0);
        return userQqBindingRepository.save(binding);
    }

    public void unbindQqAccount(Long userId, Long bindingId) {
        Optional<UserQqBinding> bindingOpt = userQqBindingRepository.findById(bindingId);
        if (bindingOpt.isEmpty()) {
            throw new RuntimeException("绑定记录不存在");
        }
        UserQqBinding binding = bindingOpt.get();
        if (!binding.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作");
        }
        binding.setActive(false);
        userQqBindingRepository.save(binding);

        if (binding.isDefault()) {
            List<UserQqBinding> remainingBindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
            if (!remainingBindings.isEmpty()) {
                remainingBindings.get(0).setDefault(true);
                userQqBindingRepository.save(remainingBindings.get(0));
            }
        }
    }

    public void setDefaultQqAccount(Long userId, Long bindingId) {
        Optional<UserQqBinding> bindingOpt = userQqBindingRepository.findById(bindingId);
        if (bindingOpt.isEmpty()) {
            throw new RuntimeException("绑定记录不存在");
        }
        UserQqBinding binding = bindingOpt.get();
        if (!binding.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作");
        }

        List<UserQqBinding> userBindings = userQqBindingRepository.findByUserIdAndActiveTrue(userId);
        for (UserQqBinding b : userBindings) {
            if (b.isDefault()) {
                b.setDefault(false);
                userQqBindingRepository.save(b);
            }
        }

        binding.setDefault(true);
        userQqBindingRepository.save(binding);
    }
}
