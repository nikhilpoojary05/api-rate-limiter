package com.ratelimiter.gateway.ratelimit;

import com.ratelimiter.gateway.model.RateLimitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
                    "Redis script error in TokenBucketRateLimiter",
                    e
            );

            // Fail-open
            return Mono.just(
                    new RateLimitResult(true, 1, 0)
            );
        });
    }
}