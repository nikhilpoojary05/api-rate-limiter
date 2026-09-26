package com.ratelimiter.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Baseline response headers for everything the gateway serves. None of these were set
 * before, so a browser had no instruction to refuse MIME sniffing, framing, or to stop
 * leaking the URL in a Referer.
 */
@Configuration
public class SecurityHeadersConfig {

    /**
     * HSTS tells browsers never to use plain http for this host again, so enabling it
     * without working TLS locks clients out. Off by default; turn it on together with
     * TLS termination (and with auth.cookie.secure).
     */
    @Value("${security.hsts.enabled:false}")
    private boolean hstsEnabled;

    @Value("${security.hsts.max-age-seconds:31536000}")
    private long hstsMaxAge;

    /** Ordered first so the headers are attached even to responses produced by other filters. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebFilter securityHeadersFilter() {
        return (exchange, chain) -> {
            exchange.getResponse().beforeCommit(() -> {
                HttpHeaders headers = exchange.getResponse().getHeaders();
                headers.set("X-Content-Type-Options", "nosniff");
                headers.set("X-Frame-Options", "DENY");
                headers.set("Referrer-Policy", "no-referrer");
                headers.set("Cross-Origin-Opener-Policy", "same-origin");
                // This gateway serves JSON, not markup; a restrictive default is safe.
                headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                if (hstsEnabled) {
                    headers.set("Strict-Transport-Security",
                            "max-age=" + hstsMaxAge + "; includeSubDomains");
                }
                return Mono.empty();
            });
            return chain.filter(exchange);
        };
    }
}
