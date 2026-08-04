package com.qqai;

import com.qqai.entity.CreditTransaction;
import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.entity.enums.OrderStatus;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.repository.CreditTransactionRepository;
import com.qqai.repository.SubscriptionOrderRepository;
import com.qqai.repository.UserCreditRepository;
import com.qqai.service.CreditService;
import com.qqai.service.OrderNoGenerator;
import com.qqai.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CreditIntegrationTest {

    @Autowired
    private CreditService creditService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private OrderNoGenerator orderNoGenerator;

    @Autowired
    private UserCreditRepository userCreditRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private SubscriptionOrderRepository subscriptionOrderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void testConcurrentSpendPoints_NoOverdraft() throws Exception {
        Long userId = 1001L;
        int initialBonus = 100;
        int spendPerThread = 11;
        int threadCount = 10;

        transactionTemplate.executeWithoutResult(status -> {
            UserCredit uc = creditService.ensureAccount(userId);
            int currentBonus = uc.getBalance();
            if (currentBonus < initialBonus) {
                creditService.grantPoints(userId, initialBonus - currentBonus,
                        CreditTransactionType.ADMIN_GRANT, "测试初始积分", null, null);
            } else if (currentBonus > initialBonus) {
                creditService.adminAdjust(userId, -(currentBonus - initialBonus),
                        "测试扣减初始余额", 0L);
            }
        });

        UserCredit before = userCreditRepository.findByUserId(userId).orElseThrow();
        assertEquals(initialBonus, before.getBalance(), "初始余额应为100");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            Future<?> f = executor.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(s -> {
                        creditService.spendPoints(userId, spendPerThread,
                                CreditTransactionType.AI_CONSUMPTION, "并发消费测试", "conv-" + Thread.currentThread().getId());
                    });
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
            futures.add(f);
        }

        latch.await();
        executor.shutdown();

        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> f.get());
        }

        UserCredit after = userCreditRepository.findByUserId(userId).orElseThrow();
        assertTrue(after.getBalance() >= 0, "最终余额不能为负数，实际: " + after.getBalance());
        assertEquals(threadCount, successCount.get() + failCount.get(), "所有线程必须完成");

        int expectedMaxSuccess = initialBonus / spendPerThread;
        assertTrue(successCount.get() <= expectedMaxSuccess,
                "成功次数不能超过余额允许上限: 成功=" + successCount.get() + " 上限=" + expectedMaxSuccess);

        Page<CreditTransaction> txPage = creditTransactionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100));
        int totalTx = (int) txPage.getTotalElements();

        long spendTxCount = txPage.getContent().stream()
                .filter(tx -> tx.getType() == CreditTransactionType.AI_CONSUMPTION)
                .count();
        assertEquals(successCount.get(), spendTxCount,
                "消费型流水条数应等于成功次数: 成功=" + successCount.get() + " 消费流水=" + spendTxCount);
        assertTrue(totalTx >= successCount.get() + 1,
                "流水总条数应至少包含初始化+消费流水: 总条数=" + totalTx + " 成功消费=" + successCount.get());

        System.out.println("=== testConcurrentSpendPoints 结果 ===");
        System.out.println("初始余额: " + initialBonus);
        System.out.println("每次消费: " + spendPerThread + " × 线程数: " + threadCount);
        System.out.println("成功: " + successCount.get() + " 失败: " + failCount.get());
        System.out.println("最终余额: " + after.getBalance());
        System.out.println("流水总条数: " + totalTx);
    }

    @Test
    void testOrderNoGenerator_200ConcurrentUnique() throws Exception {
        int count = 200;
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(count);
        Set<String> generated = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger errors = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Future<?> f = executor.submit(() -> {
                try {
                    String no = orderNoGenerator.generate();
                    assertNotNull(no, "订单号不能为空");
                    assertTrue(no.startsWith("SUB-"), "必须以SUB-开头: " + no);
                    assertTrue(generated.add(no), "发现重复订单号: " + no);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
            futures.add(f);
        }

        latch.await();
        executor.shutdown();

        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> f.get());
        }

        assertEquals(0, errors.get(), "生成错误数应为0");
        assertEquals(count, generated.size(), "生成的订单号必须全部唯一，期望=" + count + " 实际=" + generated.size());

        System.out.println("=== testOrderNoGenerator_200ConcurrentUnique 结果 ===");
        System.out.println("生成总数: " + generated.size() + " / " + count);
        System.out.println("错误: " + errors.get());
    }

    @Test
    void testRefundOrder_Idempotent_BalanceChangesOnce() {
        Long userId = 2001L;
        SubscriptionTier planTier = SubscriptionTier.LITE;

        SubscriptionOrder order = transactionTemplate.execute(s ->
                subscriptionService.createOrder(userId, planTier, "127.0.0.1", "test-agent")
        );
        assertNotNull(order);
        assertEquals(OrderStatus.PENDING, order.getStatus());

        transactionTemplate.executeWithoutResult(s ->
                subscriptionService.markPaid(order.getOrderNo(), "alipay", "txn-001")
        );

        SubscriptionOrder paidOrder = subscriptionOrderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertEquals(OrderStatus.PAID, paidOrder.getStatus());

        UserCredit afterPaid = userCreditRepository.findByUserId(userId).orElseThrow();
        int balanceAfterPaid = afterPaid.getBalance();
        int creditAmount = paidOrder.getCreditAmount();
        assertTrue(balanceAfterPaid >= creditAmount,
                "支付后余额应至少包含套餐积分: 余额=" + balanceAfterPaid + " 套餐积分=" + creditAmount);

        int balanceBeforeRefund = userCreditRepository.findByUserId(userId).orElseThrow().getBalance();

        SubscriptionOrder refunded1 = transactionTemplate.execute(s -> {
            try {
                return subscriptionService.refundOrder(order.getOrderNo(), "测试退款", 1L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(OrderStatus.REFUNDED, refunded1.getStatus());

        int balanceAfterFirstRefund = userCreditRepository.findByUserId(userId).orElseThrow().getBalance();
        assertEquals(balanceBeforeRefund - creditAmount, balanceAfterFirstRefund,
                "第一次退款应扣除套餐积分: 退款前=" + balanceBeforeRefund
                        + " 退款后=" + balanceAfterFirstRefund + " 应扣=" + creditAmount);

        int balanceBeforeSecondRefund = balanceAfterFirstRefund;
        try {
            transactionTemplate.executeWithoutResult(s ->
                    subscriptionService.refundOrder(order.getOrderNo(), "重复退款", 1L)
            );
            fail("第二次退款应抛出ALREADY_REFUNDED异常");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue(cause instanceof BizException, "应为BizException，实际: " + cause.getClass());
            assertTrue(cause.getMessage().contains("ALREADY_REFUNDED"),
                    "异常消息应包含ALREADY_REFUNDED，实际: " + cause.getMessage());
            System.out.println("第二次退款拦截成功: " + cause.getMessage());
        }

        int balanceAfterSecondRefund = userCreditRepository.findByUserId(userId).orElseThrow().getBalance();
        assertEquals(balanceBeforeSecondRefund, balanceAfterSecondRefund,
                "第二次退款失败时余额不应变化: 退款前=" + balanceBeforeSecondRefund
                        + " 退款后=" + balanceAfterSecondRefund);

        SubscriptionOrder finalOrder = subscriptionOrderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertEquals(OrderStatus.REFUNDED, finalOrder.getStatus(), "订单状态应保持REFUNDED");

        System.out.println("=== testRefundOrder_Idempotent_BalanceChangesOnce 结果 ===");
        System.out.println("支付后余额: " + balanceAfterPaid);
        System.out.println("第一次退款前: " + balanceBeforeRefund + " → 退款后: " + balanceAfterFirstRefund + " (扣除" + creditAmount + ")");
        System.out.println("第二次退款拦截，余额保持: " + balanceAfterSecondRefund);
        System.out.println("最终订单状态: " + finalOrder.getStatus());
    }

    @Test
    void testMarkPaid_RollbackOnException() {
        Long userId = 3001L;
        SubscriptionTier planTier = SubscriptionTier.PRO;

        SubscriptionOrder order = transactionTemplate.execute(s ->
                subscriptionService.createOrder(userId, planTier, "127.0.0.1", "test-agent")
        );
        String orderNo = order.getOrderNo();

        int balanceBefore = transactionTemplate.execute(s -> {
            creditService.ensureAccount(userId);
            return userCreditRepository.findByUserId(userId).orElseThrow().getBalance();
        });

        try {
            transactionTemplate.executeWithoutResult(s -> {
                subscriptionService.markPaid(orderNo, "alipay", "txn-rollback-001");
                throw new BizException("模拟支付后处理异常，强制回滚");
            });
            fail("应抛出异常触发回滚");
        } catch (BizException e) {
            assertEquals("模拟支付后处理异常，强制回滚", e.getMessage());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue(cause.getMessage().contains("模拟支付后处理异常"),
                    "异常原因应为模拟异常，实际: " + cause.getMessage());
        }

        SubscriptionOrder orderAfter = subscriptionOrderRepository.findByOrderNo(orderNo).orElseThrow();
        assertEquals(OrderStatus.PENDING, orderAfter.getStatus(),
                "回滚后订单状态应为PENDING，实际: " + orderAfter.getStatus());
        assertNull(orderAfter.getPaidAt(), "paidAt应为null");

        int balanceAfter = transactionTemplate.execute(s ->
                userCreditRepository.findByUserId(userId).orElseThrow().getBalance()
        );
        assertEquals(balanceBefore, balanceAfter,
                "回滚后余额应保持不变: 之前=" + balanceBefore + " 之后=" + balanceAfter);

        long purchaseTxCount = transactionTemplate.execute(s ->
                creditTransactionRepository.sumInByUserIdAndType(userId, CreditTransactionType.SUBSCRIPTION_PURCHASE)
        );
        assertEquals(0, purchaseTxCount > 0 ? 1 : 0,
                "回滚后不应产生SUBSCRIPTION_PURCHASE流水: sum=" + purchaseTxCount);

        System.out.println("=== testMarkPaid_RollbackOnException 结果 ===");
        System.out.println("订单状态: " + orderAfter.getStatus() + " (应为PENDING)");
        System.out.println("余额: " + balanceBefore + " → " + balanceAfter + " (保持不变)");
        System.out.println("SUBSCRIPTION_PURCHASE流水总和: " + purchaseTxCount + " (应为0)");
    }
}
