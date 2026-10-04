package com.ratelimiter.gateway.ratelimit;

import com.ratelimiter.gateway.model.RateLimitResult;
import com.ratelimiter.gateway.support.MutableClock;
import com.ratelimiter.gateway.support.TestRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several gateway instances share one Redis. They must also share one clock: when each
 * instance passed its own time, instances on different servers disagreed about where the
 * window was, and together admitted more than the limit.
 */
class RedisClockTest {

    private static final int LIMIT = 10;
    private static final long WINDOW_MS = 60_000;

    private ReactiveRedisTemplate<String, String> redis;
    private String key;

    @BeforeEach
    void setUp() {
        redis = TestRedis.template();
        key = "test:" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        redis.delete("rl:sw:" + key).block(Duration.ofSeconds(5));
    }

    private static long admitted(SlidingWindowRateLimiter gateway, String key, int attempts) {
        return Flux.range(0, attempts)
                .flatMap(i -> gateway.checkLimit(key, LIMIT, WINDOW_MS))
                .filter(RateLimitResult::allowed)
                .count()
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("instances passing their own clocks over-admit when the clocks disagree")
    void callerClocksThatDisagreeOverAdmit() {
        // The hazard Redis TIME removes: one server's clock runs a window ahead, so it
        // trims every entry the other just added and starts a fresh window.
        MutableClock clock = MutableClock.startingNow();
        MutableClock skewed = MutableClock.startingNow();
        skewed.advance(Duration.ofMillis(WINDOW_MS + 1_000));
        var gatewayA = new SlidingWindowRateLimiter(redis, TestRedis.slidingWindowScript(), clock);
        var gatewayB = new SlidingWindowRateLimiter(redis, TestRedis.slidingWindowScript(), skewed);

        long total = admitted(gatewayA, key, LIMIT * 2) + admitted(gatewayB, key, LIMIT * 2);

        assertThat(total).isEqualTo(LIMIT * 2L);
    }

    @Test
    @DisplayName("instances on Redis's clock admit exactly the limit between them")
    void instancesOnRedisClockShareOneLimit() {
        var gatewayA = new SlidingWindowRateLimiter(redis, TestRedis.slidingWindowScript(), null);
        var gatewayB = new SlidingWindowRateLimiter(redis, TestRedis.slidingWindowScript(), null);
        var gatewayC = new SlidingWindowRateLimiter(redis, TestRedis.slidingWindowScript(), null);

        // 90 concurrent requests spread over three instances.
        long total = Flux.range(0, 90)
                .flatMap(i -> List.of(gatewayA, gatewayB, gatewayC).get(i % 3).checkLimit(key, LIMIT, WINDOW_MS))
                .filter(RateLimitResult::allowed)
                .count()
                .block(Duration.ofSeconds(10));

        assertThat(total).isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("the reset time comes from Redis's clock, not the instance's")
    void resetTimeComesFromRedis() {
        var gateway = new SlidingWindowRateLimiter(redis, TestRedis.slidingWindowScript(), null);

        long before = redisTimeMs();
        RateLimitResult result = gateway.checkLimit(key, LIMIT, WINDOW_MS).block(Duration.ofSeconds(5));
        long after = redisTimeMs();

        assertThat(result.resetMs()).isBetween(before + WINDOW_MS, after + WINDOW_MS);
    }

    private long redisTimeMs() {
        return redis.execute(connection -> connection.serverCommands().time())
                .next()
                .block(Duration.ofSeconds(5));
    }
}
