package com.ratelimiter.gateway.ratelimit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.gateway.model.RateLimitResult;
import com.ratelimiter.gateway.model.RateLimitRule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {

    private final SlidingWindowRateLimiter slidingWindowRateLimiter;
    private final TokenBucketRateLimiter tokenBucketRateLimiter;

    @Qualifier("reactiveRedisTemplate")
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper;

    private final Map<String, RateLimitRule> ruleCache = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {

        log.info("Gateway application is ready. Starting rate limit rule refresh...");

        refreshRules()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> {},
                        error -> log.error("Initial rate rule refresh failed", error)
                );

        Flux.interval(Duration.ofSeconds(30))
                .flatMap(i ->
                        refreshRules()
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorResume(e -> {
                                    log.error("Periodic rate rule refresh failed", e);
                                    return Mono.empty();
                                })
                )
                .subscribe();
    }

    private Mono<Void> refreshRules() {

        return redisTemplate.opsForValue()
                .get("rules:all")
                .map(json -> {

                    try {

                        List<RateLimitRule> rules =
                                objectMapper.readValue(
                                        json,
                                        new TypeReference<List<RateLimitRule>>() {}
                                );

                        ruleCache.clear();

                        rules.forEach(rule ->
                                ruleCache.put(
                                        rule.getTenantId() + ":" + rule.getTier(),
                                        rule
                                )
                        );

                        log.info(
                                "Refreshed {} rate limit rules",
                                rules.size()
                        );

                    } catch (Exception e) {

                        log.error(
                                "Failed to parse rate limit rules",
                                e
                        );
                    }

                    return json;
                })
                .then();
    }

    public Mono<RateLimitResult> isAllowed(
            String tenantId,
            String userId,
            String tier) {

        RateLimitRule rule =
                ruleCache.get(tenantId + ":" + tier);

        if (rule == null || !rule.isActive()) {

            return Mono.just(
                    new RateLimitResult(true, 100, 0)
            );
        }

        String key = tenantId + ":" + userId;

        if ("TOKEN_BUCKET".equalsIgnoreCase(rule.getAlgorithm())) {

            return tokenBucketRateLimiter.checkLimit(
                    key,
                    rule.getBurstCapacity(),
                    rule.getRequestLimit(),
                    rule.getWindowMs()
            );

        } else {

            return slidingWindowRateLimiter.checkLimit(
                    key,
                    rule.getRequestLimit(),
                    rule.getWindowMs()
            );
        }
    }
}