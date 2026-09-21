package com.qqai;

import com.qqai.common.RateLimiterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限流器回归（企业级化批次 E）。
 *
 * 背景：旧实现用 {@code ConcurrentHashMap<String, ArrayDeque<Instant>>}——ArrayDeque 非线程安全
 * 却被并发清理/写入，且空 key 清理依赖"恰好读到空队列"会内存泄漏。换成 Caffeine 原子计数后，
 * 这里覆盖：窗口内计数、超限拒绝、键隔离、参数校验，以及并发下计数不丢不错。
 */
class RateLimiterServiceTest {

    @Test
    @DisplayName("窗口内放行到上限，超过上限拒绝")
    void allowsUpToLimitThenRejects() {
        RateLimiterService limiter = new RateLimiterService();
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.isAllowed("login:ip1:admin", 5, 1), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(limiter.isAllowed("login:ip1:admin", 5, 1), "第 6 次应被拒绝");
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void differentKeysAreIndependent() {
        RateLimiterService limiter = new RateLimiterService();
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.isAllowed("register:1.1.1.1", 3, 1));
        }
        assertFalse(limiter.isAllowed("register:1.1.1.1", 3, 1));
        assertTrue(limiter.isAllowed("register:2.2.2.2", 3, 1), "换一个 IP 不受前一个 key 影响");
    }

    @Test
    @DisplayName("非法参数直接拒绝（空 key / 上限<=0）")
    void invalidArgumentsAreRejected() {
        RateLimiterService limiter = new RateLimiterService();
        assertFalse(limiter.isAllowed(null, 5, 1));
        assertFalse(limiter.isAllowed("  ", 5, 1));
        assertFalse(limiter.isAllowed("k", 0, 1));
        assertFalse(limiter.isAllowed("k", -1, 1));
    }

    @Test
    @DisplayName("并发下计数精确：8 线程共 80 次请求、上限 50，恰好放行 50 次")
    void concurrentCountingIsExact() throws Exception {
        RateLimiterService limiter = new RateLimiterService();
        int threads = 8;
        int perThread = 10;
        int limit = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.isAllowed("concurrent-key", limit, 1)) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在 30s 内结束");

        assertEquals(0, errors.get(), "并发调用不应抛异常（旧实现会因非线程安全队列出错）");
        assertEquals(limit, allowed.get(), "放行次数必须精确等于上限，多放=限流失效，少放=误伤用户");
    }
}
