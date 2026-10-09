package com.ratelimiter.gateway.ratelimit;

import com.ratelimiter.gateway.model.RateLimitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class SlidingWindowRateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> slidingWindowScript;

    /**
     * When Redis is unreachable we cannot know whether the caller is over their limit.
     * Denying is the safe answer for a rate limiter: failing open let anyone who could
     * degrade Redis switch rate limiting off entirely. Flip this only if availability
     * matters more than enforcement for your deployment.
     */
    @Value("${ratelimit.fail-open:false}")
    private boolean failOpen;

    /**
     * Null in production: the scripts then read Redis's clock, so every gateway instance
     * measures time the same way. Tests pass a clock to control time.
     */
    private final Clock clock;

    @Autowired
    public SlidingWindowRateLimiter(
            @Qualifier("reactiveRedisTemplate")
            ReactiveRedisTemplate<String, String> redisTemplate,

            @Qualifier("slidingWindowScript")
            RedisScript<List> slidingWindowScript) {

        this(redisTemplate, slidingWindowScript, null);
    }

    /** Tests pin the clock so concurrent requests genuinely share a millisecond. */
    SlidingWindowRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate,
                             RedisScript<List> slidingWindowScript,
                             Clock clock) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
        this.clock = clock;
    }

    public Mono<RateLimitResult> checkLimit(String key, int limit, long windowMs) {
        return checkLimit(key, limit, windowMs, 1);
    }

    /** @param cost units of the limit this request uses; admitted only if all of it fits */
    public Mono<RateLimitResult> checkLimit(
            String key,
            int limit,
            long windowMs,
            int cost) {

        String redisKey = "rl:sw:" + key;
        long requestedNow = clock != null ? clock.millis() : 0;  // 0 = Redis TIME

        return redisTemplate.execute(
                slidingWindowScript,
                Collections.singletonList(redisKey),
                List.of(
                        String.valueOf(requestedNow),
                        String.valueOf(windowMs),
                        String.valueOf(limit),
                        UUID.randomUUID().toString(),
                        String.valueOf(cost)
                )
        )
        .next()
        .map(result -> {

            boolean allowed = ((Long) result.get(0)) == 1L;
            int remaining = ((Long) result.get(1)).intValue();
            long now = (Long) result.get(2);  // the time the script actually used

            return new RateLimitResult(
                    allowed,
                    remaining,
                    now + windowMs
            );
        })
        .onErrorResume(e -> {

            long now = clock != null ? clock.millis() : System.currentTimeMillis();

            log.error(
                    "Redis script error in SlidingWindowRateLimiter — failing {}",
                    failOpen ? "open" : "closed",
                    e
            );

            return Mono.just(
                    failOpen
                            ? new RateLimitResult(true, 1, 0)
                            : new RateLimitResult(false, 0, now + windowMs)
            );
        });
    }
}