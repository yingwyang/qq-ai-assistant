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
    
    private SecretKey getSigningKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "jwt.secret 未配置。请通过环境变量 JWT_SECRET 提供至少 32 字符的密钥。");
        }
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Long userId, String username, String role, Integer tokenVersion) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);
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
    
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
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
        }
        return false;
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
}
