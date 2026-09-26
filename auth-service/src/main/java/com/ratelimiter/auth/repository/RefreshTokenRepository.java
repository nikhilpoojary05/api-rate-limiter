package com.ratelimiter.auth.repository;

import com.ratelimiter.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenAndRevokedFalse(String token);

    /** Includes already-revoked rows, so replay of a spent token can be detected. */
    Optional<RefreshToken> findByToken(String token);

    void deleteAllByUserId(String userId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.token = :token")
    void revokeByToken(String token);

    /** Used when a spent token is replayed: assume the whole family is compromised. */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    int revokeAllForUser(String userId);

    List<RefreshToken> findAllByUserIdAndRevokedFalse(String userId);
}
