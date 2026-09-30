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

class TokenBucketRateLimiterTest {

    private ReactiveRedisTemplate<String, String> redis;
    private MutableClock clock;
    private TokenBucketRateLimiter limiter;
    private String key;

    @BeforeEach
    void setUp() {
        redis = TestRedis.template();
        clock = MutableClock.startingNow();
        limiter = new TokenBucketRateLimiter(redis, TestRedis.tokenBucketScript(), clock);
        key = "test:" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        redis.delete("rl:tb:" + key).block(Duration.ofSeconds(5));
    }

    private RateLimitResult check(int capacity, double refillPerSecond) {
        return limiter.checkLimit(key, capacity, refillPerSecond, 60_000).block(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a full bucket admits its capacity as a burst, then blocks")
    void burstThenBlock() {
        for (int i = 0; i < 5; i++) {
            assertThat(check(5, 1.0).allowed()).as("request %d", i + 1).isTrue();
        }
        assertThat(check(5, 1.0).allowed()).isFalse();
    }

    /**
     * Guards the unit bug fixed earlier: the script refills per SECOND. At 1 token/s an
     * empty bucket yields exactly one request per elapsed second — not ten, which is what
     * passing a per-minute limit straight through used to produce.
     */
    @Test
    @DisplayName("refills at exactly the per-second rate it is given")
    void refillsPerSecond() {
        for (int i = 0; i < 3; i++) {
            check(3, 1.0);
        }
        assertThat(check(3, 1.0).allowed()).as("bucket drained").isFalse();

        clock.advance(Duration.ofMillis(999));
        assertThat(check(3, 1.0).allowed()).as("0.999 tokens is not a token").isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(check(3, 1.0).allowed()).as("one token at 1.000s").isTrue();
        assertThat(check(3, 1.0).allowed()).as("and only one").isFalse();
    }

    @Test
    @DisplayName("10 per minute means one token every 6 seconds")
    void fractionalRate() {
        double tenPerMinute = 10 / 60.0;
        check(1, tenPerMinute);
        assertThat(check(1, tenPerMinute).allowed()).isFalse();

        clock.advance(Duration.ofMillis(5_900));
        assertThat(check(1, tenPerMinute).allowed()).as("5.9s").isFalse();

        clock.advance(Duration.ofMillis(200));
        assertThat(check(1, tenPerMinute).allowed()).as("6.1s").isTrue();
    }

    @Test
    @DisplayName("an idle bucket never refills past its capacity")
    void refillIsCapped() {
        for (int i = 0; i < 4; i++) {
            check(4, 1.0);
        }
        clock.advance(Duration.ofHours(1));

        int admitted = 0;
        for (int i = 0; i < 10; i++) {
            if (check(4, 1.0).allowed()) {
                admitted++;
            }
        }
        assertThat(admitted).isEqualTo(4);
    }

    @Test
    @DisplayName("an idle bucket keeps its state for the TTL it is given")
    void ttlIsApplied() {
        check(5, 1.0);
        Duration ttl = redis.getExpire("rl:tb:" + key).block(Duration.ofSeconds(5));
        assertThat(ttl).isNotNull();
        assertThat(ttl.getSeconds()).isBetween(50L, 60L);
    }

    @Test
    @DisplayName("200 concurrent requests in the same millisecond admit exactly the capacity")
    void exactUnderConcurrency() {
        int capacity = 20;
        int attempts = 200;

        List<RateLimitResult> results = Flux.range(0, attempts)
                .flatMap(i -> limiter.checkLimit(key, capacity, 1.0, 60_000), attempts)
                .collectList()
                .block(Duration.ofSeconds(30));

        long allowed = results.stream().filter(RateLimitResult::allowed).count();
        assertThat(allowed)
                .as("admitted out of %d same-millisecond requests (Redis: %s)",
                        attempts, TestRedis.description())
                .isEqualTo(capacity);
    }
}
