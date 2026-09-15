package com.qqai;

import com.qqai.entity.User;
import com.qqai.entity.UserCredit;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.*;
import com.qqai.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户删除级联清理测试
 * 验证删除用户时会正确清理所有关联数据
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class UserDeletionCascadeTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserQqBindingRepository bindingRepository;

    @Autowired
    private UserCreditRepository creditRepository;

    @Autowired
    private UserSettingsRepository settingsRepository;

    @Autowired
    private CreditTransactionRepository transactionRepository;

    @Autowired
    private SubscriptionOrderRepository orderRepository;

    @Autowired
    private MonthlyBonusRecordRepository bonusRepository;

    @Autowired
    private SignInRecordRepository signInRepository;

    @Autowired
    private AstrBotConversationRepository conversationRepository;

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

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = userService.register("test_cascade_user", "password123", "测试用户");
        Long userId = testUser.getId();

        UserCredit credit = new UserCredit();
        credit.setUserId(userId);
        credit.setBalance(500);
        credit.setTotalEarned(500);
        credit.setTotalSpent(0);
        credit.setSubscriptionTier(com.qqai.entity.enums.SubscriptionTier.FREE);
        creditRepository.save(credit);
    }

    @AfterEach
    void tearDown() {
        if (testUser != null && userRepository.existsById(testUser.getId())) {
            userRepository.delete(testUser);
        }
    }

    @Test
    @DisplayName("删除用户后 QQ 绑定应被清理")
    void shouldCleanupBindingsWhenUserDeleted() {
        Long userId = testUser.getId();
        userService.bindQqAccount(userId, "123456789", "测试昵称", null);
        assertFalse(bindingRepository.findByUserIdAndActiveTrue(userId).isEmpty());

        userService.delete(testUser);

        List<UserQqBinding> bindings = bindingRepository.findByUserId(userId);
        assertTrue(bindings.isEmpty(), "删除用户后 QQ 绑定应被清理");
    }

    @Test
    @DisplayName("删除用户后积分账户应被清理")
    void shouldCleanupCreditWhenUserDeleted() {
        Long userId = testUser.getId();
        assertTrue(creditRepository.existsByUserId(userId));

        userService.delete(testUser);

        assertFalse(creditRepository.existsByUserId(userId),
                "删除用户后积分账户应被清理");
    }

    @Test
    @DisplayName("删除用户后用户设置应被清理")
    void shouldCleanupSettingsWhenUserDeleted() {
        Long userId = testUser.getId();

        com.qqai.entity.UserSettings settings = new com.qqai.entity.UserSettings();
        settings.setUserId(String.valueOf(userId));
        settings.setBotName("测试Bot");
        settingsRepository.save(settings);

        userService.delete(testUser);

        assertTrue(settingsRepository.findAll().stream()
                .noneMatch(s -> s.getUserId().equals(String.valueOf(userId))),
                "删除用户后用户设置应被清理");
    }

    @Test
    @DisplayName("删除用户后积分流水应被清理")
    void shouldCleanupTransactionsWhenUserDeleted() {
        Long userId = testUser.getId();
        userService.delete(testUser);

        assertTrue(transactionRepository.findAll().stream()
                .noneMatch(t -> t.getUserId() != null && t.getUserId().equals(userId)),
                "删除用户后积分流水应被清理");
    }

    @Test
    @DisplayName("删除用户后订阅订单应被清理")
    void shouldCleanupOrdersWhenUserDeleted() {
        Long userId = testUser.getId();
        userService.delete(testUser);

        assertTrue(orderRepository.findAll().stream()
                .noneMatch(o -> o.getUserId() != null && o.getUserId().equals(userId)),
                "删除用户后订阅订单应被清理");
    }

    @Test
    @DisplayName("删除用户后签到记录应被清理")
    void shouldCleanupSignInRecordsWhenUserDeleted() {
        Long userId = testUser.getId();
        userService.delete(testUser);

        assertTrue(signInRepository.findAll().stream()
                .noneMatch(s -> s.getUserId() != null && s.getUserId().equals(userId)),
                "删除用户后签到记录应被清理");
    }

    @Test
    @DisplayName("删除用户后月卡奖励记录应被清理")
    void shouldCleanupBonusRecordsWhenUserDeleted() {
        Long userId = testUser.getId();
        userService.delete(testUser);

        assertTrue(bonusRepository.findAll().stream()
                .noneMatch(b -> b.getUserId() != null && b.getUserId().equals(userId)),
                "删除用户后月卡奖励记录应被清理");
    }

    @Test
    @DisplayName("删除用户后 QQ 号可以被其他用户绑定")
    void shouldAllowQqRebindingAfterUserDeletion() {
        Long userId = testUser.getId();
        userService.bindQqAccount(userId, "999888777", "旧用户", null);
        assertTrue(userService.existsByUserIdAndQqNumberAndActiveTrue(userId, "999888777"));

        userService.delete(testUser);

        List<UserQqBinding> activeBindings = bindingRepository.findByQqNumberAndActiveTrue("999888777");
        assertTrue(activeBindings.isEmpty(),
                "删除用户后 QQ 号的激活绑定应被清理，允许重新绑定");
    }

    @Test
    @DisplayName("删除用户后 AstrBot 对话应被清理")
    void shouldCleanupAstrBotConversationsWhenUserDeleted() {
        Long userId = testUser.getId();

        // 创建对话
        com.qqai.entity.AstrBotConversation conv = new com.qqai.entity.AstrBotConversation();
        conv.setConversationId("test-conv-001");
        conv.setUserId(userId);
        conv.setGroupId("123456");
        conv.setTitle("测试对话");
        conv.setMessageCount(2);
        conversationRepository.save(conv);

        // 验证对话存在
        assertFalse(conversationRepository.findByUserIdOrderByTimeUpdatedDesc(userId).isEmpty());

        // 删除用户
        userService.delete(testUser);

        // 验证对话已清理
        assertTrue(conversationRepository.findByUserIdOrderByTimeUpdatedDesc(userId).isEmpty(),
                "删除用户后 AstrBot 对话应被清理");
    }

    @Test
    @DisplayName("删除用户后 AstrBot 对话消息应被清理")
    void shouldCleanupAstrBotMessagesWhenUserDeleted() {
        Long userId = testUser.getId();

        // 创建对话及消息
        com.qqai.entity.AstrBotConversation conv = new com.qqai.entity.AstrBotConversation();
        conv.setConversationId("test-conv-002");
        conv.setUserId(userId);
        conv.setGroupId("123456");
        conv.setTitle("测试对话2");
        conv.setMessageCount(1);
        conversationRepository.save(conv);

        com.qqai.entity.AstrBotMessage msg = new com.qqai.entity.AstrBotMessage();
        msg.setMessageId("msg-001");
        msg.setConversationId("test-conv-002");
        msg.setRole(com.qqai.entity.AstrBotMessage.MessageRole.USER);
        msg.setContent("你好");
        astrBotMessageRepository.save(msg);

        // 验证消息存在
        assertFalse(astrBotMessageRepository.findByConversationIdOrderByTimeCreatedAsc("test-conv-002").isEmpty());

        // 删除用户
        userService.delete(testUser);

        // 验证消息已清理
        assertTrue(astrBotMessageRepository.findByConversationIdOrderByTimeCreatedAsc("test-conv-002").isEmpty(),
                "删除用户后 AstrBot 对话消息应被清理");

        // 验证对话也被清理
        assertTrue(conversationRepository.findByUserIdOrderByTimeUpdatedDesc(userId).isEmpty(),
                "删除用户后 AstrBot 对话也应被清理");
    }

    @Test
    @DisplayName("删除用户后 QQ 绑定对应的群聊应被清理")
    void shouldCleanupGroupsWhenUserDeleted() {
        Long userId = testUser.getId();

        // 绑定 QQ 号
        userService.bindQqAccount(userId, "111222333", "测试QQ", null);

        // 创建群聊
        com.qqai.entity.Group group = new com.qqai.entity.Group();
        group.setGroupId("999000001");
        group.setOwnerQq("111222333");
        group.setGroupName("测试群");
        group.setActive(true);
        groupRepository.save(group);

        // 验证群存在
        assertFalse(groupRepository.findByOwnerQq("111222333").isEmpty());

        // 删除用户
        userService.delete(testUser);

        // 验证群已清理
        assertTrue(groupRepository.findByOwnerQq("111222333").isEmpty(),
                "删除用户后 QQ 绑定对应的群聊应被清理");
    }

    @Test
    @DisplayName("删除用户后群聊中的消息应被清理")
    void shouldCleanupMessagesWhenUserDeleted() {
        Long userId = testUser.getId();

        // 绑定 QQ 号
        userService.bindQqAccount(userId, "111222333", "测试QQ", null);

        // 创建群聊
        com.qqai.entity.Group group = new com.qqai.entity.Group();
        group.setGroupId("999000002");
        group.setOwnerQq("111222333");
        group.setGroupName("测试群2");
        group.setActive(true);
        groupRepository.save(group);

        // 创建消息
        com.qqai.entity.Message msg = new com.qqai.entity.Message();
        msg.setMessageId("msg-999-001");
        msg.setGroupId("999000002");
        msg.setUserQq("222333444");
        msg.setMessageType(com.qqai.entity.Message.MessageType.TEXT);
        msg.setContent("测试消息");
        msg.setSendTime(java.time.LocalDateTime.now());
        messageRepository.save(msg);

        // 验证消息存在
        assertFalse(messageRepository.findByGroupIdOrderBySendTimeDesc("999000002").isEmpty());

        // 删除用户
        userService.delete(testUser);

        // 验证消息已清理
        assertTrue(messageRepository.findByGroupIdOrderBySendTimeDesc("999000002").isEmpty(),
                "删除用户后群聊中的消息应被清理");
    }

    @Test
    @DisplayName("删除用户后消息关联的文件记录应被清理")
    void shouldCleanupFileRecordsWhenUserDeleted() {
        Long userId = testUser.getId();

        // 绑定 QQ 号
        userService.bindQqAccount(userId, "111222333", "测试QQ", null);

        // 创建群聊
        com.qqai.entity.Group group = new com.qqai.entity.Group();
        group.setGroupId("999000003");
        group.setOwnerQq("111222333");
        group.setGroupName("测试群3");
        group.setActive(true);
        groupRepository.save(group);

        // 创建文件记录
        com.qqai.entity.FileRecord fileRecord = new com.qqai.entity.FileRecord();
        fileRecord.setFileId("file-001");
        fileRecord.setFileName("test.jpg");
        fileRecord.setFileType(com.qqai.entity.FileRecord.FileType.IMAGE);
        fileRecord.setFileSize(12345L);
        fileRecord.setMimeType("image/jpeg");
        fileRecord.setStorageType(com.qqai.entity.FileRecord.StorageType.MINIO);
        fileRecord.setBucketName("qq-chat");
        fileRecord.setObjectKey("images/111222333/2026-08-27/test.jpg");
        fileRecord.setUrl("/images/111222333/2026-08-27/test.jpg");
        fileRecord.setUploaderId(userId);
        fileRecord.setActive(true);
        fileRecordRepository.save(fileRecord);

        // 创建关联文件的消息
        com.qqai.entity.Message msg = new com.qqai.entity.Message();
        msg.setMessageId("msg-999-002");
        msg.setGroupId("999000003");
        msg.setUserQq("222333444");
        msg.setMessageType(com.qqai.entity.Message.MessageType.IMAGE);
        msg.setContent("[CQ:image,file=test.jpg,url=/images/111222333/2026-08-27/test.jpg]");
        msg.setFileId("file-001");
        msg.setSendTime(java.time.LocalDateTime.now());
        messageRepository.save(msg);

        // 验证文件记录存在
        assertTrue(fileRecordRepository.findByFileId("file-001").isPresent());

        // 删除用户
        userService.delete(testUser);

        // 验证文件记录已清理
        assertFalse(fileRecordRepository.findByFileId("file-001").isPresent(),
                "删除用户后消息关联的文件记录应被清理");
    }

    @Test
    @DisplayName("删除用户后 content 中含文件路径的消息应被清理")
    void shouldCleanupMessagesWithFilePathInContent() {
        Long userId = testUser.getId();

        // 绑定 QQ 号
        userService.bindQqAccount(userId, "111222333", "测试QQ", null);

        // 创建群聊
        com.qqai.entity.Group group = new com.qqai.entity.Group();
        group.setGroupId("999000005");
        group.setOwnerQq("111222333");
        group.setGroupName("测试群5");
        group.setActive(true);
        groupRepository.save(group);

        // 创建消息：content 存储文件路径，fileId 为 NULL
        com.qqai.entity.Message msg = new com.qqai.entity.Message();
        msg.setMessageId("msg-999-003");
        msg.setGroupId("999000005");
        msg.setUserQq("222333444");
        msg.setMessageType(com.qqai.entity.Message.MessageType.IMAGE);
        msg.setContent("/images/images/1090875633/2026-08-27/ce405857-424f-46e5-b725-ad99cb33198c.jpg");
        // fileId 为 null（真实场景）
        msg.setSendTime(java.time.LocalDateTime.now());
        messageRepository.save(msg);

        // 验证消息存在
        assertFalse(messageRepository.findByGroupIdOrderBySendTimeDesc("999000005").isEmpty());

        // 删除用户
        userService.delete(testUser);

        // 验证消息已清理
        assertTrue(messageRepository.findByGroupIdOrderBySendTimeDesc("999000005").isEmpty(),
                "删除用户后 content 含文件路径的消息应被清理");

        // 验证群聊也已清理
        assertTrue(groupRepository.findByOwnerQq("111222333").isEmpty(),
                "删除用户后群聊应被清理");
    }
}