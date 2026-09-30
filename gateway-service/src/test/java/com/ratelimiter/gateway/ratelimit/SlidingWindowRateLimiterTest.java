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

class SlidingWindowRateLimiterTest {

    private ReactiveRedisTemplate<String, String> redis;
    private MutableClock clock;
    private SlidingWindowRateLimiter limiter;
    private String key;

    @BeforeEach
    void setUp() {
        redis = TestRedis.template();
        clock = MutableClock.startingNow();
        limiter = new SlidingWindowRateLimiter(redis, TestRedis.slidingWindowScript(), clock);
        key = "test:" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        redis.delete("rl:sw:" + key).block(Duration.ofSeconds(5));
    }

    private RateLimitResult check(int limit, long windowMs) {
        return limiter.checkLimit(key, limit, windowMs).block(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("allows exactly the limit, then blocks")
    void allowsExactlyTheLimit() {
        for (int i = 0; i < 5; i++) {
            assertThat(check(5, 60_000).allowed()).as("request %d", i + 1).isTrue();
        }
        assertThat(check(5, 60_000).allowed()).isFalse();
    }

    @Test
    @DisplayName("remaining counts down to zero")
    void remainingCountsDown() {
        assertThat(check(3, 60_000).remaining()).isEqualTo(2);
        assertThat(check(3, 60_000).remaining()).isEqualTo(1);
        assertThat(check(3, 60_000).remaining()).isEqualTo(0);
        assertThat(check(3, 60_000).remaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("requests are admitted again once the window slides past them")
    void windowSlides() {
        for (int i = 0; i < 3; i++) {
            check(3, 1_000);
        }
        assertThat(check(3, 1_000).allowed()).isFalse();

        clock.advance(Duration.ofMillis(999));
        assertThat(check(3, 1_000).allowed()).as("still inside the window").isFalse();

        clock.advance(Duration.ofMillis(2));
        assertThat(check(3, 1_000).allowed()).as("window has moved past the first requests").isTrue();
    }

    @Test
    @DisplayName("the window slides continuously rather than resetting in fixed blocks")
    void slidesContinuously() {
        check(2, 1_000);                          // t = 0
        clock.advance(Duration.ofMillis(600));
        check(2, 1_000);                          // t = 600
        assertThat(check(2, 1_000).allowed()).isFalse();

        clock.advance(Duration.ofMillis(401));    // t = 1001: only the t=0 request has left
        assertThat(check(2, 1_000).allowed()).isTrue();
        assertThat(check(2, 1_000).allowed()).as("t=600 is still counted").isFalse();
    }

    @Test
    @DisplayName("callers are counted independently")
    void keysAreIndependent() {
        for (int i = 0; i < 2; i++) {
            check(2, 60_000);
        }
        assertThat(check(2, 60_000).allowed()).isFalse();

        String otherKey = key + ":other";
        try {
            RateLimitResult other = limiter.checkLimit(otherKey, 2, 60_000).block(Duration.ofSeconds(5));
            assertThat(other.allowed()).isTrue();
        } finally {
            redis.delete("rl:sw:" + otherKey).block(Duration.ofSeconds(5));
        }
    }

    /**
     * The property the design rests on: check-and-increment happens inside one Lua script,
     * so concurrent callers cannot all see "under the limit" and all get in.
     *
     * <p>The clock is frozen, so all 300 requests carry the same timestamp — the case real
     * traffic produces and a wall-clock test does not reliably reach. With a timestamp-only
     * sorted-set member, same-millisecond requests overwrite each other and this admits all
     * 300; it was mutation-tested against exactly that.
     */
    @Test
    @DisplayName("300 concurrent requests in the same millisecond admit exactly the limit")
    void exactUnderConcurrency() {
        int limit = 50;
        int attempts = 300;

        List<RateLimitResult> results = Flux.range(0, attempts)
                .flatMap(i -> limiter.checkLimit(key, limit, 60_000), attempts)
                .collectList()
                .block(Duration.ofSeconds(30));

        long allowed = results.stream().filter(RateLimitResult::allowed).count();
        assertThat(allowed)
                .as("admitted out of %d same-millisecond requests (Redis: %s)",
                        attempts, TestRedis.description())
                .isEqualTo(limit);
    }
}
