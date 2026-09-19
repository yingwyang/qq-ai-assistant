package com.qqai.service;

import com.qqai.common.AvatarResolver;
import com.qqai.entity.User;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private UserCreditRepository userCreditRepository;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private SubscriptionOrderRepository subscriptionOrderRepository;

    @Autowired
    private SignInRecordRepository signInRecordRepository;

    @Autowired
    private MonthlyBonusRecordRepository monthlyBonusRecordRepository;

    @Autowired
    private AstrBotConversationRepository astrBotConversationRepository;

    @Autowired
    private AstrBotMessageRepository astrBotMessageRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Autowired
    private GroupReadStateRepository readStateRepository;

    @Autowired
    private FileStorageService fileStorageService;

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

    /** 「可用管理员」= role 为 ADMIN 且未被禁用；后台至少要留一位 */
    public long countActiveAdmins() {
        return userRepository.countByRoleIgnoreCaseAndActiveTrue("ADMIN");
    }

    /** 该用户当前是否算「可用管理员」 */
    public boolean isActiveAdmin(User user) {
        return user != null
                && user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole())
                && user.isActive();
    }

    /**
     * 判断某次操作会不会让系统失去最后一位可用管理员。
     *
     * <p>覆盖三种会让管理员无法登录后台的操作：降权、禁用、删除。
     * 其它情况（操作对象不是可用管理员、或还有别的可用管理员）一律放行。</p>
     *
     * @param target      被操作的用户
     * @param willLoseAdmin 这次操作是否会让该用户失去「可用管理员」身份
     */
    public boolean wouldRemoveLastAdmin(User target, boolean willLoseAdmin) {
        if (!willLoseAdmin || !isActiveAdmin(target)) return false;
        return countActiveAdmins() <= 1;
    }

    /**
     * 管理端用户查询：关键字 + 角色 + 启用状态 + 排序。
     *
     * <p>组合条件用 Specification 组装（关键字 3 选 1 × 角色 × 状态 × 排序会退化成大量派生方法）；
     * 排序字段走白名单，非法值回落到注册时间倒序。</p>
     *
     * @param keyword 账号/昵称关键字（可为空）
     * @param role    ADMIN / USER（可为空）
     * @param active  true 只看启用、false 只看禁用、null 不限
     * @param sort    createdAt / lastLoginTime / username（默认 createdAt）
     * @param order   asc / desc（默认 desc）
     */
    public Page<User> queryUsers(String keyword, String role, Boolean active,
                                 String sort, String order, Pageable pageable) {
        final String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        final String roleFilter = role == null || role.isBlank() ? null : role.trim().toUpperCase();

        org.springframework.data.jpa.domain.Specification<User> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (kw != null) {
                String like = "%" + kw.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("username")), like),
                        cb.like(cb.lower(root.get("nickname")), like)));
            }
            if (roleFilter != null) {
                predicates.add(cb.equal(cb.upper(root.get("role")), roleFilter));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            return predicates.isEmpty() ? cb.conjunction()
                    : cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return userRepository.findAll(spec, applyUserSort(pageable, sort, order));
    }

    /** 管理端用户列表排序字段白名单 */
    public static final java.util.Set<String> USER_SORT_FIELDS =
            java.util.Set.of("createdAt", "lastLoginTime", "username", "id");

    private Pageable applyUserSort(Pageable pageable, String sort, String order) {
        String field = sort == null || !USER_SORT_FIELDS.contains(sort) ? "createdAt" : sort;
        org.springframework.data.domain.Sort.Direction direction =
                "asc".equalsIgnoreCase(order == null ? "" : order)
                        ? org.springframework.data.domain.Sort.Direction.ASC
                        : org.springframework.data.domain.Sort.Direction.DESC;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                org.springframework.data.domain.Sort.by(direction, field));
    }

    /**
     * 导出用：按同一套筛选条件取出全部用户（带上限，避免一次性拉爆内存）。
     *
     * @return 用户列表与「是否被截断」
     */
    public UserExportResult exportUsers(String keyword, String role, Boolean active,
                                        String sort, String order, int maxRows) {
        Page<User> page = queryUsers(keyword, role, active, sort, order, PageRequest.of(0, maxRows));
        boolean truncated = page.getTotalElements() > page.getContent().size();
        return new UserExportResult(page.getContent(), page.getTotalElements(), truncated);
    }

    /** 导出结果（内容 + 总条数 + 是否截断） */
    public record UserExportResult(List<User> users, long totalElements, boolean truncated) {
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    @Transactional
    public void delete(User user) {
        Long userId = user.getId();
        log.info("级联删除用户关联数据, userId={}", userId);

        // 0. 先获取用户绑定的 QQ 号（在删除绑定之前）
        List<UserQqBinding> bindings = userQqBindingRepository.findByUserId(userId);
        List<String> boundQqNumbers = bindings.stream()
                .map(UserQqBinding::getQqNumber)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());

        // 0.1 清理群聊、消息、文件（基于绑定的 QQ 号）
        if (!boundQqNumbers.isEmpty()) {
            cleanupGroupsAndMessages(userId, boundQqNumbers);
        }

        // 1. 删除 QQ 绑定
        userQqBindingRepository.deleteAll(bindings);
        log.info("已清理 QQ 绑定, userId={}", userId);

        // 2. 删除积分流水
        creditTransactionRepository.deleteByUserId(userId);
        log.info("已清理积分流水, userId={}", userId);

        // 3. 删除订阅订单
        subscriptionOrderRepository.deleteByUserId(userId);
        log.info("已清理订阅订单, userId={}", userId);

        // 4. 删除签到记录
        signInRecordRepository.deleteByUserId(userId);
        log.info("已清理签到记录, userId={}", userId);

        // 5. 删除月卡奖励记录
        monthlyBonusRecordRepository.deleteByUserId(userId);
        log.info("已清理月卡奖励, userId={}", userId);

        // 6. 删除积分账户
        userCreditRepository.findByUserId(userId).ifPresent(credit -> {
            userCreditRepository.delete(credit);
            log.info("已清理积分账户, userId={}", userId);
        });

        // 7. 删除用户设置
        userSettingsRepository.findByUserId(String.valueOf(userId)).ifPresent(settings -> {
            userSettingsRepository.delete(settings);
            log.info("已清理用户设置, userId={}", userId);
        });

        // 8. 删除 AstrBot 对话及消息
        List<com.qqai.entity.AstrBotConversation> conversations =
                astrBotConversationRepository.findByUserIdOrderByTimeUpdatedDesc(userId);
        if (!conversations.isEmpty()) {
            List<String> convIds = conversations.stream()
                    .map(com.qqai.entity.AstrBotConversation::getConversationId)
                    .collect(java.util.stream.Collectors.toList());
            // 先删消息（外键约束）
            for (String convId : convIds) {
                astrBotMessageRepository.deleteByConversationId(convId);
            }
            // 再删对话
            astrBotConversationRepository.deleteAll(conversations);
            log.info("已清理 AstrBot 对话及消息, userId={}, 对话数={}", userId, conversations.size());
        }

        // 9. 最后删除用户
        userRepository.delete(user);
        log.info("用户已删除, userId={}", userId);
    }

    /**
     * 清理用户 QQ 绑定对应的群聊、消息和文件
     */
    private void cleanupGroupsAndMessages(Long userId, List<String> boundQqNumbers) {
        // a. 查找用户绑定 QQ 号对应的群聊
        List<com.qqai.entity.Group> groups = groupRepository.findByOwnerQqIn(boundQqNumbers);
        if (groups.isEmpty()) {
            log.info("用户无对应群聊, userId={}", userId);
            return;
        }

        List<String> groupIds = groups.stream()
                .map(com.qqai.entity.Group::getGroupId)
                .collect(java.util.stream.Collectors.toList());
        log.info("准备清理群聊及消息, userId={}, 群数={}", userId, groups.size());

        // b. 查找群聊中的所有消息
        List<com.qqai.entity.Message> messages = messageRepository.findByGroupIdIn(groupIds);
        if (!messages.isEmpty()) {
            // c. 提取消息关联的 fileId
            List<String> fileIds = messages.stream()
                    .map(com.qqai.entity.Message::getFileId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());

            // c.1 从 content 中提取文件路径（格式: /images/..., /videos/..., /audios/..., /files/...）
            List<String> contentPaths = new ArrayList<>();
            java.util.regex.Pattern pathPattern = java.util.regex.Pattern.compile(
                    "/(images|videos|audios|files)/[^\\s\\[\\]\"'<>]+");
            for (com.qqai.entity.Message msg : messages) {
                if (msg.getContent() != null) {
                    java.util.regex.Matcher matcher = pathPattern.matcher(msg.getContent());
                    while (matcher.find()) {
                        contentPaths.add(matcher.group());
                    }
                }
            }

            // d. 删除文件记录及实际文件（通过 fileId）
            if (!fileIds.isEmpty()) {
                List<com.qqai.entity.FileRecord> fileRecords = fileRecordRepository.findAllByFileIdIn(fileIds);
                for (com.qqai.entity.FileRecord fr : fileRecords) {
                    try {
                        fileStorageService.deleteFile(fr.getFileId());
                    } catch (Exception e) {
                        log.warn("删除 MinIO 文件失败（继续清理数据库）, fileId={}, error={}",
                                fr.getFileId(), e.getMessage());
                    }
                }
                fileRecordRepository.deleteByFileIdIn(fileIds);
                log.info("已清理 fileId 对应的文件记录, 文件数={}", fileRecords.size());
            }

            // d.1 删除 content 中路径对应的实际文件（无 FileRecord 的情况）
            if (!contentPaths.isEmpty()) {
                List<String> uniquePaths = contentPaths.stream().distinct().collect(java.util.stream.Collectors.toList());
                for (String path : uniquePaths) {
                    try {
                        fileStorageService.deleteByPath(path);
                    } catch (Exception e) {
                        log.warn("删除 content 路径对应文件失败, path={}, error={}", path, e.getMessage());
                    }
                }
                log.info("已清理 content 路径对应的文件, 文件数={}", uniquePaths.size());
            }

            // e. 删除消息
            messageRepository.deleteByGroupIdIn(groupIds);
            log.info("已清理群聊消息, 消息数={}", messages.size());
        }

        // f. 删除群聊阅读状态（无论是否有消息）
        readStateRepository.deleteByUserIdAndGroupIdIn(userId, groupIds);

        // g. 删除群聊
        groupRepository.deleteAll(groups);
        log.info("已清理群聊, 群数={}", groups.size());
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
