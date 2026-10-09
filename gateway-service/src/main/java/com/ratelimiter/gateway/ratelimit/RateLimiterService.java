package com.ratelimiter.gateway.ratelimit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.gateway.model.RateLimitResult;
import com.ratelimiter.gateway.model.RateLimitRule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Limit applied when no rule matches the caller's tenant/tier. Previously an
     * unmatched tenant was allowed through unlimited, which silently disabled rate
     * limiting for any tenant whose rule was missing or misconfigured.
     */
    @Value("${ratelimit.default.request-limit:60}")
    private int defaultRequestLimit;

    @Value("${ratelimit.default.window-ms:60000}")
    private long defaultWindowMs;

    /**
     * Limit for unauthenticated endpoints, keyed by client IP. Login and registration
     * carry no tenant context, so they were previously skipped entirely — leaving the
     * one endpoint worth brute-forcing as the only one with no rate limit at all.
     */
    @Value("${ratelimit.public.request-limit:20}")
    private int publicRequestLimit;

    @Value("${ratelimit.public.window-ms:60000}")
    private long publicWindowMs;

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

    /**
     * Rate limit an unauthenticated caller by source IP.
     *
     * <p>Note this trusts the socket address. Behind a reverse proxy or load balancer
     * every request appears to come from the proxy, so configure trusted-proxy forwarded
     * header handling before relying on this in production.
     */
    public Mono<RateLimitResult> isAllowedForIp(String ip, String scope) {
        return slidingWindowRateLimiter.checkLimit(
                "public:" + scope + ":" + ip,
                publicRequestLimit,
                publicWindowMs
        );
    }

    public Mono<RateLimitResult> isAllowed(String tenantId, String userId, String tier) {
        return isAllowed(tenantId, userId, tier, 1);
    }

    /** @param cost units of the caller's limit this request uses (see EndpointCosts) */
    public Mono<RateLimitResult> isAllowed(
            String tenantId,
            String userId,
            String tier,
            int cost) {

        RateLimitRule rule =
                ruleCache.get(tenantId + ":" + tier);

        String key = tenantId + ":" + userId;

        if (rule == null || !rule.isActive()) {

            log.debug(
                    "No active rule for tenant={} tier={} — applying default limit of {} per {}ms",
                    tenantId, tier, defaultRequestLimit, defaultWindowMs
            );

            return slidingWindowRateLimiter.checkLimit(
                    key,
                    defaultRequestLimit,
                    defaultWindowMs,
                    cost
            );
        }

        if ("TOKEN_BUCKET".equalsIgnoreCase(rule.getAlgorithm())) {

            // requestLimit is "requests per windowMs"; the bucket script refills per
            // second. Passing the raw limit made a 10-per-minute rule refill at 10 per
            // second — sixty times the configured rate.
            double refillPerSecond = rule.getRequestLimit() / (rule.getWindowMs() / 1000.0);

            // Keep the bucket alive at least long enough to refill from empty, so an
            // idle caller does not come back to a full bucket.
            long refillMs = (long) Math.ceil(rule.getBurstCapacity() / refillPerSecond * 1000.0);
            long ttlMs = Math.max(rule.getWindowMs(), refillMs);

            return tokenBucketRateLimiter.checkLimit(
                    key,
                    rule.getBurstCapacity(),
                    refillPerSecond,
                    ttlMs,
                    cost
            );

        } else {

            return slidingWindowRateLimiter.checkLimit(
                    key,
                    rule.getRequestLimit(),
                    rule.getWindowMs(),
                    cost
            );
        }
    }
}