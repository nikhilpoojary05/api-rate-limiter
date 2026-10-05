package com.ratelimiter.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.gateway.model.RateLimitResult;
import com.ratelimiter.gateway.ratelimit.RateLimiterService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The metrics behind the Grafana dashboard: what gets counted, and under which tags. */
class RateLimitingFilterMetricsTest {

    private static final GatewayFilterChain UPSTREAM = exchange -> Mono.empty();

    private final RateLimiterService limiter = mock(RateLimiterService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimitingFilter filter = new RateLimitingFilter(
            limiter, mock(ReactiveRedisTemplate.class, RETURNS_DEEP_STUBS), new ObjectMapper(), registry);

    private static MockServerWebExchange tenantRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/demo/ping")
                .header("X-Tenant-Id", "acme-corp")
                .header("X-User-Id", "42")
                .header("X-User-Tier", "TIER_A"));
    }

    private void run(MockServerWebExchange exchange) {
        filter.filter(exchange, UPSTREAM).onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(5));
    }

    private double requests(String tenant, String outcome) {
        var counter = registry.find("gateway.requests").tag("tenant", tenant).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static RateLimitResult result(boolean allowed) {
        return new RateLimitResult(allowed, allowed ? 9 : 0, System.currentTimeMillis() + 60_000);
    }

    @Test
    @DisplayName("an admitted request is counted and timed under its tenant")
    void countsAllowed() {
        when(limiter.isAllowed(anyString(), anyString(), anyString())).thenReturn(Mono.just(result(true)));

        run(tenantRequest());

        assertThat(requests("acme-corp", "allowed")).isEqualTo(1);
        assertThat(registry.find("gateway.request.duration").tag("tenant", "acme-corp").tag("outcome", "allowed")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rate-limited request is counted as blocked")
    void countsBlocked() {
        when(limiter.isAllowed(anyString(), anyString(), anyString())).thenReturn(Mono.just(result(false)));

        run(tenantRequest());

        assertThat(requests("acme-corp", "blocked")).isEqualTo(1);
        assertThat(requests("acme-corp", "allowed")).isZero();
    }

    @Test
    @DisplayName("a blocked login attempt is counted, under the unauthenticated tag")
    void countsBlockedLogin() {
        when(limiter.isAllowedForIp(any(), anyString())).thenReturn(Mono.just(result(false)));

        run(MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")));

        assertThat(requests(RateLimitingFilter.UNAUTHENTICATED, "blocked")).isEqualTo(1);
    }

    @Test
    @DisplayName("metrics are tagged by tenant and outcome only, never by user")
    void noPerUserSeries() {
        when(limiter.isAllowed(anyString(), anyString(), anyString())).thenReturn(Mono.just(result(true)));

        run(tenantRequest());

        // "le" is the histogram bucket boundary: a fixed set of values, not one per user.
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .contains("tenant", "outcome")
                    .isSubsetOf("tenant", "outcome", "le");
        }
    }
}
