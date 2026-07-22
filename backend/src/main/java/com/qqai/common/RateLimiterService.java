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

        if (timestamps.size() < maxRequests) {
            timestamps.offerLast(now);
            return true;
        }
        return false;
    }
}
