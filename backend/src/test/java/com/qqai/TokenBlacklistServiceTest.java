package com.qqai;

import com.qqai.security.JwtUtil;
import com.qqai.security.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 黑名单回归测试（对应审查问题 #2：登出后 token 会"复活"）。
 *
 * <p>原实现把黑名单条目 TTL 固定为 {@code jwt.expiration}（24h），而勾选「记住我」签发的 token
 * 有效期是 30 天。于是：登出 → 24 小时后黑名单条目自动过期 → 那枚 30 天 token 重新可用。
 * 现在条目寿命对齐 token 自身的 {@code exp}，这里把该语义钉住。</p>
 */
class TokenBlacklistServiceTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-32-characters-long";
    private static final long ONE_DAY_MS = 86_400_000L;
    private static final long THIRTY_DAYS_MS = 30 * ONE_DAY_MS;

    private JwtUtil jwtUtil;
    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "jwtExpiration", ONE_DAY_MS);
        ReflectionTestUtils.setField(jwtUtil, "jwtRememberMeExpiration", THIRTY_DAYS_MS);
        service = new TokenBlacklistService(jwtUtil);
    }

    @Test
    @DisplayName("按 token 自身有效期入黑名单：30 天的「记住我」token 条目不会提前失效")
    void blacklistedEntryOutlivesTwentyFourHoursForLongLivedToken() {
        String token = jwtUtil.generateToken(1L, "u", "USER", 0, THIRTY_DAYS_MS);
        String jti = jwtUtil.getJtiFromToken(token);

        long expiresAt = jwtUtil.getExpirationFromToken(token);
        assertTrue(expiresAt - System.currentTimeMillis() > ONE_DAY_MS,
                "「记住我」token 的有效期应超过 24 小时（本用例前提）");

        service.invalidateJti(jti, expiresAt);
        assertTrue(service.isJtiBlacklisted(jti));
    }

    @Test
    @DisplayName("条目寿命取自传入的 token 过期时间，而非固定 24h（已过期即视为不在黑名单）")
    void entryTtlFollowsTokenExpiryNotFixedWindow() {
        String jti = UUID.randomUUID().toString();

        // 过期时刻已过 → 条目立即失效（说明 TTL 由 token 的 exp 决定，不是写死的 24h）
        service.invalidateJti(jti, System.currentTimeMillis() - 1_000L);
        assertFalse(service.isJtiBlacklisted(jti), "已过期的 token 条目不应再留在黑名单里");

        // 换一个尚未过期的时刻 → 条目有效
        String jti2 = UUID.randomUUID().toString();
        service.invalidateJti(jti2, System.currentTimeMillis() + THIRTY_DAYS_MS);
        assertTrue(service.isJtiBlacklisted(jti2));
    }

    @Test
    @DisplayName("未提供有效期时按最长寿命保守保留（宁可多占内存也不提前放行）")
    void unknownExpiryUsesConservativeMaxTtl() {
        String jti = UUID.randomUUID().toString();
        service.invalidateJti(jti);
        assertTrue(service.isJtiBlacklisted(jti));
    }

    @Test
    @DisplayName("空 jti 与查询 null 安全，不抛异常")
    void handlesBlankInput() {
        service.invalidateJti(null);
        service.invalidateJti("  ");
        assertFalse(service.isJtiBlacklisted(null));
        assertFalse(service.isJtiBlacklisted(""));
        assertFalse(service.isJtiBlacklisted(UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("登出链路端到端：签发的 token 能读出过期时间并进入黑名单")
    void logoutFlowUsesTokenOwnExpiry() {
        String token = jwtUtil.generateToken(7L, "someone", "USER", 0, THIRTY_DAYS_MS);
        assertTrue(jwtUtil.validateToken(token));

        String jti = jwtUtil.getJtiFromToken(token);
        long expiresAt = jwtUtil.getExpirationFromToken(token);

        assertTrue(expiresAt > 0, "应能从 token 读出过期时间（登出时传给黑名单用）");
        service.invalidateJti(jti, expiresAt);
        assertTrue(service.isJtiBlacklisted(jti), "登出后该 token 必须处于黑名单中");
    }

    @Test
    @DisplayName("无法解析的 token 读过期时间返回 -1（由调用方走保守分支）")
    void unparsableTokenReturnsMinusOne() {
        assertEquals(-1L, jwtUtil.getExpirationFromToken("not-a-jwt"));
    }
}
