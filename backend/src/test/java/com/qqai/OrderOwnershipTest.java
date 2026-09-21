package com.qqai;

import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.User;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.exception.CreditErrorCode;
import com.qqai.repository.SubscriptionOrderRepository;
import com.qqai.repository.UserRepository;
import com.qqai.service.CreditService;
import com.qqai.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单归属（越权）回归测试（企业级化批次 C.4）。
 *
 * 背景：按 orderNo 的用户端接口原先在 Controller 里各写一遍「查找 + 比对 userId」，
 * 而 orderNo 是外部输入 —— 只要某个新端点忘了比对，就能读/操作他人订单。
 * 现在所有归属判定收口到 {@code SubscriptionService.requireOrderOwner}，
 * 本测试覆盖四个用户端入口：查看详情 / 取消 / 申请退款 / 纠纷申诉。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderOwnershipTest {

    @Autowired private SubscriptionService subscriptionService;
    @Autowired private CreditService creditService;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;

    private Long ownerId;
    private Long strangerId;

    @BeforeEach
    void setUp() {
        subscriptionOrderRepository.deleteAll();
        userRepository.deleteAll();
        ownerId = createUser("order-owner");
        strangerId = createUser("order-stranger");
        creditService.ensureAccount(ownerId);
        creditService.ensureAccount(strangerId);
    }

    private Long createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("x");
        user.setRole("USER");
        user.setActive(true);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user).getId();
    }

    private BizException forbidden(Executable action) {
        BizException ex = assertThrows(BizException.class, action::run);
        assertEquals(CreditErrorCode.FORBIDDEN_ORDER, ex.getErrorCode(),
                "越权访问必须以 FORBIDDEN_ORDER 拒绝，实际：" + ex.getErrorCode() + " / " + ex.getMessage());
        return ex;
    }

    private interface Executable {
        void run();
    }

    @Test
    @DisplayName("查看他人订单详情：403（不是 404，避免探测订单号）")
    void strangerCannotReadOrderDetail() {
        SubscriptionOrder order = subscriptionService.createOrder(ownerId, SubscriptionTier.LITE, null, null);

        forbidden(() -> subscriptionService.getOrderForUser(order.getOrderNo(), strangerId));
        // 本人可读
        assertEquals(order.getOrderNo(),
                subscriptionService.getOrderForUser(order.getOrderNo(), ownerId).getOrderNo());
    }

    @Test
    @DisplayName("未登录（userId=null）调用用户端订单接口：403")
    void anonymousOwnerIdIsRejected() {
        SubscriptionOrder order = subscriptionService.createOrder(ownerId, SubscriptionTier.LITE, null, null);
        forbidden(() -> subscriptionService.getOrderForUser(order.getOrderNo(), null));
        forbidden(() -> subscriptionService.cancelOrderAsUser(order.getOrderNo(), null, "未登录"));
    }

    @Test
    @DisplayName("取消他人订单：403，且订单状态不变")
    void strangerCannotCancelOrder() {
        SubscriptionOrder order = subscriptionService.createOrder(ownerId, SubscriptionTier.LITE, null, null);

        forbidden(() -> subscriptionService.cancelOrderAsUser(order.getOrderNo(), strangerId, "恶意取消"));

        SubscriptionOrder reloaded = subscriptionOrderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertEquals("PENDING", reloaded.getStatus().name(), "越权取消不得改变订单状态");

        // 本人取消成功
        SubscriptionOrder cancelled = subscriptionService.cancelOrderAsUser(order.getOrderNo(), ownerId, "用户取消");
        assertEquals("CANCELLED", cancelled.getStatus().name());
    }

    @Test
    @DisplayName("申请他人订单退款 / 发起他人订单纠纷：403")
    void strangerCannotRefundOrDispute() {
        SubscriptionOrder order = subscriptionService.createOrder(ownerId, SubscriptionTier.LITE, null, null);
        subscriptionService.markPaid(order.getOrderNo(), "MANUAL", null);

        forbidden(() -> subscriptionService.requestRefund(order.getOrderNo(), "恶意退款", strangerId));
        forbidden(() -> subscriptionService.disputeOrder(order.getOrderNo(), "恶意申诉", strangerId));

        SubscriptionOrder reloaded = subscriptionOrderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertEquals("PAID", reloaded.getStatus().name(), "越权操作不得改变订单状态");
    }

    @Test
    @DisplayName("订单不存在时仍是 404（区别于越权的 403）")
    void missingOrderStillReturnsNotFound() {
        BizException ex = assertThrows(BizException.class,
                () -> subscriptionService.getOrderForUser("NO-SUCH-ORDER", ownerId));
        assertEquals(404, ex.getCode());
    }
}
