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
public class SlidingWindowRateLimiter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> slidingWindowScript;

    public SlidingWindowRateLimiter(
            @Qualifier("reactiveRedisTemplate")
            ReactiveRedisTemplate<String, String> redisTemplate,

            @Qualifier("slidingWindowScript")
            RedisScript<List> slidingWindowScript) {

        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
    }

    public Mono<RateLimitResult> checkLimit(
            String key,
            int limit,
            long windowMs) {

        String redisKey = "rl:sw:" + key;
        long now = System.currentTimeMillis();

        return redisTemplate.execute(
                slidingWindowScript,
                Collections.singletonList(redisKey),
                List.of(
                        String.valueOf(now),
                        String.valueOf(windowMs),
                        String.valueOf(limit)
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
                    "Redis script error in SlidingWindowRateLimiter",
                    e
            );

            // Fail-open
            return Mono.just(
                    new RateLimitResult(true, 1, 0)
            );
        });
    }
}