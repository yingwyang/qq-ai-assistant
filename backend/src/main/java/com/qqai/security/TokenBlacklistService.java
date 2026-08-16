package com.qqai.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * JWT 黑名单服务。
 *
 * 实现说明:黑名单为进程内存缓存(Caffeine),服务重启后已登出 token 的 JTI 会重新生效。
 * 兜底机制:用户修改密码/被踢时 tokenVersion 自增(users.token_version,持久化在数据库),
 * 旧 token 因 tv 不匹配永久失效,因此重启丢失黑名单不会造成安全漏洞,
 * 仅影响"仅登出未改密"的 token 在重启后的剩余有效期。
 * 若未来需要强一致登出,可将 JTI 迁移到 Redis(或数据库表)存储。
 */
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
