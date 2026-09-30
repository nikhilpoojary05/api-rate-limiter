package com.ratelimiter.admin.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The single place that decides whether a caller may touch a tenant. */
class TenantAccessTest {

    private final TenantAccess access = new TenantAccess();

    static void signIn(String tenant, String... roles) {
        AdminPrincipal principal = new AdminPrincipal("1", tenant, Set.of(roles));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("with no authenticated principal, everything is denied")
    void anonymousIsDenied() {
        assertThatThrownBy(access::current).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.requireAccessTo("acme-corp")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.resolveScope(null)).isInstanceOf(AccessDeniedException.class);
    }

    @Nested
    @DisplayName("a tenant administrator")
    class TenantAdmin {

        @Test
        @DisplayName("may access their own tenant")
        void ownTenant() {
            signIn("beta-inc", AdminPrincipal.ROLE_ADMIN);
            assertThatNoException().isThrownBy(() -> access.requireAccessTo("beta-inc"));
        }

        @Test
        @DisplayName("may not access another tenant")
        void otherTenant() {
            signIn("beta-inc", AdminPrincipal.ROLE_ADMIN);
            assertThatThrownBy(() -> access.requireAccessTo("acme-corp"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("asking for 'all tenants' is quietly narrowed to their own")
        void unscopedRequestIsPinned() {
            signIn("beta-inc", AdminPrincipal.ROLE_ADMIN);
            assertThat(access.resolveScope(null)).isEqualTo("beta-inc");
        }

        @Test
        @DisplayName("explicitly asking for another tenant is refused, not silently redirected")
        void explicitOtherTenantIsRefused() {
            signIn("beta-inc", AdminPrincipal.ROLE_ADMIN);
            assertThatThrownBy(() -> access.resolveScope("acme-corp"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("does not have cross-tenant rights")
        void notSuperAdmin() {
            signIn("beta-inc", AdminPrincipal.ROLE_ADMIN);
            assertThatThrownBy(access::requireSuperAdmin).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("a super administrator")
    class SuperAdmin {

        @Test
        @DisplayName("may access any tenant")
        void anyTenant() {
            signIn("acme-corp", AdminPrincipal.ROLE_ADMIN, AdminPrincipal.ROLE_SUPER_ADMIN);
            assertThatNoException().isThrownBy(() -> access.requireAccessTo("beta-inc"));
            assertThatNoException().isThrownBy(access::requireSuperAdmin);
        }

        @Test
        @DisplayName("an unscoped request means all tenants")
        void unscopedMeansAll() {
            signIn("acme-corp", AdminPrincipal.ROLE_SUPER_ADMIN);
            assertThat(access.resolveScope(null)).isNull();
            assertThat(access.resolveScope("beta-inc")).isEqualTo("beta-inc");
        }
    }

    @Test
    @DisplayName("a principal with no tenant cannot match any tenant")
    void nullTenantMatchesNothing() {
        signIn(null, AdminPrincipal.ROLE_ADMIN);
        assertThatThrownBy(() -> access.requireAccessTo("acme-corp")).isInstanceOf(AccessDeniedException.class);
    }
}
