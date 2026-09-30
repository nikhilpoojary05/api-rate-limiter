package com.ratelimiter.admin.web;

import com.ratelimiter.admin.config.SecurityConfig;
import com.ratelimiter.admin.controller.AnalyticsController;
import com.ratelimiter.admin.security.TenantAccess;
import com.ratelimiter.admin.service.TrafficEventService;
import com.ratelimiter.admin.sse.SseEmitterRegistry;
import com.ratelimiter.common.security.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real admin security filter chain, driven with real signed tokens: which callers
 * reach which endpoints. The analytics controller is used because its tenant checks sit
 * in the controller itself, so the whole path — JWT filter, role gate, tenant scoping,
 * exception handler — is exercised end to end.
 */
@WebMvcTest(controllers = AnalyticsController.class,
        properties = "jwt.secret=admin-web-test-secret-comfortably-over-32-bytes")
@Import({SecurityConfig.class, JwtUtils.class, TenantAccess.class})
class AdminSecurityMatrixTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtUtils jwtUtils;

    @MockitoBean
    private TrafficEventService trafficEventService;

    @MockitoBean
    private SseEmitterRegistry sseEmitterRegistry;

    private static final String RANGE = "&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z";

    private String bearer(String tenant, String... roles) {
        return "Bearer " + jwtUtils.generateAccessToken("1", "u", tenant, "uid", "TIER_A", List.of(roles));
    }

    private String rawToken(String tenant, String... roles) {
        return jwtUtils.generateAccessToken("1", "u", tenant, "uid", "TIER_A", List.of(roles));
    }

    // ---- authentication and role gate ----------------------------------------------

    @Test
    @DisplayName("anonymous callers are refused")
    void anonymousRefused() throws Exception {
        mvc.perform(get("/admin/analytics/summary")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an ordinary user is refused — admin endpoints need ROLE_ADMIN")
    void plainUserRefused() throws Exception {
        mvc.perform(get("/admin/analytics/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer("acme-corp", "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a garbage token authenticates nobody")
    void garbageTokenRefused() throws Exception {
        mvc.perform(get("/admin/analytics/summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isForbidden());
    }

    // ---- tenant scoping -------------------------------------------------------------

    @Test
    @DisplayName("a tenant admin can read their own tenant's traffic")
    void ownTenantTraffic() throws Exception {
        mvc.perform(get("/admin/analytics/traffic?tenantId=beta-inc" + RANGE)
                        .header(HttpHeaders.AUTHORIZATION, bearer("beta-inc", "ROLE_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a tenant admin cannot read another tenant's traffic")
    void otherTenantTraffic() throws Exception {
        mvc.perform(get("/admin/analytics/traffic?tenantId=acme-corp" + RANGE)
                        .header(HttpHeaders.AUTHORIZATION, bearer("beta-inc", "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a tenant admin cannot pull another tenant's recent events")
    void otherTenantEvents() throws Exception {
        mvc.perform(get("/admin/analytics/events?tenantId=acme-corp")
                        .header(HttpHeaders.AUTHORIZATION, bearer("beta-inc", "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a super admin can read any tenant's traffic")
    void superAdminCrossTenant() throws Exception {
        mvc.perform(get("/admin/analytics/traffic?tenantId=acme-corp" + RANGE)
                        .header(HttpHeaders.AUTHORIZATION, bearer("beta-inc", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("manual event ingestion is super-admin only")
    void ingestionIsSuperAdminOnly() throws Exception {
        String body = "{\"tenantId\":\"acme-corp\",\"status\":\"ALLOWED\"}";

        mvc.perform(post("/admin/analytics/events").contentType("application/json").content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("acme-corp", "ROLE_ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/admin/analytics/events").contentType("application/json").content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("acme-corp", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")))
                .andExpect(status().isOk());
    }

    // ---- the live stream ------------------------------------------------------------

    @Test
    @DisplayName("the live traffic stream is not public")
    void liveStreamNeedsAuth() throws Exception {
        mvc.perform(get("/admin/analytics/live")).andExpect(status().isForbidden());
    }

    /**
     * Distinct from the anonymous case: an anonymous caller is also refused further in, by
     * the controller requiring a principal, so that test alone would not notice the role
     * gate being removed. A signed-in non-admin has a principal, so only the role gate
     * stands between them and every event in their tenant.
     */
    @Test
    @DisplayName("an ordinary user cannot open the live stream")
    void liveStreamNeedsAdminRole() throws Exception {
        mvc.perform(get("/admin/analytics/live").param("token", rawToken("acme-corp", "ROLE_USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the live stream accepts a query-string token, since EventSource cannot send headers")
    void liveStreamQueryToken() throws Exception {
        mvc.perform(get("/admin/analytics/live").param("token", rawToken("acme-corp", "ROLE_ADMIN")))
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("a query-string token is ignored on every other endpoint")
    void queryTokenIgnoredElsewhere() throws Exception {
        mvc.perform(get("/admin/analytics/summary").param("token", rawToken("acme-corp", "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
    }
}
