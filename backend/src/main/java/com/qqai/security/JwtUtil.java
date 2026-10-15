package com.qqai.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    
    @Value("${jwt.secret:}")
    private String jwtSecret;
    
    @Value("${jwt.expiration:86400000}") // 默认24小时
    private long jwtExpiration;

    /** 「记住我」时的有效期，默认 30 天（2592000000ms）。 */
    @Value("${jwt.remember-me-expiration:2592000000}")
    private long jwtRememberMeExpiration;
    
    private SecretKey getSigningKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "jwt.secret 未配置。请通过环境变量 JWT_SECRET 提供至少 32 字符的密钥。");
        }
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username, String role, Integer tokenVersion) {
        return generateToken(userId, username, role, tokenVersion, jwtExpiration);
    }

    /**
     * 生成指定有效期的 token（「记住我」用更长有效期）。
     *
     * @param expirationMs 有效期毫秒数
     */
    public String generateToken(Long userId, String username, String role, Integer tokenVersion, long expirationMs) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);
        String jti = UUID.randomUUID().toString();

        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("tv", tokenVersion)
                .claim("role", role)
                .id(jti)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }
    
    public String generateToken(String username, String role) {
        return generateToken(-1L, username, role, 0);
    }
    
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }
    
    public String getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("role", String.class);
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("uid", Long.class);
    }

    public Integer getTokenVersionFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("tv", Integer.class);
    }

    public String getJtiFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getId();
    }

    /**
     * 取 token 自身的过期时间（epoch millis）。
     * 用于登出时把黑名单条目的寿命对齐到该 token 的真实有效期——否则「记住我」的 30 天 token
     * 会在黑名单条目按固定 24h 过期后重新变成可用。
     *
     * @return 过期时间；解析失败返回 -1
     */
    public long getExpirationFromToken(String token) {
        try {
            Date expiration = parseToken(token).getExpiration();
            return expiration != null ? expiration.getTime() : -1L;
        } catch (Exception e) {
            log.debug("读取 token 过期时间失败: {}", e.getMessage());
            return -1L;
        }
    }
    
    /**
     * 安全解析：失败返回 {@code null} 并记录原因。
     *
     * <p>替代「先 {@link #validateToken} 再逐个 {@code getXxxFromToken}」的写法——那种写法每取一个声明
     * 都要重新做一次签名校验（过滤器中一次请求要解析 6 遍）。这里解析一次，调用方从 {@link Claims} 里取全部字段。</p>
     */
    public Claims parseTokenSafely(String token) {
        try {
            return parseToken(token);
        } catch (ExpiredJwtException e) {
            log.warn("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT token is unsupported: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT token is malformed: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("JWT signature validation failed: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is empty or null: {}", e.getMessage());
        } catch (JwtException e) {
            // 兜底：其它 JWT 校验异常（如声明类型不符），同样按无效处理
            log.warn("JWT token is invalid: {}", e.getMessage());
        }
        return null;
    }

    public boolean validateToken(String token) {
        return parseTokenSafely(token) != null;
    }
    
    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    
    public long getExpirationTime() {
        return jwtExpiration;
    }

    /** 「记住我」有效期（毫秒）。 */
    public long getRememberMeExpirationTime() {
        return jwtRememberMeExpiration;
    }
}
