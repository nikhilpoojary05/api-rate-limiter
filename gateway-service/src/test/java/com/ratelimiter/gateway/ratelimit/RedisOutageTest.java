package com.ratelimiter.gateway.ratelimit;

import com.ratelimiter.gateway.model.RateLimitResult;
import com.ratelimiter.gateway.support.TestRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When Redis is unreachable the limiter cannot know whether a caller is over their limit.
 * These pin the decision: deny by default, allow only when explicitly configured to.
 * Previously both limiters failed open, so anyone able to degrade Redis switched rate
 * limiting off for everyone.
 */
class RedisOutageTest {

    private static LettuceConnectionFactory deadFactory;
    private static ReactiveRedisTemplate<String, String> deadRedis;

    @BeforeAll
    static void pointAtNothing() {
        // Port 1 on loopback: nothing listens, so every command fails fast.
        deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        deadFactory.afterPropertiesSet();
        deadFactory.start();
        deadRedis = new ReactiveRedisTemplate<>(deadFactory, RedisSerializationContext.string());
    }

    @AfterAll
    static void tearDown() {
        deadFactory.destroy();
    }

    @Test
    @DisplayName("sliding window denies when Redis is down")
    void slidingWindowFailsClosed() {
        SlidingWindowRateLimiter limiter =
                new SlidingWindowRateLimiter(deadRedis, TestRedis.slidingWindowScript());

        RateLimitResult result = limiter.checkLimit("outage", 100, 60_000).block(Duration.ofSeconds(15));

        assertThat(result.allowed()).isFalse();
        assertThat(result.resetMs()).as("a usable Retry-After").isGreaterThan(System.currentTimeMillis());
    }

    @Test
    @DisplayName("token bucket denies when Redis is down")
    void tokenBucketFailsClosed() {
        TokenBucketRateLimiter limiter =
                new TokenBucketRateLimiter(deadRedis, TestRedis.tokenBucketScript());

        RateLimitResult result = limiter.checkLimit("outage", 20, 1.0, 60_000).block(Duration.ofSeconds(15));

        assertThat(result.allowed()).isFalse();
    }

    @Test
    @DisplayName("fail-open is honoured only when explicitly enabled")
    void failOpenIsOptIn() {
        SlidingWindowRateLimiter limiter =
                new SlidingWindowRateLimiter(deadRedis, TestRedis.slidingWindowScript());
        ReflectionTestUtils.setField(limiter, "failOpen", true);

        RateLimitResult result = limiter.checkLimit("outage", 100, 60_000).block(Duration.ofSeconds(15));

        assertThat(result.allowed()).isTrue();
    }
}
