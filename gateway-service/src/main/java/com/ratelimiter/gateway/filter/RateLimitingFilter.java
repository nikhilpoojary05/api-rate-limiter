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
import java.util.Objects;

@Component
@Slf4j
@RequiredArgsConstructor
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private final RateLimiterService rateLimiterService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String tenantId = request.getHeaders().getFirst("X-Tenant-Id");

        // Skip rate limiting for requests without tenant context (public paths handled by JWT filter)
        if (tenantId == null || tenantId.isBlank()) {
            return chain.filter(exchange);
        }

        String userId  = request.getHeaders().getFirst("X-User-Id");
        String tier    = request.getHeaders().getFirst("X-User-Tier");
        String ip      = Objects.requireNonNull(request.getRemoteAddress()).getAddress().getHostAddress();
        long startTime = System.currentTimeMillis();

        return rateLimiterService.isAllowed(tenantId, userId, tier)
                .flatMap(result -> {
                    long resetMs   = result.resetMs();
                    long retryAfter = Math.max(1, (resetMs - System.currentTimeMillis()) / 1000);

                    if (!result.allowed()) {
                        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", "0");
                        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
                        exchange.getResponse().getHeaders().add("X-RateLimit-Reset", String.valueOf(resetMs));
                        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(retryAfter));
                        publishEvent(exchange, tenantId, userId, ip, startTime, "BLOCKED");
                        return Mono.error(new RateLimitExceededException((int) retryAfter));
                    }

                    exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", String.valueOf(result.remaining()));
                    exchange.getResponse().getHeaders().add("X-RateLimit-Reset", String.valueOf(resetMs));

                    return chain.filter(exchange)
                            .doOnSuccess(v -> publishEvent(exchange, tenantId, userId, ip, startTime, "ALLOWED"))
                            .doOnError(e -> publishEvent(exchange, tenantId, userId, ip, startTime, "ERROR"));
                });
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
