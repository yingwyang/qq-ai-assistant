package com.qqai;

import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.security.JwtUtil;
import com.qqai.security.TokenBlacklistService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * JWT 认证过滤器回归测试。
 *
 * <p>背景：过滤器原先对同一 token 解析 6 遍（每取一个声明都重做一次签名校验），并用 {@code findById}
 * 每请求加载整个 User 实体。重构为「一次解析 + 最小投影查询」后，这里把判定语义钉住，
 * 确保优化没有放松任何一道校验。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterTest {

    /** 受保护且只依赖本地数据库的接口，不触发外部组件/进程 */
    private static final String PROTECTED_PATH = "/api/user/settings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    private final List<Long> createdUserIds = new ArrayList<>();

    private User user;

    @BeforeEach
    void setUp() {
        user = saveUser("jwt_filter_user", "USER", true, 0);
    }

    @AfterEach
    void tearDown() {
        createdUserIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("无凭据 → 401")
    void missingTokenIsRejected() throws Exception {
        assertStatus(401, mockMvc.perform(get(PROTECTED_PATH)));
    }

    @Test
    @DisplayName("无法解析的 token → 401")
    void malformedTokenIsRejected() throws Exception {
        assertStatus(401, mockMvc.perform(get(PROTECTED_PATH).cookie(rawCookie("not-a-jwt"))));
    }

    @Test
    @DisplayName("有效 token → 通过认证（非 401）")
    void validTokenPasses() throws Exception {
        int code = performWithToken(token(user, 0));
        assertNotEquals(401, code, "有效 token 不应被判未登录");
    }

    @Test
    @DisplayName("tokenVersion 与库中不一致 → 401（改密/降权后旧令牌必须失效）")
    void staleTokenVersionIsRejected() throws Exception {
        String stale = token(user, 0);
        user.setTokenVersion(1);
        userRepository.save(user);

        assertStatus(401, mockMvc.perform(get(PROTECTED_PATH).cookie(rawCookie(stale))));
    }

    @Test
    @DisplayName("用户被禁用 → 401")
    void disabledUserIsRejected() throws Exception {
        String token = token(user, 0);
        user.setActive(false);
        userRepository.save(user);

        assertStatus(401, mockMvc.perform(get(PROTECTED_PATH).cookie(rawCookie(token))));
    }

    @Test
    @DisplayName("token 指向的用户已删除 → 401（投影查询查不到即拒绝）")
    void deletedUserIsRejected() throws Exception {
        String token = token(user, 0);
        userRepository.delete(user);

        assertStatus(401, mockMvc.perform(get(PROTECTED_PATH).cookie(rawCookie(token))));
    }

    @Test
    @DisplayName("已登出的 jti → 401")
    void blacklistedJtiIsRejected() throws Exception {
        String token = token(user, 0);
        tokenBlacklistService.invalidateJti(jwtUtil.getJtiFromToken(token), jwtUtil.getExpirationFromToken(token));

        assertStatus(401, mockMvc.perform(get(PROTECTED_PATH).cookie(rawCookie(token))));
    }

    // ===================== 辅助方法 =====================

    private int performWithToken(String token) throws Exception {
        return mockMvc.perform(get(PROTECTED_PATH).cookie(rawCookie(token)))
                .andReturn().getResponse().getStatus();
    }

    private void assertStatus(int expected,
                              org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        int actual = actions.andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, "HTTP 状态码不符");
    }

    private Cookie rawCookie(String token) {
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME, token);
    }

    private String token(User u, int tokenVersion) {
        return jwtUtil.generateToken(u.getId(), u.getUsername(), u.getRole(), tokenVersion);
    }

    private User saveUser(String username, String role, boolean active, int tokenVersion) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("encoded-secret");
        u.setRole(role);
        u.setActive(active);
        u.setTokenVersion(tokenVersion);
        u.setCreatedAt(LocalDateTime.now());
        User saved = userRepository.save(u);
        createdUserIds.add(saved.getId());
        return saved;
    }
}
