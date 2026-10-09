package com.ratelimiter.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.security.JwtUtils;
import com.ratelimiter.gateway.support.TestRedis;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The gateway is the only place identity headers are set, and downstream services trust
 * them. These tests pin who is allowed to produce X-Tenant-Id and X-User-Roles: the
 * gateway from a verified token, never the client.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-that-is-comfortably-over-thirty-two-bytes";

    private JwtUtils jwtUtils;
    private JwtAuthenticationFilter filter;
    private ReactiveRedisTemplate<String, String> redis;

    /** Captures what the filter forwards downstream; null means the chain was never called. */
    private final AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange.getRequest());
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        jwtUtils = jwtUtils(SECRET, 900_000);
        redis = TestRedis.template();
        filter = new JwtAuthenticationFilter(jwtUtils, redis, new ObjectMapper());
        forwarded.set(null);
    }

    private static JwtUtils jwtUtils(String secret, long accessExpiryMs) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "jwtSecret", secret);
        ReflectionTestUtils.setField(utils, "accessTokenExpiryMs", accessExpiryMs);
        ReflectionTestUtils.setField(utils, "refreshTokenExpiryMs", 604_800_000L);
        return utils;
    }

    private String token(JwtUtils utils, String tenant, List<String> roles) {
        return utils.generateAccessToken("7", "alice", tenant,
                "11111111-1111-1111-1111-111111111111", "TIER_A", roles);
    }

    private MockServerWebExchange run(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, chain).block(Duration.ofSeconds(5));
        return exchange;
    }

    @Test
    @DisplayName("public routes cannot be used to inject identity headers")
    void publicRouteStripsSpoofedIdentity() {
        run(MockServerHttpRequest.post("/api/auth/login")
                .header("X-Tenant-Id", "evil-corp")
                .header("X-User-Roles", "ROLE_SUPER_ADMIN")
                .header("X-User-Id", "1")
                .header("X-Tenant-Uid", "spoofed")
                .header("X-User-Tier", "TIER_A")
                .build());

        HttpHeaders headers = forwarded.get().getHeaders();
        assertThat(headers.containsKey("X-Tenant-Id")).isFalse();
        assertThat(headers.containsKey("X-User-Roles")).isFalse();
        assertThat(headers.containsKey("X-User-Id")).isFalse();
        assertThat(headers.containsKey("X-Tenant-Uid")).isFalse();
        assertThat(headers.containsKey("X-User-Tier")).isFalse();
    }

    @Test
    @DisplayName("a protected route without a token is rejected and never forwarded")
    void missingTokenIsRejected() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/demo/ping").build());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("identity is taken from the verified token, overriding anything the client sent")
    void identityComesFromTheToken() {
        String jwt = token(jwtUtils, "acme-corp", List.of("ROLE_USER"));

        run(MockServerHttpRequest.get("/api/demo/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .header("X-Tenant-Id", "evil-corp")
                .header("X-User-Roles", "ROLE_SUPER_ADMIN")
                .build());

        HttpHeaders headers = forwarded.get().getHeaders();
        assertThat(headers.get("X-Tenant-Id")).containsExactly("acme-corp");
        assertThat(headers.get("X-User-Roles")).containsExactly("ROLE_USER");
        assertThat(headers.get("X-User-Id")).containsExactly("7");
    }

    @Test
    @DisplayName("a token signed with another key is rejected")
    void foreignSignatureIsRejected() {
        JwtUtils attacker = jwtUtils("a-completely-different-key-also-over-32-bytes-long", 900_000);
        String forged = token(attacker, "acme-corp", List.of("ROLE_SUPER_ADMIN"));

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/demo/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged).build());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() {
        String expired = token(jwtUtils(SECRET, -60_000), "acme-corp", List.of("ROLE_USER"));

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/demo/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired).build());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a query-string token is accepted on the SSE stream, which cannot send headers")
    void sseAcceptsQueryToken() {
        String jwt = token(jwtUtils, "acme-corp", List.of("ROLE_ADMIN"));

        run(MockServerHttpRequest.get("/api/admin/analytics/live").queryParam("token", jwt).build());

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().get("X-Tenant-Id")).containsExactly("acme-corp");
    }

    @Test
    @DisplayName("...but nowhere else: a query-string token on any other route is ignored")
    void queryTokenIgnoredElsewhere() {
        String jwt = token(jwtUtils, "acme-corp", List.of("ROLE_ADMIN"));

        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/admin/rules")
                .queryParam("token", jwt).build());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("only health and info are public; other actuator endpoints need a token")
    void actuatorIsNotBlanketPublic() {
        run(MockServerHttpRequest.get("/actuator/health").build());
        assertThat(forwarded.get()).as("health is public").isNotNull();

        forwarded.set(null);
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/actuator/prometheus").build());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    /**
     * Performance regression guard. The filter used to validate the token and then call
     * five extract* methods, each re-parsing it: six HMAC verifications per request, which
     * profiling put at about a fifth of the gateway's CPU. One is enough.
     */
    @Test
    @DisplayName("an authenticated request parses the token exactly once")
    void parsesTokenOnce() {
        JwtUtils counting = spy(jwtUtils);
        filter = new JwtAuthenticationFilter(counting, redis, new ObjectMapper());
        String jwt = token(jwtUtils, "acme-corp", List.of("ROLE_USER"));

        run(MockServerHttpRequest.get("/api/demo/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt).build());

        assertThat(forwarded.get()).isNotNull();
        verify(counting, times(1)).parseToken(anyString());
    }

    // ---- API keys ------------------------------------------------------------------

    /** Publishes a key the way the admin service does: only its hash, in the apikeys hash. */
    private String publishKey(String tenant, String tier) {
        String key = "test-key-" + java.util.UUID.randomUUID();
        redis.opsForHash().put(JwtAuthenticationFilter.API_KEYS, JwtAuthenticationFilter.sha256Hex(key),
                "{\"tenantId\":\"" + tenant + "\",\"tier\":\"" + tier + "\"}").block(Duration.ofSeconds(5));
        return key;
    }

    @Test
    @DisplayName("a published API key acts as its tenant, as an API client, and is not forwarded")
    void apiKeyActsAsTenant() {
        String key = publishKey("beta-inc", "TIER_B");

        run(MockServerHttpRequest.get("/api/demo/ping")
                .header(JwtAuthenticationFilter.API_KEY_HEADER, key)
                .header("X-User-Roles", "ROLE_SUPER_ADMIN")
                .build());

        HttpHeaders headers = forwarded.get().getHeaders();
        assertThat(headers.getFirst("X-Tenant-Id")).isEqualTo("beta-inc");
        assertThat(headers.getFirst("X-User-Tier")).isEqualTo("TIER_B");
        assertThat(headers.get("X-User-Roles")).containsExactly(JwtAuthenticationFilter.API_CLIENT_ROLE);
        assertThat(headers.getFirst("X-User-Id")).startsWith("apikey:");
        assertThat(headers.containsKey(JwtAuthenticationFilter.API_KEY_HEADER)).isFalse();
    }

    @Test
    @DisplayName("an unknown API key is refused")
    void unknownApiKeyRefused() {
        MockServerWebExchange exchange = run(MockServerHttpRequest.get("/api/demo/ping")
                .header(JwtAuthenticationFilter.API_KEY_HEADER, "not-a-real-key").build());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    @DisplayName("with both a token and an API key, the token decides and the key is dropped")
    void tokenWinsOverApiKey() {
        String key = publishKey("beta-inc", "TIER_B");
        String jwt = token(jwtUtils, "acme-corp", List.of("ROLE_USER"));

        run(MockServerHttpRequest.get("/api/demo/ping")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .header(JwtAuthenticationFilter.API_KEY_HEADER, key).build());

        assertThat(forwarded.get().getHeaders().getFirst("X-Tenant-Id")).isEqualTo("acme-corp");
        assertThat(forwarded.get().getHeaders().containsKey(JwtAuthenticationFilter.API_KEY_HEADER)).isFalse();
    }
}
