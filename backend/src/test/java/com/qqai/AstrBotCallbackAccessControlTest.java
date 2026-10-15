package com.qqai;

import com.qqai.entity.Group;
import com.qqai.entity.User;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.repository.UserRepository;
import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.security.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AstrBot 回调接口的归属校验回归测试。
 *
 * <p>背景：{@code /api/astrbot/callback} 会把回调内容写进消息库，但此前没有任何归属校验——
 * 任何登录用户都能把假消息写进别人的群（消息列表被污染）。现在与 {@code /analyze} 同口径：
 * 群消息必须落在调用者绑定 QQ 拥有的群里（管理员豁免）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AstrBotCallbackAccessControlTest {

    private static final String OWNED_GROUP = "900300400";
    private static final String OTHER_GROUP = "900500600";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserQqBindingRepository bindingRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdGroupIds = new ArrayList<>();
    private final List<Long> createdBindingIds = new ArrayList<>();

    private User owner;

    @BeforeEach
    void setUp() {
        owner = saveUser("callback_owner", "USER");
        bindQq(owner, "30001");
        saveGroup(OWNED_GROUP, "30001");
        saveGroup(OTHER_GROUP, "30002");
    }

    @AfterEach
    void tearDown() {
        createdBindingIds.forEach(id -> bindingRepository.findById(id).ifPresent(bindingRepository::delete));
        createdGroupIds.forEach(id -> groupRepository.findById(id).ifPresent(groupRepository::delete));
        createdUserIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
        createdBindingIds.clear();
        createdGroupIds.clear();
        createdUserIds.clear();
    }

    @Test
    @DisplayName("往自己拥有的群写回调消息 → 放行")
    void ownerCanWriteToOwnGroup() throws Exception {
        int code = postCallback(OWNED_GROUP, owner);
        assertEquals(200, code, "自己拥有的群应可写入，实际=" + code);
    }

    @Test
    @DisplayName("往别人的群写回调消息 → 403（修复前可随意注入假消息）")
    void strangerCannotWriteToOtherGroup() throws Exception {
        assertEquals(403, postCallback(OTHER_GROUP, owner));
    }

    @Test
    @DisplayName("管理员可写入任意群")
    void adminCanWriteToAnyGroup() throws Exception {
        User admin = saveUser("callback_admin", "ADMIN");
        int code = postCallback(OTHER_GROUP, admin);
        assertNotEquals(403, code, "管理员应豁免归属校验");
        assertNotEquals(401, code, "管理员应已通过认证");
    }

    @Test
    @DisplayName("无 group_id 的私聊回调不受群归属限制（不因缺群号而报错）")
    void privateCallbackIsNotBlockedByGroupCheck() throws Exception {
        String body = "{\"session_id\":\"sess-1\",\"sender_id\":\"999\",\"sender_name\":\"x\",\"message\":\"hi\"}";
        int code = mockMvc.perform(post("/api/astrbot/callback")
                        .cookie(cookie(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
        assertEquals(200, code);
    }

    // ===================== 辅助方法 =====================

    private int postCallback(String groupId, User user) throws Exception {
        String body = "{\"message_type\":\"group\",\"group_id\":\"" + groupId
                + "\",\"sender_id\":\"30001\",\"sender_name\":\"tester\",\"message\":\"hello\"}";
        return mockMvc.perform(post("/api/astrbot/callback")
                        .cookie(cookie(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private Cookie cookie(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), 0);
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME, token);
    }

    private User saveUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("encoded-secret");
        user.setRole(role);
        user.setActive(true);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private void bindQq(User user, String qqNumber) {
        UserQqBinding binding = new UserQqBinding();
        binding.setUserId(user.getId());
        binding.setQqNumber(qqNumber);
        binding.setActive(true);
        binding.setDefault(true);
        createdBindingIds.add(bindingRepository.save(binding).getId());
    }

    private void saveGroup(String groupId, String ownerQq) {
        Group group = new Group();
        group.setGroupId(groupId);
        group.setOwnerQq(ownerQq);
        group.setGroupName("测试群-" + groupId);
        group.setActive(true);
        group.setCreatedAt(LocalDateTime.now());
        createdGroupIds.add(groupRepository.save(group).getId());
    }
}
