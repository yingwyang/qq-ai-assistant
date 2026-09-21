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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 管理端资金接口的入参校验回归（企业级化批次 C.2）。
 *
 * 背景：补单 / 调账 / 手工记账原先直接吃 {@code Map<String,Object>}，没有任何 Bean Validation：
 * 补单接口允许管理员（或拿着管理员令牌的人）传任意大的 pointsGranted / durationDays，
 * 等于一个「手工造币」入口。改造后统一走 DTO + @Valid。
 *
 * 本测试只提交**非法**入参，校验在进入服务层之前就失败，因此不会写入任何订单/流水。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminInputValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;

    private Cookie adminCookie;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User admin = new User();
        admin.setUsername("validation-admin");
        admin.setPassword("x");
        admin.setRole("ADMIN");
        admin.setActive(true);
        admin.setTokenVersion(0);
        admin.setCreatedAt(LocalDateTime.now());
        User saved = userRepository.save(admin);
        String token = jwtUtil.generateToken(saved.getId(), saved.getUsername(), "ADMIN", 0);
        adminCookie = new Cookie(JwtAuthenticationFilter.COOKIE_NAME, token);
    }

    private int postJson(String path, String json) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
        return result.getResponse().getStatus();
    }

    private String postJsonBody(String path, String json) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("补单：缺少 userId / planCode 返回 400 校验错误")
    void manualCreateRequiresIdentityFields() throws Exception {
        assertEquals(400, postJson("/api/credits/admin/orders/manual-create", "{\"planCode\":\"LITE\"}"));
        assertEquals(400, postJson("/api/credits/admin/orders/manual-create", "{\"userId\":1}"));
    }

    @Test
    @DisplayName("补单：积分与时长超上限被拒绝（原先可任意覆盖）")
    void manualCreateRejectsUnboundedOverrides() throws Exception {
        assertEquals(400, postJson("/api/credits/admin/orders/manual-create",
                "{\"userId\":1,\"planCode\":\"LITE\",\"pointsGranted\":999999999}"),
                "pointsGranted 超上限必须被拒");
        assertEquals(400, postJson("/api/credits/admin/orders/manual-create",
                "{\"userId\":1,\"planCode\":\"LITE\",\"durationDays\":99999}"),
                "durationDays 超上限必须被拒");
        assertEquals(400, postJson("/api/credits/admin/orders/manual-create",
                "{\"userId\":1,\"planCode\":\"LITE\",\"priceCents\":-1}"),
                "负价格必须被拒");
    }

    @Test
    @DisplayName("调账：缺少 amount / 金额越界返回 400")
    void adjustRequiresBoundedAmount() throws Exception {
        assertEquals(400, postJson("/api/credits/admin/adjust", "{\"userId\":1}"));
        assertEquals(400, postJson("/api/credits/admin/adjust", "{\"userId\":1,\"amount\":999999999}"),
                "超过上限的调账金额必须被拒");
        assertEquals(400, postJson("/api/credits/admin/adjust", "{\"userId\":1,\"amount\":-999999999}"),
                "超过下限的调账金额必须被拒");
    }

    @Test
    @DisplayName("手工记账：金额缺失 / 非正数 / 非法方向返回 400")
    void cashEntryRequiresPositiveAmount() throws Exception {
        assertEquals(400, postJson("/api/credits/admin/cash", "{\"direction\":\"IN\"}"));
        assertEquals(400, postJson("/api/credits/admin/cash", "{\"direction\":\"IN\",\"amount\":0}"));
        assertEquals(400, postJson("/api/credits/admin/cash", "{\"direction\":\"IN\",\"amount\":-5}"));
        assertEquals(400, postJson("/api/credits/admin/cash", "{\"direction\":\"SIDEWAYS\",\"amount\":1}"));
    }

    @Test
    @DisplayName("校验错误返回统一信封与 VALIDATION_ERROR 错误码（不再回显内部异常文本）")
    void validationErrorsUseUnifiedEnvelope() throws Exception {
        String body = postJsonBody("/api/credits/admin/cash", "{\"direction\":\"IN\"}");
        assertTrue(body.contains("VALIDATION_ERROR"), "应带 VALIDATION_ERROR 错误码：" + body);
        assertTrue(body.contains("400"), "应返回 400：" + body);
        assertFalse(body.contains("Exception"), "不应回显异常类名：" + body);
        assertFalse(body.contains("java."), "不应回显内部类型/包名：" + body);
    }
}
