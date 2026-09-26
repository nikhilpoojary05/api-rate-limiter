package com.ratelimiter.auth.service;

import com.ratelimiter.auth.entity.RefreshToken;
import com.ratelimiter.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Revokes every outstanding refresh token for a user, in its own transaction.
 *
 * <p>This is a separate bean on purpose. The caller detects a replayed token and then
 * throws to reject the request — which rolls its transaction back. Revoking inside that
 * transaction meant the revocation was rolled back too, leaving the compromised tokens
 * live. REQUIRES_NEW commits the revocation independently, and a separate bean is needed
 * because a self-invocation would not go through the transactional proxy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenRevoker {

    private static final String REDIS_REFRESH_PREFIX = "refresh:";

    private final RefreshTokenRepository refreshTokenRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(String userId) {
        // Read the outstanding tokens before revoking: afterwards the query that finds
        // them returns nothing, and their Redis keys would be left behind.
        List<RefreshToken> outstanding = refreshTokenRepository.findAllByUserIdAndRevokedFalse(userId);
        outstanding.forEach(t -> redisTemplate.delete(REDIS_REFRESH_PREFIX + t.getToken()));

        int revoked = refreshTokenRepository.revokeAllForUser(userId);
        log.warn("Revoked {} outstanding refresh tokens for userId={}", revoked, userId);
        return revoked;
    }
}
