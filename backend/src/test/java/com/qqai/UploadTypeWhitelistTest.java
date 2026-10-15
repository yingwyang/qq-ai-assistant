package com.qqai;

import com.qqai.entity.User;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 上传接口类型白名单回归测试。
 *
 * <p>背景：{@code file.upload.allowed-types} 此前是死配置，{@code /api/messages/upload} 只校验逻辑
 * fileType 与体积，因此 {@code .html} 之类可脚本化文件也能落库并分发给别人。现在按配置白名单拒绝。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadTypeWhitelistTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final List<Long> createdUserIds = new ArrayList<>();

    private User user;

    @BeforeEach
    void setUp() {
        user = saveUser("upload_wl_user");
    }

    @AfterEach
    void tearDown() {
        createdUserIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("上传 .html → 400（修复前会被保存并在 MinIO 里按 text/html 分发）")
    void rejectsHtmlUpload() throws Exception {
        assertEquals(400, upload("evil.html", "text/html", "<script>alert(1)</script>"));
    }

    @Test
    @DisplayName("上传无扩展名文件 → 400（fail-closed）")
    void rejectsExtensionlessUpload() throws Exception {
        assertEquals(400, upload("payload", "application/octet-stream", "MZ..."));
    }

    @Test
    @DisplayName("上传白名单内类型不会被类型校验拦住")
    void allowsWhitelistedExtension() throws Exception {
        // 白名单内的 .jpg 会继续走到 MinIO；测试环境未启动 MinIO，因此后续失败是预期的，
        // 这里只断言「不是被类型白名单拒掉」
        int code = upload("ok.jpg", "image/jpeg", "fake-jpeg-bytes");
        assertNotEquals(400, code, "白名单内的扩展名不应被判为类型不允许");
    }

    // ===================== 辅助方法 =====================

    private int upload(String filename, String contentType, String body) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, contentType,
                body.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/messages/upload")
                        .file(file)
                        .param("fileType", "FILE")
                        .cookie(cookie(user)))
                .andReturn().getResponse().getStatus();
    }

    private Cookie cookie(User u) {
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME,
                jwtUtil.generateToken(u.getId(), u.getUsername(), u.getRole(), 0));
    }

    private User saveUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("encoded-secret");
        u.setRole("USER");
        u.setActive(true);
        u.setTokenVersion(0);
        u.setCreatedAt(LocalDateTime.now());
        User saved = userRepository.save(u);
        createdUserIds.add(saved.getId());
        return saved;
    }
}
