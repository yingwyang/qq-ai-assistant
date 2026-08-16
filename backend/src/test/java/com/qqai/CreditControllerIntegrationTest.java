package com.qqai;

import com.qqai.common.SecurityHelper;
import com.qqai.controller.CreditsController;
import com.qqai.controller.SubscriptionsController;
import com.qqai.dto.common.ApiResponse;
import com.qqai.entity.CreditRule;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.User;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.repository.UserRepository;
import com.qqai.security.AuthPrincipal;
import com.qqai.service.CreditRuleService;
import com.qqai.service.CreditService;
import com.qqai.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CreditControllerIntegrationTest {

    @Autowired
    private CreditsController creditsController;

    @Autowired
    private SubscriptionsController subscriptionsController;

    @Autowired
    private CreditService creditService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private CreditRuleService creditRuleService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityHelper securityHelper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private User userA;
    private User userB;
    private User admin;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        createUsers();
        // 确保积分规则已初始化
        creditRuleService.getRule();
    }

    private void createUsers() {
        transactionTemplate.executeWithoutResult(s -> {
            userA = userRepository.findByUsername("userA_ctrl").orElseGet(() -> {
                User u = new User();
                u.setUsername("userA_ctrl");
                u.setNickname("用户A");
                u.setPassword(passwordEncoder.encode("pass123"));
                u.setRole("USER");
                u.setActive(true);
                return userRepository.save(u);
            });
            userB = userRepository.findByUsername("userB_ctrl").orElseGet(() -> {
                User u = new User();
                u.setUsername("userB_ctrl");
                u.setNickname("用户B");
                u.setPassword(passwordEncoder.encode("pass123"));
                u.setRole("USER");
                u.setActive(true);
                return userRepository.save(u);
            });
            admin = userRepository.findByUsername("admin_ctrl").orElseGet(() -> {
                User u = new User();
                u.setUsername("admin_ctrl");
                u.setNickname("管理员");
                u.setPassword(passwordEncoder.encode("pass123"));
                u.setRole("ADMIN");
                u.setActive(true);
                return userRepository.save(u);
            });
        });
    }

    private void loginAs(User user) {
        String role = user.getRole() != null ? user.getRole() : "USER";
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        Integer tokenVersion = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getUsername(), role, tokenVersion);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    private void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testSignIn_Twice_ALREADY_SIGNED_IN() {
        Integer originalSignInPoints = transactionTemplate.execute(s -> creditRuleService.getRule().getSignInPoints());
        try {
            transactionTemplate.executeWithoutResult(s -> {
                CreditRule rule = creditRuleService.getRule();
                rule.setSignInPoints(200);
                creditRuleService.setRule(rule);
                creditRuleService.evictCache();
            });

            loginAs(userA);
            transactionTemplate.executeWithoutResult(s -> creditService.ensureAccount(userA.getId()));

            ResponseEntity<ApiResponse<Map<String, Object>>> firstResp =
                    transactionTemplate.execute(s -> creditsController.signIn());
            assertNotNull(firstResp);
            assertNotNull(firstResp.getBody());
            assertEquals(200, firstResp.getBody().getCode());
            Map<String, Object> data = firstResp.getBody().getData();
            assertNotNull(data);
            assertEquals(200, data.get("points"));
            assertTrue(((Integer) data.get("newBalance")) >= 200,
                    "第1次签到后余额应>=200，实际: " + data.get("newBalance"));
            System.out.println("第1次签到返回: points=" + data.get("points") + " streakDays=" + data.get("streakDays")
                    + " newBalance=" + data.get("newBalance"));

            BizException thrown = assertThrows(BizException.class, () -> {
                transactionTemplate.executeWithoutResult(s -> creditsController.signIn());
            }, "第2次签到应抛出ALREADY_SIGNED_IN BizException");

            assertEquals(CreditErrorCode.ALREADY_SIGNED_IN, thrown.getErrorCode(),
                    "错误码应为ALREADY_SIGNED_IN，实际: " + thrown.getErrorCode());
            assertEquals(400, thrown.getCode());
            System.out.println("第2次签到拦截成功: errorCode=" + thrown.getErrorCode()
                    + " message=" + thrown.getMessage());

        } finally {
            transactionTemplate.executeWithoutResult(s -> {
                CreditRule rule = creditRuleService.getRule();
                rule.setSignInPoints(originalSignInPoints);
                creditRuleService.setRule(rule);
                creditRuleService.evictCache();
            });
            logout();
        }
    }

    @Test
    void testOrderAccess_B_REQUEST_A_FORBIDDEN_ORDER() {
        loginAs(userA);
        transactionTemplate.executeWithoutResult(s -> creditService.ensureAccount(userA.getId()));
        SubscriptionOrder orderOfA = transactionTemplate.execute(s ->
                subscriptionService.createOrder(userA.getId(), SubscriptionTier.LITE, "127.0.0.1", "test"));
        assertNotNull(orderOfA);
        String orderNo = orderOfA.getOrderNo();
        System.out.println("A创建订单: orderNo=" + orderNo + " userId=" + orderOfA.getUserId());

        loginAs(userB);
        BizException thrown = assertThrows(BizException.class, () -> {
            transactionTemplate.executeWithoutResult(s -> subscriptionsController.getOrder(orderNo));
        }, "B请求A订单详情应抛出FORBIDDEN_ORDER");

        assertEquals(CreditErrorCode.FORBIDDEN_ORDER, thrown.getErrorCode(),
                "错误码应为FORBIDDEN_ORDER，实际: " + thrown.getErrorCode());
        assertEquals(403, thrown.getCode());
        System.out.println("B访问A订单拦截成功: errorCode=" + thrown.getErrorCode()
                + " message=" + thrown.getMessage());
        logout();
    }

    @Test
    void testAdminApi_USER_ROLE_403_ADMIN_REQUIRED() {
        loginAs(userA);
        BizException thrown = assertThrows(BizException.class, () -> {
            creditsController.getClass(); // 确保controller加载
            transactionTemplate.executeWithoutResult(s -> {
                securityHelper.requireAdmin();
            });
        }, "USER调用requireAdmin应抛出ADMIN_REQUIRED");

        assertEquals(CreditErrorCode.ADMIN_REQUIRED, thrown.getErrorCode(),
                "错误码应为ADMIN_REQUIRED，实际: " + thrown.getErrorCode());
        assertEquals(403, thrown.getCode());
        System.out.println("USER访问管理员功能拦截成功: errorCode=" + thrown.getErrorCode()
                + " message=" + thrown.getMessage());
        logout();
    }

    @Test
    void testGetBalance_ReturnsExpectedStructure() {
        loginAs(userA);
        transactionTemplate.executeWithoutResult(s -> creditService.ensureAccount(userA.getId()));

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                transactionTemplate.execute(s -> creditsController.getBalance());
        assertNotNull(resp);
        assertNotNull(resp.getBody());
        assertEquals(200, resp.getBody().getCode());
        Map<String, Object> data = resp.getBody().getData();
        assertNotNull(data);
        assertTrue(data.containsKey("balance"));
        assertTrue(data.containsKey("totalEarned"));
        assertTrue(data.containsKey("totalSpent"));
        assertTrue(data.containsKey("todaySignInDone"));
        assertTrue(data.containsKey("streakDays"));
        assertTrue(data.containsKey("subscriptionTier"));
        assertTrue(data.containsKey("subscriptionExpiresAt"));
        System.out.println("balance 结构校验通过: " + data);
        logout();
    }

    @Test
    void testGetPlans_ReturnsPlans() {
        loginAs(userA);
        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                transactionTemplate.execute(s -> subscriptionsController.getPlans());
        assertNotNull(resp);
        assertNotNull(resp.getBody());
        assertEquals(200, resp.getBody().getCode());
        Map<String, Object> data = resp.getBody().getData();
        assertNotNull(data);
        // 新结构: { plans, directPlans, monthlyCards, groups }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) data.get("plans");
        assertNotNull(plans);
        assertTrue(plans.size() >= 4, "应至少返回4档套餐，实际: " + plans.size());
        for (Map<String, Object> p : plans) {
            assertTrue(p.containsKey("planCode"));
            assertTrue(p.containsKey("pointsGranted"));
            assertTrue(p.containsKey("priceCents"));
            assertTrue(p.containsKey("benefits"));
        }
        System.out.println("plans接口通过: 套餐数量=" + plans.size());
        logout();
    }

    @Test
    void testPurchase_CreatesPendingOrderOnly() {
        loginAs(userB);
        transactionTemplate.executeWithoutResult(s -> creditService.ensureAccount(userB.getId()));

        Map<String, Object> body = new HashMap<>();
        body.put("planCode", "LITE");
        body.put("paymentMethod", "MANUAL");
        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                transactionTemplate.execute(s -> subscriptionsController.purchase(body, null));
        assertNotNull(resp);
        assertNotNull(resp.getBody());
        assertEquals(200, resp.getBody().getCode());
        Map<String, Object> data = resp.getBody().getData();
        assertNotNull(data);
        assertNotNull(data.get("orderNo"));
        // 安全整改:购买只创建 PENDING 订单,权益由管理员确认到账后发放,不允许自付自过
        assertEquals("PENDING", data.get("status"));
        assertNotNull(data.get("price"));
        assertNotNull(data.get("creditAmount"));
        assertNotNull(data.get("message"));
        System.out.println("purchase接口通过(待确认): orderNo=" + data.get("orderNo"));
        logout();
    }
}
