package com.qqai;

import com.qqai.entity.SubscriptionOrder;
import com.qqai.entity.User;
import com.qqai.entity.UserCredit;
import com.qqai.entity.enums.CreditTransactionType;
import com.qqai.entity.enums.SubscriptionTier;
import com.qqai.exception.BizException;
import com.qqai.repository.CreditTransactionRepository;
import com.qqai.repository.SignInRecordRepository;
import com.qqai.repository.SubscriptionOrderRepository;
import com.qqai.repository.UserCreditRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资金链路并发与幂等回归（对应企业级化批次 B）。
 *
 * 覆盖四类此前会被绕过的并发缺陷：
 *  1. 确认收款（markPaid）先查后改：并发重复确认会重复发放积分与权益；
 *  2. 发放积分（grantPoints）读-改-写无锁：并发发放丢更新 / 乐观锁 500；
 *  3. 每日签到：靠唯一约束兜底，冲突后在同一事务内继续查询会抛 UnexpectedRollbackException；
 *  4. 下单 60 秒幂等窗口与写入不在同一锁范围：并发下单生成两单。
 *
 * 另覆盖两条扣费顺序修复：TTS 扣费带幂等键（失败可精确退费）、AI 对话调用前的余额门禁。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MoneyPathConcurrencyTest {

    @Autowired private CreditService creditService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserCreditRepository userCreditRepository;
    @Autowired private CreditTransactionRepository creditTransactionRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;
    @Autowired private SignInRecordRepository signInRecordRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        // 依赖顺序：流水 → 订单/签到 → 账户 → 用户
        creditTransactionRepository.deleteAll();
        subscriptionOrderRepository.deleteAll();
        signInRecordRepository.deleteAll();
        userCreditRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setUsername("money-path-user");
        user.setPassword("x");
        user.setRole("USER");
        user.setActive(true);
        user.setTokenVersion(0);
        user.setCreatedAt(LocalDateTime.now());
        userId = userRepository.save(user).getId();

        // 预建账户：避免并发测试把「账户初始化」的竞态混进被测逻辑
        creditService.ensureAccount(userId);
    }

    /** 并发跑同一批任务，返回每个任务的结果或异常。 */
    private List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private int balanceOf(Long userId) {
        return userCreditRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    private long countTx(CreditTransactionType type, String relatedId) {
        return creditTransactionRepository.findByUserIdAndTypeOrderByCreatedAtDesc(
                        userId, type, org.springframework.data.domain.PageRequest.of(0, 100))
                .getContent().stream()
                .filter(tx -> relatedId.equals(tx.getRelatedId()))
                .count();
    }

    @Test
    @DisplayName("并发确认收款：只成功一次、只发放一次积分")
    void concurrentMarkPaidGrantsOnce() throws Exception {
        SubscriptionOrder order = subscriptionService.createOrder(userId, SubscriptionTier.LITE, "127.0.0.1", "test");
        int before = balanceOf(userId);
        int credit = order.getCreditAmount();
        assertTrue(credit > 0, "测试前提：LITE 套餐应配置正积分");

        List<Object> results = runConcurrently(2, () -> {
            subscriptionService.markPaid(order.getOrderNo(), "MANUAL", null);
            return "ok";
        });

        long success = results.stream().filter("ok"::equals).count();
        long rejected = results.stream().filter(r -> r instanceof BizException).count();
        assertEquals(1, success, "并发确认收款只应有 1 次成功，实际结果=" + results);
        assertEquals(1, rejected, "另一次应被判订单状态非法");

        assertEquals(1, countTx(CreditTransactionType.SUBSCRIPTION_PURCHASE, order.getOrderNo()),
                "同一订单只应产生一条购买流水");
        assertEquals(before + credit, balanceOf(userId), "积分只应发放一次");
    }

    @Test
    @DisplayName("并发发放积分：不丢更新、不报乐观锁异常")
    void concurrentGrantPointsDoesNotLoseUpdates() throws Exception {
        int before = balanceOf(userId);
        int threads = 6;
        int each = 10;

        List<Object> results = runConcurrently(threads, () -> {
            creditService.grantPoints(userId, each, CreditTransactionType.ADMIN_GRANT,
                    "并发测试发放", "concurrency-test", null);
            return "ok";
        });

        long failed = results.stream().filter(r -> r instanceof Exception).count();
        assertEquals(0, failed, "并发发放不应失败（丢更新会表现为 OptimisticLockingFailureException）：" + results);
        assertEquals(before + threads * each, balanceOf(userId), "6 次并发发放必须全部入账");
    }

    @Test
    @DisplayName("并发签到：只成功一次，且不抛 UnexpectedRollbackException")
    void concurrentSignInGrantsOnce() throws Exception {
        int before = balanceOf(userId);

        List<Object> results = runConcurrently(2, () -> {
            creditService.signInToday(userId);
            return "ok";
        });

        long success = results.stream().filter("ok"::equals).count();
        assertEquals(1, success, "并发签到只应有 1 次成功，实际=" + results);

        Object other = results.stream().filter(r -> !"ok".equals(r)).findFirst().orElseThrow();
        assertTrue(other instanceof BizException, "失败方应是业务异常而不是事务回滚异常：" + other);
        assertEquals("ALREADY_SIGNED_IN", ((BizException) other).getErrorCode());

        assertTrue(balanceOf(userId) > before, "签到积分应到账");
        assertEquals(1, signInRecordRepository.count(), "只应有一条签到记录");
    }

    @Test
    @DisplayName("并发下单：60 秒幂等窗口下只产生一单")
    void concurrentCreateOrderProducesSingleOrder() throws Exception {
        List<Object> results = runConcurrently(3, () ->
                subscriptionService.createOrder(userId, SubscriptionTier.LITE, "127.0.0.1", "test"));

        long failed = results.stream().filter(r -> r instanceof Exception).count();
        assertEquals(0, failed, "并发下单不应抛异常：" + results);
        List<String> orderNos = new ArrayList<>();
        for (Object r : results) {
            orderNos.add(((SubscriptionOrder) r).getOrderNo());
        }
        assertEquals(1, new java.util.HashSet<>(orderNos).size(), "并发下单应复用同一订单号：" + orderNos);
        assertEquals(1, subscriptionOrderRepository.count(), "库里只应有一单");
    }

    @Test
    @DisplayName("TTS 扣费带幂等键，失败退费能精确回补")
    void ttsRefundRestoresBalance() {
        int before = balanceOf(userId);
        String requestKey = "tts-test-key-1";

        CreditService.CreditCostResult cost = creditService.spendForTts(userId, "你好，这是一段测试语音文本。", null, false, requestKey);
        assertTrue(cost.getCost() > 0, "非管理员且未封顶时应真实扣费");
        assertEquals(before - cost.getCost(), balanceOf(userId));

        CreditService.CreditCostResult refund = creditService.refundForTts(userId, requestKey, "TTS 合成失败");
        assertTrue(refund.getCost() >= 0);
        assertEquals(before, balanceOf(userId), "退费后余额应回到扣费前");

        // 幂等：重复退费不再回补
        creditService.refundForTts(userId, requestKey, "TTS 合成失败");
        assertEquals(before, balanceOf(userId), "重复退费不得重复回补");
    }

    @Test
    @DisplayName("AI 对话前置门禁：0 余额被拒、有余额放行、管理员免费")
    void assertChatAffordableGatesZeroBalance() {
        // 造 0 余额：直接改账户余额（测试内部手法）
        UserCredit account = userCreditRepository.findByUserId(userId).orElseThrow();
        int original = account.getBalance();
        account.setBalance(0);
        userCreditRepository.save(account);

        BizException rejected = assertThrows(BizException.class,
                () -> creditService.assertChatAffordable(userId, false),
                "0 余额非管理员应在调用大模型前被拒");
        assertEquals("INSUFFICIENT_CREDITS", rejected.getErrorCode());

        // 管理员免费通道（默认 adminFree=true）应放行
        assertDoesNotThrow(() -> creditService.assertChatAffordable(userId, true));

        // 有余额即放行
        UserCredit reloaded = userCreditRepository.findByUserId(userId).orElseThrow();
        reloaded.setBalance(original > 0 ? original : 100);
        userCreditRepository.save(reloaded);
        assertDoesNotThrow(() -> creditService.assertChatAffordable(userId, false));
    }

    @Test
    @DisplayName("退款幂等：同一订单重复退款只扣回一次")
    void refundIsIdempotent() {
        SubscriptionOrder order = subscriptionService.createOrder(userId, SubscriptionTier.LITE, null, null);
        subscriptionService.markPaid(order.getOrderNo(), "MANUAL", null);
        int afterPaid = balanceOf(userId);

        subscriptionService.refundOrderWithRatio(order.getOrderNo(), "测试退款", 1L, 1.0);
        int afterRefund = balanceOf(userId);
        assertTrue(afterRefund < afterPaid, "退款应扣回积分");

        assertThrows(BizException.class,
                () -> subscriptionService.refundOrderWithRatio(order.getOrderNo(), "重复退款", 1L, 1.0),
                "重复退款必须被拒绝");
        assertEquals(afterRefund, balanceOf(userId), "重复退款不得二次扣回");
    }

    @Test
    @DisplayName("取消订单与确认收款互斥：先取消则确认失败")
    void cancelThenMarkPaidIsRejected() {
        SubscriptionOrder order = subscriptionService.createOrder(userId, SubscriptionTier.PRO, null, null);
        subscriptionService.cancelOrder(order.getOrderNo(), userId, "用户取消");

        AtomicInteger granted = new AtomicInteger(countTx(CreditTransactionType.SUBSCRIPTION_PURCHASE, order.getOrderNo()) > 0 ? 1 : 0);
        assertThrows(BizException.class,
                () -> subscriptionService.markPaid(order.getOrderNo(), "MANUAL", null),
                "已取消订单不能再确认收款");
        assertEquals(0, granted.get(), "被拒的确认收款不应发放积分");
    }
}
