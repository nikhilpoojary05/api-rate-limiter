package com.ratelimiter.gateway.ratelimit;

import com.ratelimiter.gateway.model.RateLimitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    public TokenBucketRateLimiter(
            @Qualifier("reactiveRedisTemplate")
            ReactiveRedisTemplate<String, String> redisTemplate,

            @Qualifier("tokenBucketScript")
            RedisScript<List> tokenBucketScript) {

        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    public Mono<RateLimitResult> checkLimit(
            String key,
            int capacity,
            int refillRate,
            long windowMs) {

        String redisKey = "rl:tb:" + key;
        long now = System.currentTimeMillis();

        return redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(redisKey),
                List.of(
                        String.valueOf(now),
                        String.valueOf(capacity),
                        String.valueOf(refillRate),
                        String.valueOf(windowMs)
                )
        )
        .next()
        .map(result -> {

            boolean allowed = ((Long) result.get(0)) == 1L;
            int remaining = ((Long) result.get(1)).intValue();

            return new RateLimitResult(
                    allowed,
                    remaining,
                    now + windowMs
            );
        })
        .onErrorResume(e -> {

            log.error(
                    "Redis script error in TokenBucketRateLimiter — failing {}",
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