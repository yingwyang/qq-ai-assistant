package com.qqai;

import com.qqai.config.MediaAccessFilter;
import com.qqai.entity.Group;
import com.qqai.entity.User;
import com.qqai.entity.UserQqBinding;
import com.qqai.repository.GroupRepository;
import com.qqai.repository.UserQqBindingRepository;
import com.qqai.repository.UserRepository;
import com.qqai.security.AuthPrincipal;
import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.security.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 媒体访问控制回归测试（对应审查问题 #1：/images/**、/uploads/** 曾为 permitAll）。
 *
 * <p>覆盖点：</p>
 * <ol>
 *   <li>匿名不再能读取任何聊天媒体（此前只要拿到 URL 就能看）；</li>
 *   <li>群聊媒体按「绑定 QQ 是否拥有该群」授权，非群主 403；</li>
 *   <li>TTS 语音仅生成者本人可读；</li>
 *   <li>uploads 下服务端中间产物（chat-tmp/outbound 等）普通用户不可读；</li>
 *   <li>头像保持公开（登录页/群头像展示依赖）；</li>
 *   <li>ADMIN 全量放行；</li>
 *   <li>带 {@code ..} 的路径在授权前被拒（防绕过归属校验）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaAccessControlTest {

    private static final String OWNED_GROUP = "123456789";
    private static final String OTHER_GROUP = "987654321";

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

    @Autowired
    private MediaAccessFilter mediaAccessFilter;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdGroupIds = new ArrayList<>();
    private final List<Long> createdBindingIds = new ArrayList<>();

    private User owner;
    private User stranger;
    private User admin;

    @BeforeEach
    void setUp() {
        owner = saveUser("media_owner");
        stranger = saveUser("media_stranger");
        admin = saveUser("media_admin", "ADMIN");

        bindQq(owner, "10001");
        bindQq(stranger, "20002");

        saveGroup(OWNED_GROUP, "10001");
        saveGroup(OTHER_GROUP, "20002");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        createdBindingIds.forEach(id -> bindingRepository.findById(id).ifPresent(bindingRepository::delete));
        createdGroupIds.forEach(id -> groupRepository.findById(id).ifPresent(groupRepository::delete));
        createdUserIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
        createdBindingIds.clear();
        createdGroupIds.clear();
        createdUserIds.clear();
    }

    // ===================== 匿名 =====================

    @Test
    @DisplayName("匿名读取群聊图片 → 401（修复前是 permitAll，可匿名拿到）")
    void anonymousDeniedOnGroupMedia() throws Exception {
        assertEquals(401, status(get("/images/images/" + OWNED_GROUP + "/2026-10-15/x.jpg")));
    }

    @Test
    @DisplayName("匿名读取 TTS 语音 → 401")
    void anonymousDeniedOnTts() throws Exception {
        assertEquals(401, status(get("/uploads/tts/" + owner.getId() + "/2026-10-15/x.wav")));
    }

    @Test
    @DisplayName("匿名读取 uploads 中间产物 → 401")
    void anonymousDeniedOnUploadsInternals() throws Exception {
        assertEquals(401, status(get("/uploads/chat-tmp/x.png")));
    }

    @Test
    @DisplayName("头像保持公开：匿名不会被鉴权拦住（文件不存在时是 404）")
    void avatarsStayPublic() throws Exception {
        int code = status(get("/uploads/avatars/groups/group_1.jpg"));
        assertNotEquals(401, code, "头像应保持匿名可读");
        assertNotEquals(403, code, "头像应保持匿名可读");
    }

    // ===================== 归属校验 =====================

    @Test
    @DisplayName("拥有该群的用户可读群聊媒体（404 = 已过鉴权、到达资源处理器）")
    void groupOwnerCanReadOwnGroupMedia() throws Exception {
        int code = status(get("/images/images/" + OWNED_GROUP + "/2026-10-15/x.jpg").cookie(cookie(owner)));
        assertEquals(404, code, "群主应通过鉴权（文件不存在即 404），实际=" + code);
    }

    @Test
    @DisplayName("非该群用户读取群聊媒体 → 403")
    void strangerDeniedOnOtherGroupMedia() throws Exception {
        assertEquals(403, status(get("/images/images/" + OWNED_GROUP + "/2026-10-15/x.jpg").cookie(cookie(stranger))));
    }

    @Test
    @DisplayName("TTS 语音：本人可读（404），他人 403")
    void ttsIsOwnerOnly() throws Exception {
        String own = "/uploads/tts/" + owner.getId() + "/2026-10-15/a.wav";
        String other = "/uploads/tts/" + stranger.getId() + "/2026-10-15/b.wav";

        assertEquals(404, status(get(own).cookie(cookie(owner))), "本人应通过鉴权读取自己的 TTS");
        assertEquals(403, status(get(other).cookie(cookie(owner))));
    }

    @Test
    @DisplayName("uploads 中间产物：普通用户 403，ADMIN 放行（404）")
    void uploadsInternalsAreAdminOnly() throws Exception {
        String path = "/uploads/outbound/" + OWNED_GROUP + "/x.jpg";
        assertEquals(403, status(get(path).cookie(cookie(owner))));
        assertEquals(404, status(get(path).cookie(cookie(admin))), "ADMIN 应通过鉴权读取中间产物");
    }

    @Test
    @DisplayName("ADMIN 可读任意群聊媒体（404）")
    void adminCanReadAnyGroupMedia() throws Exception {
        int code = status(get("/images/video/" + OTHER_GROUP + "/2026-10-15/x.mp4").cookie(cookie(admin)));
        assertEquals(404, code, "ADMIN 应通过鉴权读取任意群媒体，实际=" + code);
    }

    @Test
    @DisplayName("路径含 .. 时在授权前被拒绝（防绕过归属校验）")
    void traversalPathRejected() throws Exception {
        // MockMvc 的 URI 构造会归一化 .. ，这里直接驱动过滤器，保证拿到的是原始 URI
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/images/images/" + OWNED_GROUP + "/../../uploads/tts/" + stranger.getId() + "/x.wav");
        request.setRequestURI("/images/images/" + OWNED_GROUP + "/../../uploads/tts/" + stranger.getId() + "/x.wav");
        MockHttpServletResponse response = new MockHttpServletResponse();

        setAuthentication(owner);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);

        mediaAccessFilter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus(), "可疑路径应直接拒绝，不能进入归属校验");
        assertFalse(chainCalled.get(), "被拒绝的请求不应继续走后续过滤器链");
    }

    // ===================== 辅助方法 =====================

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private Cookie cookie(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), 0);
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME, token);
    }

    private void setAuthentication(User user) {
        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getUsername(), user.getRole(), 0);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private User saveUser(String username) {
        return saveUser(username, "USER");
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
        UserQqBinding saved = bindingRepository.save(binding);
        createdBindingIds.add(saved.getId());
    }

    private void saveGroup(String groupId, String ownerQq) {
        Group group = new Group();
        group.setGroupId(groupId);
        group.setOwnerQq(ownerQq);
        group.setGroupName("测试群-" + groupId);
        group.setActive(true);
        group.setCreatedAt(LocalDateTime.now());
        Group saved = groupRepository.save(group);
        createdGroupIds.add(saved.getId());
    }
}
