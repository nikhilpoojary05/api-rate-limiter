package com.ratelimiter.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
public class JwtUtils {

    /** No default: a missing JWT_SECRET must stop the service, not fall back to a shared key. */
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.access-token-expiry-ms:900000}") // 15 minutes
    private long accessTokenExpiryMs;

    @Value("${jwt.refresh-token-expiry-ms:604800000}") // 7 days
    private long refreshTokenExpiryMs;

    /** Signing keys that shipped in this repo's history and must never be used again. */
    private static final Set<String> BANNED_SECRETS = Set.of(
            "SuperSecretKeyForJwtSigningThatIsAtLeast256BitsLong!!",
            "your-256-bit-secret-key-for-jwt-signing-must-be-long-enough"
    );

    private static final int MIN_SECRET_BYTES = 32; // HS256 requires a 256-bit key

    @PostConstruct
    void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not configured. Set the JWT_SECRET environment variable.");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256.");
        }
        if (BANNED_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "jwt.secret is a known placeholder key that was published in this repository. "
                    + "Generate a new one, e.g. openssl rand -base64 48");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * @param tenantSlug human-readable tenant key ("acme-corp"). This is the canonical
     *                   tenant identity on the wire: rate limit rules, tenant records and
     *                   traffic analytics are all keyed by it.
     * @param tenantUid  the auth-service UUID for the tenant, carried for internal lookups.
     */
    public String generateAccessToken(String userId, String username, String tenantSlug,
                                       String tenantUid, String tier, Collection<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", tenantSlug);
        claims.put("tenant_uid", tenantUid);
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

    public String extractTenantUid(String token) {
        return parseToken(token).get("tenant_uid", String.class);
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
