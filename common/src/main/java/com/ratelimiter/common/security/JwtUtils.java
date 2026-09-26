package com.ratelimiter.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
public class JwtUtils {

    @Value("${jwt.secret:your-256-bit-secret-key-for-jwt-signing-must-be-long-enough}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiry-ms:900000}") // 15 minutes
    private long accessTokenExpiryMs;

    @Value("${jwt.refresh-token-expiry-ms:604800000}") // 7 days
    private long refreshTokenExpiryMs;

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(String userId, String username, String tenantId,
                                       String tier, Collection<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", tenantId);
        claims.put("tier", tier);
        claims.put("roles", roles);
        claims.put("username", username);
        claims.put("type", "ACCESS");

        return Jwts.builder()
                .subject(userId)
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(String userId, String tenantId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", tenantId);
        claims.put("type", "REFRESH");

        return Jwts.builder()
                .subject(userId)
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiryMs))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT: {}", e.getMessage());
        } catch (SecurityException e) {
            log.warn("JWT signature invalid: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("JWT validation error: {}", e.getMessage());
        }
        return false;
    }

    public String extractUserId(String token) {
        return parseToken(token).getSubject();
    }

    public String extractTenantId(String token) {
        return parseToken(token).get("tenant_id", String.class);
    }

    public String extractTier(String token) {
        return parseToken(token).get("tier", String.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object roles = parseToken(token).get("roles");
        if (roles instanceof List<?>) {
            return (List<String>) roles;
        }
        return Collections.emptyList();
    }

    public boolean isRefreshToken(String token) {
        return "REFRESH".equals(parseToken(token).get("type", String.class));
    }

    public long getAccessTokenExpiryMs() {
        return accessTokenExpiryMs;
    }

    public long getRefreshTokenExpiryMs() {
        return refreshTokenExpiryMs;
    }
}
