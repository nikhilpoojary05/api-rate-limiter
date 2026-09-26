package com.ratelimiter.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.gateway.exception.RateLimitExceededException;
import com.ratelimiter.gateway.ratelimit.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private final RateLimiterService rateLimiterService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Unauthenticated paths that still need a limit. These carry no tenant context, so
     * they fall outside the per-tenant rules and are limited by source IP instead —
     * otherwise /api/auth/login is an unlimited credential-stuffing target.
     */
    private static final List<String> IP_LIMITED_PREFIXES = List.of("/api/auth/");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String tenantId = request.getHeaders().getFirst("X-Tenant-Id");

        String path    = request.getURI().getPath();
        String ip      = clientIp(request);
        long startTime = System.currentTimeMillis();

        if (tenantId == null || tenantId.isBlank()) {
            boolean ipLimited = IP_LIMITED_PREFIXES.stream().anyMatch(path::startsWith);
            if (!ipLimited) {
                return chain.filter(exchange);
            }
            return rateLimiterService.isAllowedForIp(ip, "auth")
                    .flatMap(result -> {
                        if (!result.allowed()) {
                            long retryAfter = retryAfterSeconds(result.resetMs());
                            addLimitHeaders(exchange, result.resetMs(), retryAfter, true);
                            log.warn("Rate limited unauthenticated request from {} to {}", ip, path);
                            return Mono.error(new RateLimitExceededException((int) retryAfter));
                        }
                        addLimitHeaders(exchange, result.resetMs(), 0, false);
                        exchange.getResponse().getHeaders()
                                .set("X-RateLimit-Remaining", String.valueOf(result.remaining()));
                        return chain.filter(exchange);
                    });
        }

        String userId  = request.getHeaders().getFirst("X-User-Id");
        String tier    = request.getHeaders().getFirst("X-User-Tier");

        return rateLimiterService.isAllowed(tenantId, userId, tier)
                .flatMap(result -> {
                    long resetMs    = result.resetMs();
                    long retryAfter = retryAfterSeconds(resetMs);

                    if (!result.allowed()) {
                        addLimitHeaders(exchange, resetMs, retryAfter, true);
                        publishEvent(exchange, tenantId, userId, ip, startTime, "BLOCKED");
                        return Mono.error(new RateLimitExceededException((int) retryAfter));
                    }

                    addLimitHeaders(exchange, resetMs, 0, false);
                    exchange.getResponse().getHeaders()
                            .set("X-RateLimit-Remaining", String.valueOf(result.remaining()));

                    return chain.filter(exchange)
                            .doOnSuccess(v -> publishEvent(exchange, tenantId, userId, ip, startTime, "ALLOWED"))
                            .doOnError(e -> publishEvent(exchange, tenantId, userId, ip, startTime, "ERROR"));
                });
    }

    private static long retryAfterSeconds(long resetMs) {
        return Math.max(1, (resetMs - System.currentTimeMillis()) / 1000);
    }

    /** Uses set() rather than add() so a retried request cannot accumulate duplicate headers. */
    private static void addLimitHeaders(ServerWebExchange exchange, long resetMs,
                                        long retryAfter, boolean blocked) {
        var headers = exchange.getResponse().getHeaders();
        headers.set("X-RateLimit-Reset", String.valueOf(resetMs));
        if (blocked) {
            headers.set("X-RateLimit-Remaining", "0");
            headers.set("Retry-After", String.valueOf(retryAfter));
        }
    }

    private static String clientIp(ServerHttpRequest request) {
        var remote = request.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : "unknown";
    }

    /**
     * Serializes a traffic event to JSON and pushes it to the Redis list "traffic:events".
     * The admin-service RedisTrafficConsumer reads from this list via LPOP every second.
     */
    private void publishEvent(ServerWebExchange exchange, String tenantId, String userId,
                               String ip, long startTime, String status) {
        long latencyMs = System.currentTimeMillis() - startTime;
        String path    = exchange.getRequest().getURI().getPath();
        String method  = exchange.getRequest().getMethod().name();
        int httpStatus = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value()
                : ("BLOCKED".equals(status) ? 429 : 200);

        Map<String, Object> event = new HashMap<>();
        event.put("tenantId",   tenantId);
        event.put("userId",     userId != null ? userId : "anonymous");
        event.put("ipAddress",  ip);
        event.put("path",       path);
        event.put("method",     method);
        event.put("status",     status);
        event.put("latencyMs",  latencyMs);
        event.put("httpStatus", httpStatus);
        event.put("timestamp",  Instant.now().toString());

        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList()
                    .rightPush("traffic:events", json)
                    .subscribe(
                            count -> log.debug("Traffic event queued (queue depth: {})", count),
                            err   -> log.error("Failed to queue traffic event", err)
                    );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize traffic event", e);
        }
    }

    @Override
    public int getOrder() {
        return -90;  // Runs after JwtAuthenticationFilter (-100)
    }
}
