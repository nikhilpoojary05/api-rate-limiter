package com.ratelimiter.admin.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Single place where "may this caller touch this tenant?" is answered.
 *
 * <p>Without this every admin endpoint was effectively cross-tenant: the only check
 * was that the caller held ROLE_ADMIN, so any tenant's administrator could read and
 * rewrite every other tenant's rules and traffic history.
 */
@Component
public class TenantAccess {

    public AdminPrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal principal)) {
            throw new AccessDeniedException("No authenticated admin principal");
        }
        return principal;
    }

    /** The caller's own tenant. */
    public String currentTenantId() {
        return current().tenantId();
    }

    public boolean isSuperAdmin() {
        return current().isSuperAdmin();
    }

    /** Throws unless the caller may act on {@code tenantId}. */
    public void requireAccessTo(String tenantId) {
        if (!current().canAccessTenant(tenantId)) {
            throw new AccessDeniedException("Not permitted to access tenant " + tenantId);
        }
    }

    /** Throws unless the caller has cross-tenant rights. */
    public void requireSuperAdmin() {
        if (!isSuperAdmin()) {
            throw new AccessDeniedException("This operation requires ROLE_SUPER_ADMIN");
        }
    }

    /**
     * Resolves the tenant an endpoint should operate on. A super-admin may pass any
     * tenant (or none, meaning "all"); everyone else is pinned to their own tenant
     * regardless of what they asked for.
     */
    public String resolveScope(String requestedTenantId) {
        AdminPrincipal principal = current();
        if (principal.isSuperAdmin()) {
            return requestedTenantId;
        }
        if (requestedTenantId != null && !requestedTenantId.equals(principal.tenantId())) {
            throw new AccessDeniedException("Not permitted to access tenant " + requestedTenantId);
        }
        return principal.tenantId();
    }
}
