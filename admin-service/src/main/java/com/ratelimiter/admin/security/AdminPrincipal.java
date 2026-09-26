package com.ratelimiter.admin.security;

import java.util.Set;

/**
 * The caller's identity as established from their JWT.
 *
 * <p>{@code tenantId} is the tenant slug, which is what rate limit rules, tenant
 * records and traffic events are keyed by — see the {@code tenant_id} claim.
 */
public record AdminPrincipal(String userId, String tenantId, Set<String> roles) {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** Cross-tenant access. A plain ROLE_ADMIN administers only its own tenant. */
    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";

    public boolean isSuperAdmin() {
        return roles.contains(ROLE_SUPER_ADMIN);
    }

    /** True when this caller may read or modify data belonging to {@code targetTenantId}. */
    public boolean canAccessTenant(String targetTenantId) {
        return isSuperAdmin() || (tenantId != null && tenantId.equals(targetTenantId));
    }
}
