package com.qqai.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class TokenBlacklistService {

    private final Cache<String, Boolean> blacklistCache;

    @Autowired
    public TokenBlacklistService(JwtUtil jwtUtil) {
        this.blacklistCache = Caffeine.newBuilder()
                .expireAfterWrite(jwtUtil.getExpirationTime(), TimeUnit.MILLISECONDS)
                .maximumSize(100_000)
                .build();
    }

    public void invalidateJti(String jti) {
        blacklistCache.put(jti, true);
    }

    public boolean isJtiBlacklisted(String jti) {
        return blacklistCache.getIfPresent(jti) != null;
    }
}
