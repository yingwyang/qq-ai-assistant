package com.qqai;

import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.security.JwtUtil;
import com.qqai.service.NapCatService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 二维码接口「登录 vs 换号」回归测试。
 *
 * <p>产品约束：扫码登录 QQ 是普通用户也要用的能力，不能整体收成 ADMIN
 * （2026-09-24 曾收成 ADMIN-only，直接导致普通用户登录不了 QQ）。</p>
 *
 * <p>因此策略是区分场景：机器人未登录 QQ 时任何登录用户可拉二维码；
 * 机器人已登录时再拉二维码属于顶号换号，仅管理员。NapCat 登录状态由
 * {@link NapCatService#checkLoginStatus()} 提供，本测试用 Mock 控制该状态。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NapCatQrAccessControlTest {

    private static final String QR_PATH = "/api/system/napcat/qrcode";
    private static final String QR_IMAGE_PATH = "/api/system/napcat/qrcode-image";
    private static final String QR_PATH_PATH = "/api/system/napcat/qrcode-path";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private NapCatService napCatService;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        // 二维码文件指向不存在的路径：命中 404，便于区分「鉴权通过」与「被拦」
        given(napCatService.getQrCodePath()).willReturn("./target/not-exist-qrcode.png");
    }

    @AfterEach
    void tearDown() {
        createdUserIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
        createdUserIds.clear();
    }

    // ===================== 未登录：普通用户可用（能力不能被砍） =====================

    @Test
    @DisplayName("机器人未登录 QQ 时：普通用户可拉二维码（不返回 401/403）")
    void normalUserCanLoginWhenBotLoggedOut() throws Exception {
        given(napCatService.checkLoginStatus()).willReturn(false);
        User user = saveUser("qr_normal_loggedout");

        for (String path : new String[]{QR_PATH, QR_PATH_PATH, QR_IMAGE_PATH}) {
            int code = status(path, cookie(user));
            assertNotEquals(401, code, "普通用户不应被判未登录：" + path);
            assertNotEquals(403, code, "机器人未登录时不应拦住普通用户：" + path);
        }
    }

    // ===================== 已登录：换号仅 ADMIN =====================

    @Test
    @DisplayName("机器人已登录 QQ 时：普通用户再拉二维码（换号）→ 403")
    void normalUserCannotSwitchAccountWhenBotLoggedIn() throws Exception {
        given(napCatService.checkLoginStatus()).willReturn(true);
        User user = saveUser("qr_normal_loggedin");

        for (String path : new String[]{QR_PATH, QR_PATH_PATH, QR_IMAGE_PATH}) {
            assertEquals(403, status(path, cookie(user)), "已登录状态下换号应仅限管理员：" + path);
        }
    }

    @Test
    @DisplayName("机器人已登录 QQ 时：管理员仍可换号（不返回 401/403）")
    void adminCanSwitchAccountWhenBotLoggedIn() throws Exception {
        given(napCatService.checkLoginStatus()).willReturn(true);
        User admin = saveUser("qr_admin_loggedin", "ADMIN");

        for (String path : new String[]{QR_PATH, QR_PATH_PATH, QR_IMAGE_PATH}) {
            int code = status(path, cookie(admin));
            assertNotEquals(401, code, "管理员不应被判未登录：" + path);
            assertNotEquals(403, code, "管理员应可执行换号：" + path);
        }
    }

    // ===================== 限流 =====================

    @Test
    @DisplayName("同一用户高频拉二维码 → 被限流（429）")
    void qrEndpointIsRateLimited() throws Exception {
        given(napCatService.checkLoginStatus()).willReturn(false);
        User user = saveUser("qr_flooder");

        boolean hitLimit = false;
        for (int i = 0; i < 40; i++) {
            if (status(QR_IMAGE_PATH, cookie(user)) == 429) {
                hitLimit = true;
                break;
            }
        }
        assertTrue(hitLimit, "二维码接口应按用户限流，防止被刷/撞运气");
    }

    // ===================== 辅助方法 =====================

    private int status(String path, Cookie cookie) throws Exception {
        return mockMvc.perform(get(path).cookie(cookie)).andReturn().getResponse().getStatus();
    }

    private Cookie cookie(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), 0);
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME, token);
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
}
