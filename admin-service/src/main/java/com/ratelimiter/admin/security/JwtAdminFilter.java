package com.ratelimiter.admin.security;

import com.ratelimiter.common.security.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates the JWT access token on incoming admin-service requests and publishes an
 * {@link AdminPrincipal} carrying the caller's tenant, so downstream code can scope
 * every query to it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAdminFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    /**
     * The browser EventSource API cannot set an Authorization header, so the SSE stream
     * accepts the token as a query parameter. This is narrower than it looks — it applies
     * to this one path only — but the token does land in access logs, so prefer a
     * short-lived one-time SSE ticket if this ever runs behind a logging proxy.
     */
    private static final String SSE_PATH = "/admin/analytics/live";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (StringUtils.hasText(token)) {
            try {
                // Single parse; see JwtUtils.verify. An invalid token throws and is
                // handled below, leaving the request unauthenticated.
                JwtUtils.VerifiedToken verified = jwtUtils.verify(token);
                Set<String> roles = new LinkedHashSet<>(verified.roles());

                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());

                AdminPrincipal principal = new AdminPrincipal(verified.userId(), verified.tenantId(), roles);

                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, authorities));

                log.debug("Admin JWT validated for userId={} tenant={}", verified.userId(), verified.tenantId());
            } catch (Exception e) {
                log.warn("Admin JWT validation failed: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() > 7) {
            return header.substring(7);
        }
        if (SSE_PATH.equals(request.getRequestURI())) {
            return request.getParameter("token");
        }
        return null;
    }
}
