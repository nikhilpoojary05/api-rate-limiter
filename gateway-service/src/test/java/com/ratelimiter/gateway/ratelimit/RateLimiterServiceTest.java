package com.ratelimiter.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.gateway.model.RateLimitResult;
import com.ratelimiter.gateway.model.RateLimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The routing and unit conversion between a stored rule and the Lua limiters. Two of the
 * rate-limiting bugs found in review lived here rather than in the scripts: an unmatched
 * rule let requests through unlimited, and the token bucket was handed a per-minute limit
 * as a per-second rate.
 */
class RateLimiterServiceTest {

    private SlidingWindowRateLimiter slidingWindow;
    private TokenBucketRateLimiter tokenBucket;
    private RateLimiterService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        slidingWindow = mock(SlidingWindowRateLimiter.class);
        tokenBucket = mock(TokenBucketRateLimiter.class);
        RateLimitResult ok = new RateLimitResult(true, 1, 0);
        when(slidingWindow.checkLimit(anyString(), anyInt(), anyLong())).thenReturn(Mono.just(ok));
        when(tokenBucket.checkLimit(anyString(), anyInt(), anyDouble(), anyLong())).thenReturn(Mono.just(ok));

        service = new RateLimiterService(slidingWindow, tokenBucket,
                mock(ReactiveRedisTemplate.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "defaultRequestLimit", 60);
        ReflectionTestUtils.setField(service, "defaultWindowMs", 60_000L);
        ReflectionTestUtils.setField(service, "publicRequestLimit", 20);
        ReflectionTestUtils.setField(service, "publicWindowMs", 60_000L);
    }

    @SuppressWarnings("unchecked")
    private void cacheRule(RateLimitRule rule) {
        Map<String, RateLimitRule> cache =
                (Map<String, RateLimitRule>) ReflectionTestUtils.getField(service, "ruleCache");
        cache.put(rule.getTenantId() + ":" + rule.getTier(), rule);
    }

    @Test
    @DisplayName("a tenant with no matching rule gets the default limit, not unlimited access")
    void unmatchedTenantGetsDefault() {
        service.isAllowed("unknown-tenant", "u1", "TIER_X").block();

        verify(slidingWindow).checkLimit("unknown-tenant:u1", 60, 60_000L);
        verifyNoInteractions(tokenBucket);
    }

    @Test
    @DisplayName("an inactive rule falls back to the default limit")
    void inactiveRuleGetsDefault() {
        cacheRule(RateLimitRule.builder().tenantId("acme-corp").tier("TIER_A")
                .algorithm("SLIDING_WINDOW").requestLimit(1000).windowMs(60_000).active(false).build());

        service.isAllowed("acme-corp", "u1", "TIER_A").block();

        verify(slidingWindow).checkLimit("acme-corp:u1", 60, 60_000L);
    }

    @Test
    @DisplayName("a sliding-window rule is applied as configured")
    void slidingWindowRule() {
        cacheRule(RateLimitRule.builder().tenantId("acme-corp").tier("TIER_A")
                .algorithm("SLIDING_WINDOW").requestLimit(100).windowMs(60_000).active(true).build());

        service.isAllowed("acme-corp", "u1", "TIER_A").block();

        verify(slidingWindow).checkLimit("acme-corp:u1", 100, 60_000L);
    }

    @Test
    @DisplayName("a token-bucket rule of 10 per minute refills at 1/6 token per second")
    void tokenBucketConvertsToPerSecond() {
        cacheRule(RateLimitRule.builder().tenantId("free-user-co").tier("TIER_FREE")
                .algorithm("TOKEN_BUCKET").requestLimit(10).windowMs(60_000).burstCapacity(20)
                .active(true).build());

        service.isAllowed("free-user-co", "u1", "TIER_FREE").block();

        ArgumentCaptor<Double> rate = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(tokenBucket).checkLimit(eq("free-user-co:u1"), eq(20), rate.capture(), ttl.capture());

        assertThat(rate.getValue())
                .as("tokens per second; previously 10, sixty times too fast")
                .isCloseTo(10 / 60.0, within(1e-9));
        assertThat(ttl.getValue())
                .as("long enough to refill an empty bucket: 20 tokens at 1/6 per second")
                .isEqualTo(120_000L);
    }

    @Test
    @DisplayName("unauthenticated callers are limited per IP under their own key space")
    void publicLimitIsPerIp() {
        service.isAllowedForIp("203.0.113.7", "auth").block();

        verify(slidingWindow).checkLimit("public:auth:203.0.113.7", 20, 60_000L);
    }
}
