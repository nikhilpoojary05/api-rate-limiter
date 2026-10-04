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

@Service
@Slf4j
public class TokenBucketRateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> tokenBucketScript;

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
    public TokenBucketRateLimiter(
            @Qualifier("reactiveRedisTemplate")
            ReactiveRedisTemplate<String, String> redisTemplate,

            @Qualifier("tokenBucketScript")
            RedisScript<List> tokenBucketScript) {

        this(redisTemplate, tokenBucketScript, null);
    }

    /** Tests drive the clock to check refill arithmetic without sleeping. */
    TokenBucketRateLimiter(ReactiveRedisTemplate<String, String> redisTemplate,
                           RedisScript<List> tokenBucketScript,
                           Clock clock) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.clock = clock;
    }

    /**
     * @param refillPerSecond tokens added per second. The script works in seconds, so
     *                        a per-window limit must be converted before it gets here.
     * @param ttlMs           how long an idle bucket is kept.
     */
    public Mono<RateLimitResult> checkLimit(
            String key,
            int capacity,
            double refillPerSecond,
            long ttlMs) {

        String redisKey = "rl:tb:" + key;
        long requestedNow = clock != null ? clock.millis() : 0;  // 0 = Redis TIME

        // For a bucket, "reset" is when the next token becomes available — not the end
        // of a window. This drives Retry-After, so an over-long value stalls clients.
        long nextTokenMs = (long) Math.ceil(1000.0 / Math.max(refillPerSecond, 1e-9));

        return redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(redisKey),
                List.of(
                        String.valueOf(requestedNow),
                        String.valueOf(capacity),
                        // Locale.ROOT: a comma decimal separator would not parse in Lua
                        String.format(java.util.Locale.ROOT, "%.6f", refillPerSecond),
                        String.valueOf(ttlMs)
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
                    now + nextTokenMs
            );
        })
        .onErrorResume(e -> {

            long now = clock != null ? clock.millis() : System.currentTimeMillis();

            log.error(
                    "Redis script error in TokenBucketRateLimiter — failing {}",
                    failOpen ? "open" : "closed",
                    e
            );

            return Mono.just(
                    failOpen
                            ? new RateLimitResult(true, 1, 0)
                            : new RateLimitResult(false, 0, now + nextTokenMs)
            );
        });
    }
}