package com.qqai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqai.dto.auth.AuthResponse;
import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.security.JwtUtil;
import com.qqai.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全收口回归测试（对应企业级化批次 A）。
 *
 * 覆盖四类此前会被绕过的问题：
 *  1. QQ 机器人登录二维码被匿名拉取（扫码即等于接管机器人账号）；
 *  2. 登录响应体回传 JWT 明文，抵消 HttpOnly 的防 XSS 价值；
 *  3. 角色变更不递增 tokenVersion，被降权的管理员旧令牌仍然可用。
 *
 * <p>2026-09-24 回归：二维码三个入口由 ROLE_ADMIN 放宽为「已登录即可」——此前普通用户被 403，
 * 直接表现为「普通用户登录不了 QQ」。匿名拦截保持不变。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SecurityHardeningTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    private User save(String username, String role, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("encoded-secret");
        user.setRole(role);
        user.setActive(active);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private Cookie tokenCookie(User user, String role) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), role, 0);
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME, token);
    }

    @Test
    @DisplayName("匿名请求拿不到机器人登录二维码（三个入口都拦住）")
    void anonymousCannotFetchBotQrCode() throws Exception {
        mockMvc.perform(get("/api/system/napcat/qrcode-image"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/system/napcat/qrcode"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/system/napcat/qrcode-path"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("普通登录用户可以读二维码（QQ 登录是普通用户功能；只有匿名被拦）")
    void normalUserCanFetchBotQrCode() throws Exception {
        User user = save("normal", "USER", true);
        for (String path : new String[]{
                "/api/system/napcat/qrcode-image",
                "/api/system/napcat/qrcode-path",
                "/api/system/napcat/qrcode"}) {
            int statusCode = mockMvc.perform(get(path).cookie(tokenCookie(user, "USER")))
                    .andReturn().getResponse().getStatus();
            assertNotEquals(401, statusCode, "普通登录用户不应被判未登录：" + path);
            assertNotEquals(403, statusCode, "普通登录用户不应被判权限不足：" + path);
        }
    }

    @Test
    @DisplayName("ADMIN 能通过鉴权读到二维码接口（404=文件不存在，不是 401/403）")
    void adminPassesQrEndpointAuthorization() throws Exception {
        User admin = save("admin", "ADMIN", true);
        MvcResult result = mockMvc.perform(get("/api/system/napcat/qrcode-image").cookie(tokenCookie(admin, "ADMIN")))
                .andReturn();
        int statusCode = result.getResponse().getStatus();
        assertNotEquals(401, statusCode, "ADMIN 不应被判未登录");
        assertNotEquals(403, statusCode, "ADMIN 不应被判权限不足");
    }

    @Test
    @DisplayName("组件状态灯保持公开（登录页在 Cookie 过期时仍要显示）")
    void componentStatusStaysPublic() throws Exception {
        mockMvc.perform(get("/api/system/component-status"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登录响应体不再包含 JWT 明文字段")
    void authResponseHasNoTokenField() throws Exception {
        assertTrue(
                Arrays.stream(AuthResponse.class.getRecordComponents()).noneMatch(c -> "token".equals(c.getName())),
                "AuthResponse 不应再有 token 字段（令牌只走 HttpOnly Cookie）"
        );

        AuthResponse sample = new AuthResponse(1L, "admin", "管理员", "ADMIN", null, null, null, null);
        String json = objectMapper.writeValueAsString(sample);
        assertFalse(json.contains("\"token\""), "序列化结果不应包含 token 字段：" + json);
        assertTrue(json.contains("\"username\""), "其它字段应保持可序列化：" + json);
    }

    @Test
    @DisplayName("User 实体的密码与令牌不会被序列化出去")
    void userEntityHidesSecrets() throws Exception {
        User user = save("secret-keeper", "USER", true);
        user.setToken("plain-jwt-should-never-leak");
        String json = objectMapper.writeValueAsString(user);
        assertFalse(json.contains("plain-jwt-should-never-leak"), "token 字段不应出现在 JSON 中");
        assertFalse(json.contains("encoded-secret"), "password 字段不应出现在 JSON 中");
        assertTrue(json.contains("secret-keeper"), "普通字段仍应正常序列化");
    }

    @Test
    @DisplayName("角色变更递增 tokenVersion：被降权者的旧令牌立即失效")
    void roleChangeInvalidatesIssuedTokens() {
        User admin = save("willBeDemoted", "ADMIN", true);
        assertEquals(0, admin.getTokenVersion());

        Optional<User> updated = userService.updateUserRole(admin.getId(), "USER");
        assertTrue(updated.isPresent());
        assertEquals(1, updated.get().getTokenVersion(),
                "降权后 tokenVersion 必须 +1，否则旧 JWT 里的 ADMIN 声明还能继续用");

        // 再改一次继续累加，保证每次角色变更都会让先前令牌失效
        userService.updateUserRole(admin.getId(), "ADMIN");
        User reloaded = userRepository.findById(admin.getId()).orElseThrow();
        assertEquals(2, reloaded.getTokenVersion());
    }

    @Test
    @DisplayName("禁用用户同样递增 tokenVersion（回归：原有行为不能被破坏）")
    void deactivationKeepsInvalidatingTokens() {
        User user = save("toDisable", "USER", true);
        userService.updateUserActive(user.getId(), false);
        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(1, reloaded.getTokenVersion());
        assertFalse(reloaded.isActive());
    }
}
