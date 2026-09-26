package com.ratelimiter.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines all upstream routes for the API Gateway.
 * Routes are configured via application.yml URIs so they can be overridden
 * per environment (local: localhost:808x, Docker: service-name:808x).
 */
@Configuration
public class GatewayConfig {

    @Value("${gateway.routes.auth-service-uri:http://localhost:8081}")
    private String authServiceUri;

    @Value("${gateway.routes.admin-service-uri:http://localhost:8082}")
    private String adminServiceUri;

    @Value("${gateway.routes.demo-service-uri:http://localhost:8083}")
    private String demoServiceUri;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // Auth service — strip /api prefix before forwarding
                .route("auth-service", r -> r.path("/api/auth/**")
                        .filters(f -> f.stripPrefix(1))   // /api/auth/login → /auth/login
                        .uri(authServiceUri))

                // Admin service — strip /api prefix
                .route("admin-service", r -> r.path("/api/admin/**")
                        .filters(f -> f.stripPrefix(1))   // /api/admin/rules → /admin/rules
                        .uri(adminServiceUri))

                // Demo service — strip /api prefix
                .route("demo-service", r -> r.path("/api/demo/**")
                        .filters(f -> f.stripPrefix(1))   // /api/demo/ping → /demo/ping
                        .uri(demoServiceUri))

                .build();
    }
}
