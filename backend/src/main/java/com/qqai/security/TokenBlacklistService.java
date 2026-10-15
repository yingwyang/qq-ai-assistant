package com.qqai.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * JWT 黑名单服务（登出即失效）。
 *
 * <p><b>过期口径</b>：每个条目按「该 token 自己的过期时间」过期，而不是统一固定 TTL。
 * 早期实现用固定 {@code jwt.expiration}（24h）做 TTL，于是勾了「记住我」的 30 天 token
 * 在登出 24 小时后会从黑名单里消失、重新变成可用——等于没登出。
 * 现在条目寿命对齐 token 的 {@code exp}：既不早退（留漏洞）也不多留（白占内存）。</p>
 *
 * <p><b>兜底机制</b>：用户改密 / 被禁用 / 角色变更会自增 {@code users.token_version}，
 * 旧 token 因 tv 不匹配永久失效；黑名单是进程内存态，重启后「仅登出未改密」的 token 会恢复有效，
 * 因此本类只解决"生命周期对齐"，不做强一致。需要强一致时可把 JTI 迁到 Redis。</p>
 */
@Component
public class TokenBlacklistService {

    private final Cache<String, Long> blacklistCache;

    /** 未知有效期时的保守保留时长：按最长 token 寿命计 */
    private final long maxTokenTtlMillis;

    @Autowired
    public TokenBlacklistService(JwtUtil jwtUtil) {
        this.maxTokenTtlMillis = Math.max(jwtUtil.getExpirationTime(), jwtUtil.getRememberMeExpirationTime());
        this.blacklistCache = Caffeine.newBuilder()
                .maximumSize(100_000)
                // value 存该 token 的过期时刻，供 Expiry 计算每条各自的寿命
                .expireAfter(new Expiry<String, Long>() {
                    @Override
                    public long expireAfterCreate(String key, Long expiresAtMillis, long currentTime) {
                        long ttlMillis = expiresAtMillis - System.currentTimeMillis();
                        return ttlMillis > 0 ? TimeUnit.MILLISECONDS.toNanos(ttlMillis) : 0L;
                    }

                    @Override
                    public long expireAfterUpdate(String key, Long expiresAtMillis, long currentTime,
                                                  long currentDuration) {
                        return expireAfterCreate(key, expiresAtMillis, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, Long expiresAtMillis, long currentTime,
                                                long currentDuration) {
                        // 读取不刷新寿命
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * 注销某枚 token。
     *
     * @param jti             token 唯一标识
     * @param expiresAtMillis 该 token 的过期时刻（epoch millis）；{@code <= 0} 时按最长寿命保守处理
     */
    public void invalidateJti(String jti, long expiresAtMillis) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long expiresAt = expiresAtMillis > 0 ? expiresAtMillis : System.currentTimeMillis() + maxTokenTtlMillis;
        blacklistCache.put(jti, expiresAt);
    }

    /** 未知 token 有效期时的保守入口：按最长寿命保留，宁可多占内存也不提前放行 */
    public void invalidateJti(String jti) {
        invalidateJti(jti, -1L);
    }

    public boolean isJtiBlacklisted(String jti) {
        return jti != null && blacklistCache.getIfPresent(jti) != null;
    }
}
