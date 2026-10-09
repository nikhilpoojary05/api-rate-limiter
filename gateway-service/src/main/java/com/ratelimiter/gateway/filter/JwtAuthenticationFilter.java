package com.ratelimiter.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.ratelimiter.common.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtUtils jwtUtils;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Server-to-server clients can authenticate with a tenant API key instead of a JWT.
     * The admin service publishes the SHA-256 of each active key to this Redis hash; the
     * key itself is stored nowhere. A key acts as its tenant with ROLE_API_CLIENT, so it
     * reaches the API but never the admin endpoints, which require ROLE_ADMIN.
     */
    static final String API_KEY_HEADER = "X-API-Key";
    static final String API_KEYS = "apikeys";
    static final String API_CLIENT_ROLE = "ROLE_API_CLIENT";

    // Paths that bypass JWT auth. Keep this as narrow as possible — "/actuator/"
    // as a whole would publish /actuator/prometheus and any other exposed endpoint.
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/",
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs",
            "/swagger-ui"
    );

    /**
     * The browser EventSource API cannot set an Authorization header, so the live
     * analytics stream accepts its token as a query parameter. Restricted to this one
     * path — the token does reach access logs, so prefer a short-lived single-use SSE
     * ticket if this runs behind a logging proxy.
     */
    private static final String SSE_PATH = "/api/admin/analytics/live";

    // Identity headers the gateway alone is allowed to set. Anything a client sends
    // under these names is discarded so downstream services can trust them.
    private static final List<String> IDENTITY_HEADERS = List.of(
            "X-User-Id", "X-Tenant-Id", "X-Tenant-Uid", "X-User-Tier", "X-User-Roles"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip auth for public paths
        boolean isPublic = PUBLIC_PATHS.stream().anyMatch(path::startsWith);
        if (isPublic) {
            // Still strip identity headers: an unauthenticated caller must not be able to
            // inject X-Tenant-Id or X-User-Roles by aiming at a public route.
            ServerHttpRequest stripped = exchange.getRequest().mutate()
                    .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                    .build();
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        String token = resolveToken(exchange, path);
        if (token == null || token.isBlank()) {
            String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
            if (apiKey != null && !apiKey.isBlank() && !SSE_PATH.equals(path)) {
                return authenticateApiKey(exchange, chain, apiKey, path);
            }
            log.debug("Missing or invalid credentials for path: {}", path);
            return unauthorizedResponse(exchange.getResponse(), "Missing or invalid Authorization header");
        }

        // One parse and one signature check for the whole request. This used to call
        // validateToken and then five extract* methods, each re-parsing the token — six
        // full verifications per request, about a fifth of the gateway's CPU under load.
        JwtUtils.VerifiedToken verified;
        try {
            verified = jwtUtils.verify(token);
        } catch (Exception e) {
            log.warn("JWT authentication failed for path {}: {}", path, e.getMessage());
            return unauthorizedResponse(exchange.getResponse(), "Invalid or expired token");
        }

        // Forward user context to downstream services as headers
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(API_KEY_HEADER))
                .header("X-User-Id",    verified.userId())
                .header("X-Tenant-Id",  verified.tenantId())   // canonical slug, e.g. "acme-corp"
                .header("X-Tenant-Uid", verified.tenantUid() != null ? verified.tenantUid() : "")
                .header("X-User-Tier",  verified.tier() != null ? verified.tier() : "")
                .header("X-User-Roles", String.join(",", verified.roles()))
                .build();

        log.debug("JWT validated — user={} tenant={} tier={}",
                verified.userId(), verified.tenantId(), verified.tier());
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /** What a published API key acts as; NOT_FOUND and UNAVAILABLE mark the failures. */
    private record ApiKeyIdentity(String tenantId, String tier) {
        static final ApiKeyIdentity NOT_FOUND = new ApiKeyIdentity(null, null);
        static final ApiKeyIdentity UNAVAILABLE = new ApiKeyIdentity(null, null);
    }

    private Mono<Void> authenticateApiKey(ServerWebExchange exchange, GatewayFilterChain chain,
                                          String apiKey, String path) {
        String hash = sha256Hex(apiKey);
        Mono<ApiKeyIdentity> lookup = redisTemplate.<String, String>opsForHash().get(API_KEYS, hash)
                .map(this::parseIdentity)
                .defaultIfEmpty(ApiKeyIdentity.NOT_FOUND)
                .onErrorResume(e -> {
                    log.error("API key lookup failed for path {}", path, e);
                    return Mono.just(ApiKeyIdentity.UNAVAILABLE);
                });

        return lookup.flatMap(identity -> {
            if (identity == ApiKeyIdentity.UNAVAILABLE) {
                return errorResponse(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE,
                        "API key could not be checked, try again shortly", "UNAVAILABLE");
            }
            if (identity == ApiKeyIdentity.NOT_FOUND || identity.tenantId() == null) {
                log.warn("Unknown API key presented for path {}", path);
                return unauthorizedResponse(exchange.getResponse(), "Invalid API key");
            }
            // The key is not forwarded: upstream services get the identity, never the secret.
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove(API_KEY_HEADER);
                        IDENTITY_HEADERS.forEach(headers::remove);
                    })
                    .header("X-User-Id", "apikey:" + hash.substring(0, 12))
                    .header("X-Tenant-Id", identity.tenantId())
                    .header("X-Tenant-Uid", "")
                    .header("X-User-Tier", identity.tier() != null ? identity.tier() : "")
                    .header("X-User-Roles", API_CLIENT_ROLE)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        });
    }

    private ApiKeyIdentity parseIdentity(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new ApiKeyIdentity(node.path("tenantId").asText(null), node.path("tier").asText(null));
        } catch (Exception e) {
            log.error("Unreadable API key entry in Redis", e);
            return ApiKeyIdentity.NOT_FOUND;
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private String resolveToken(ServerWebExchange exchange, String path) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ") && authHeader.length() > 7) {
            return authHeader.substring(7);
        }
        if (SSE_PATH.equals(path)) {
            return exchange.getRequest().getQueryParams().getFirst("token");
        }
        return null;
    }

    private Mono<Void> unauthorizedResponse(ServerHttpResponse response, String message) {
        return errorResponse(response, HttpStatus.UNAUTHORIZED, message, "UNAUTHORIZED");
    }

    private Mono<Void> errorResponse(ServerHttpResponse response, HttpStatus status, String message, String code) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"success\":false,\"message\":\"%s\",\"errorCode\":\"%s\"}", message, code);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -100; // Runs before RateLimitingFilter (-90)
    }
}
