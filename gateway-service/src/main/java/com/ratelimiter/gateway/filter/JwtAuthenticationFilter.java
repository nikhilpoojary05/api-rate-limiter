package com.ratelimiter.gateway.filter;

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
            log.debug("Missing or invalid credentials for path: {}", path);
            return unauthorizedResponse(exchange.getResponse(), "Missing or invalid Authorization header");
        }

        try {
            if (!jwtUtils.validateToken(token)) {
                return unauthorizedResponse(exchange.getResponse(), "Invalid or expired token");
            }

            // Extract claims using actual JwtUtils method names
            String userId    = jwtUtils.extractUserId(token);
            String tenantId  = jwtUtils.extractTenantId(token);   // canonical slug, e.g. "acme-corp"
            String tenantUid = jwtUtils.extractTenantUid(token);
            String tier      = jwtUtils.extractTier(token);
            List<String> roles = jwtUtils.extractRoles(token);
            String rolesHeader = String.join(",", roles);

            // Forward user context to downstream services as headers
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id",    userId)
                    .header("X-Tenant-Id",  tenantId)
                    .header("X-Tenant-Uid", tenantUid != null ? tenantUid : "")
                    .header("X-User-Tier",  tier != null ? tier : "")
                    .header("X-User-Roles", rolesHeader)
                    .build();

            log.debug("JWT validated — user={} tenant={} tier={}", userId, tenantId, tier);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception e) {
            log.warn("JWT authentication failed for path {}: {}", path, e.getMessage());
            return unauthorizedResponse(exchange.getResponse(), "Token validation error");
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
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"success\":false,\"message\":\"%s\",\"errorCode\":\"UNAUTHORIZED\"}", message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        return -100; // Runs before RateLimitingFilter (-90)
    }
}
