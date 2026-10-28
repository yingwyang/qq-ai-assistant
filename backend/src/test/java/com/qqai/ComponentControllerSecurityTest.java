package com.qqai;

import com.qqai.component.ComponentModel;
import com.qqai.component.ComponentOrchestrator;
import com.qqai.entity.User;
import com.qqai.repository.UserRepository;
import com.qqai.security.JwtAuthenticationFilter;
import com.qqai.security.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一组件 API 的权限回归：
 * 读取需登录，启停仅 ADMIN（误把 USER 放进来等于给普通用户杀组件的权限）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComponentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private ComponentOrchestrator orchestrator;

    private Cookie adminCookie;
    private Cookie userCookie;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        adminCookie = cookieFor(save("comp-admin", "ADMIN"));
        userCookie = cookieFor(save("comp-user", "USER"));

        ComponentModel.ComponentView view = new ComponentModel.ComponentView();
        view.id = "astrbot";
        view.state = "RUNNING";
        view.running = true;
        given(orchestrator.list()).willReturn(List.of(view));
        given(orchestrator.start("astrbot", false)).willReturn(view);
    }

    @Test
    @DisplayName("GET /api/system/components：匿名 401，登录 200")
    void readRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/system/components")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/system/components").cookie(userCookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST start：USER 403，ADMIN 200")
    void startRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/system/components/astrbot/start").cookie(userCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/system/components/astrbot/start").cookie(adminCookie))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST stop：USER 403，ADMIN 200")
    void stopRequiresAdmin() throws Exception {
        given(orchestrator.stop("astrbot", false)).willReturn(new ComponentModel.ComponentView());
        mockMvc.perform(post("/api/system/components/astrbot/stop").cookie(userCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/system/components/astrbot/stop").cookie(adminCookie))
                .andExpect(status().isOk());
    }

    private User save(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("x");
        user.setRole(role);
        user.setActive(true);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    private Cookie cookieFor(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), 0);
        return new Cookie(JwtAuthenticationFilter.COOKIE_NAME, token);
    }
}
