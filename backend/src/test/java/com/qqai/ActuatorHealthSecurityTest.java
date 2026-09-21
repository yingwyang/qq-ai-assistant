package com.qqai;

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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Actuator 健康端点回归（企业级化批次 E）。
 *
 * 目的：让 /actuator/health 真正探测 MySQL 与 RabbitMQ（原先只有 /api/system/health 探 NapCat 端口，
 * 数据库挂了健康检查仍返回 ok），同时**不能**把依赖详情暴露给未登录用户 —— 该端点收权为 ADMIN。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ActuatorHealthSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;

    private Cookie adminCookie;
    private Cookie userCookie;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        adminCookie = cookieFor(save("actuator-admin", "ADMIN"));
        userCookie = cookieFor(save("actuator-user", "USER"));
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

    @Test
    @DisplayName("匿名访问 /actuator/health：401（依赖详情不对外）")
    void anonymousCannotReadHealth() throws Exception {
        assertEquals(401, mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("普通登录用户访问 /actuator/health：403（仅 ADMIN）")
    void normalUserCannotReadHealth() throws Exception {
        assertEquals(403, mockMvc.perform(get("/actuator/health").cookie(userCookie))
                .andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("ADMIN 可读，且响应里能看到 DB 组件探测结果")
    void adminSeesDependencyDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health").cookie(adminCookie)).andReturn();
        int status = result.getResponse().getStatus();
        assertNotEquals(401, status, "管理员不应被判未登录");
        assertNotEquals(403, status, "管理员不应被判权限不足");

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"status\""), "健康响应应含 status 字段：" + body);
        assertTrue(body.contains("\"db\""), "健康响应应包含数据库组件探测（db）：" + body);
        assertTrue(body.contains("components"), "show-details=always 时应返回 components 明细：" + body);
    }
}
