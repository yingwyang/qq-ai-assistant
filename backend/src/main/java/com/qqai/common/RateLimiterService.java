package com.qqai.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存限流器（固定窗口计数）。
 *
 * 为什么换成 Caffeine：旧实现是 {@code ConcurrentHashMap<String, ArrayDeque<Instant>>}，
 * 两个问题——
 *  1. `ArrayDeque` 非线程安全，却被并发 `pollFirst` / `offerLast`（滑动窗口清理与写入竞态），
 *     高并发下可能抛异常或算错计数，等于限流可被绕过；
 *  2. 空 key 的清理依赖"恰好读到空队列"，实际会随不同 IP/用户名无限增长（内存泄漏）。
 * Caffeine 提供线程安全的原子计数与 `expireAfterWrite` 自动过期，两个问题一起解决。
 *
 * 语义差异：旧实现是「滑动窗口」，新实现是「固定窗口」（每个 key 首次请求起算 windowMinutes）。
 * 对登录/注册这类"每分钟最多 N 次"的场景，固定窗口是业界常规做法，且不会因为清理竞态而失效。
 */
@Component
public class RateLimiterService {

    /** 不同窗口长度各用一个计数器缓存，避免把 1 分钟窗口和 10 分钟窗口混在一起 */
    private final Cache<Integer, Cache<String, AtomicInteger>> windowCaches = Caffeine.newBuilder()
            .maximumSize(16)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    /**
     * 是否放行本次请求。
     *
     * @param key           限流键（如 "login:1.2.3.4:admin"）
     * @param maxRequests   窗口内允许的最大次数
     * @param windowMinutes 窗口长度（分钟，至少按 1 分钟计）
     */
    public boolean isAllowed(String key, int maxRequests, int windowMinutes) {
        if (key == null || key.isBlank() || maxRequests <= 0) {
            return false;
        }
        int window = Math.max(1, windowMinutes);
        Cache<String, AtomicInteger> counters = windowCaches.get(window, w -> Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofMinutes(w))
                .build());
        AtomicInteger counter = counters.get(key, k -> new AtomicInteger());
        return counter.incrementAndGet() <= maxRequests;
    }
}
