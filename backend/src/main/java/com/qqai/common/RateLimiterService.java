package com.qqai.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiterService {

    private final Map<String, Deque<Instant>> requestTimestamps = new ConcurrentHashMap<>();

    public boolean isAllowed(String key, int maxRequests, int windowMinutes) {
        Deque<Instant> timestamps = requestTimestamps.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant now = Instant.now();
        Instant windowStart = now.minus(windowMinutes, ChronoUnit.MINUTES);

        while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
            timestamps.pollFirst();
        }

        // 清理空队列防止内存泄漏
        if (timestamps.isEmpty()) {
            requestTimestamps.remove(key, timestamps);
        }

        if (timestamps.size() < maxRequests) {
            // 重新获取或创建时间戳队列（可能已被 remove）
            timestamps = requestTimestamps.computeIfAbsent(key, k -> new ArrayDeque<>());
            timestamps.offerLast(now);
            return true;
        }
        return false;
    }
}
