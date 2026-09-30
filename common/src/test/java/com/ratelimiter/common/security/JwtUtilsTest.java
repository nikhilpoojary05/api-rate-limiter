package com.ratelimiter.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilsTest {

    private static final String STRONG = "a-strong-test-secret-well-over-thirty-two-bytes";

    private static JwtUtils withSecret(String secret) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "jwtSecret", secret);
        ReflectionTestUtils.setField(utils, "accessTokenExpiryMs", 900_000L);
        ReflectionTestUtils.setField(utils, "refreshTokenExpiryMs", 604_800_000L);
        return utils;
    }

    @Test
    @DisplayName("refuses to start without a secret")
    void missingSecret() {
        assertThatThrownBy(() -> withSecret(null).validateSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> withSecret("   ").validateSecret())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("refuses a secret too short for HS256")
    void shortSecret() {
        assertThatThrownBy(() -> withSecret("only-31-bytes-long-not-enough!!").validateSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    /** Both keys were published in this repository; either would let anyone mint admin tokens. */
    @ParameterizedTest
    @ValueSource(strings = {
            "SuperSecretKeyForJwtSigningThatIsAtLeast256BitsLong!!",
            "your-256-bit-secret-key-for-jwt-signing-must-be-long-enough"
    })
    @DisplayName("refuses the placeholder keys that were published in this repo")
    void bannedSecrets(String leaked) {
        assertThatThrownBy(() -> withSecret(leaked).validateSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("published in this repository");
    }

    @Test
    @DisplayName("accepts a strong secret")
    void strongSecret() {
        withSecret(STRONG).validateSecret();
    }

    @Test
    @DisplayName("carries the tenant slug as tenant_id and the UUID as tenant_uid")
    void tenantClaims() {
        JwtUtils utils = withSecret(STRONG);
        String token = utils.generateAccessToken("42", "alice", "acme-corp",
                "11111111-1111-1111-1111-111111111111", "TIER_A", List.of("ROLE_ADMIN"));

        // The slug is what rules, tenants and traffic are keyed by. It used to be the UUID,
        // which matched no rule, so rate limiting silently never applied.
        assertThat(utils.extractTenantId(token)).isEqualTo("acme-corp");
        assertThat(utils.extractTenantUid(token)).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(utils.extractUserId(token)).isEqualTo("42");
        assertThat(utils.extractTier(token)).isEqualTo("TIER_A");
        assertThat(utils.extractRoles(token)).containsExactly("ROLE_ADMIN");

        Claims claims = utils.parseToken(token);
        assertThat(claims.get("type", String.class)).isEqualTo("ACCESS");
    }

    @Test
    @DisplayName("rejects a token signed with a different key")
    void foreignKey() {
        String forged = withSecret("another-key-entirely-and-also-over-32-bytes")
                .generateAccessToken("1", "mallory", "acme-corp", "x", "TIER_A", List.of("ROLE_SUPER_ADMIN"));

        assertThat(withSecret(STRONG).validateToken(forged)).isFalse();
    }

    @Test
    @DisplayName("rejects a token whose payload was edited")
    void tamperedPayload() {
        JwtUtils utils = withSecret(STRONG);
        String token = utils.generateAccessToken("1", "alice", "beta-inc", "x", "TIER_B", List.of("ROLE_USER"));

        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        assertThat(utils.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("rejects an expired token")
    void expired() {
        JwtUtils utils = withSecret(STRONG);
        ReflectionTestUtils.setField(utils, "accessTokenExpiryMs", -1_000L);
        String token = utils.generateAccessToken("1", "alice", "acme-corp", "x", "TIER_A", List.of());

        assertThat(utils.validateToken(token)).isFalse();
    }
}
