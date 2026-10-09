package com.ratelimiter.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.admin.dto.RateLimitRuleDto;
import com.ratelimiter.admin.dto.TenantDto;
import com.ratelimiter.admin.entity.RateLimitRule;
import com.ratelimiter.admin.entity.Tenant;
import com.ratelimiter.admin.repository.RateLimitRuleRepository;
import com.ratelimiter.admin.repository.TenantRepository;
import com.ratelimiter.admin.security.AdminPrincipal;
import com.ratelimiter.admin.security.TenantAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-tenant access on the id-addressed operations. These cannot be checked in the
 * controller — which tenant a rule or tenant id belongs to is only known after loading
 * it — so the services enforce it, and the check must happen before anything is written.
 */
class TenantScopedServicesTest {

    private static final long ACME_RULE_ID = 1L;
    private static final long ACME_TENANT_ID = 1L;

    private RateLimitRuleRepository rules;
    private TenantRepository tenants;
    private RuleService ruleService;
    private TenantService tenantService;
    private ApiKeyPublisher apiKeyPublisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rules = mock(RateLimitRuleRepository.class);
        tenants = mock(TenantRepository.class);
        TenantAccess access = new TenantAccess();
        ruleService = new RuleService(rules, mock(RedisTemplate.class, RETURNS_DEEP_STUBS),
                new ObjectMapper(), access);
        apiKeyPublisher = mock(ApiKeyPublisher.class);
        tenantService = new TenantService(tenants, access, apiKeyPublisher);

        RateLimitRule acmeRule = RateLimitRule.builder().id(ACME_RULE_ID).tenantId("acme-corp")
                .tier("TIER_A").algorithm("SLIDING_WINDOW").requestLimit(100).windowMs(60_000)
                .burstCapacity(150).active(true).build();
        when(rules.findById(ACME_RULE_ID)).thenReturn(Optional.of(acmeRule));
        when(rules.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rules.findAllByActive(anyBoolean())).thenReturn(List.of());

        Tenant acme = Tenant.builder().id(ACME_TENANT_ID).tenantId("acme-corp").name("Acme")
                .apiKeyHash(com.ratelimiter.admin.security.ApiKeys.hash("api_key_acme_123")).apiKeyPrefix("api_key_acme").tier("TIER_A").active(true).build();
        when(tenants.findById(ACME_TENANT_ID)).thenReturn(Optional.of(acme));
        when(tenants.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String tenant, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal("1", tenant, Set.of(roles)), null, List.of()));
    }

    private static RateLimitRuleDto rule(String tenant) {
        RateLimitRuleDto dto = new RateLimitRuleDto();
        dto.setTenantId(tenant);
        dto.setTier("TIER_A");
        dto.setAlgorithm("SLIDING_WINDOW");
        dto.setRequestLimit(1_000_000);
        dto.setWindowMs(60_000);
        dto.setBurstCapacity(1_000_000);
        return dto;
    }

    @Nested
    @DisplayName("an administrator of beta-inc")
    class BetaAdmin {

        @BeforeEach
        void asBeta() {
            signIn("beta-inc", AdminPrincipal.ROLE_ADMIN);
        }

        @Test
        @DisplayName("listing rules only queries their own tenant")
        void listIsScoped() {
            ruleService.getAllRules();

            verify(rules).findByTenantIdAndActive("beta-inc", true);
            verify(rules, never()).findAll();
        }

        @Test
        @DisplayName("cannot read acme-corp's rules")
        void cannotReadOtherTenantRules() {
            assertThatThrownBy(() -> ruleService.getRulesByTenant("acme-corp"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("cannot create a rule for acme-corp")
        void cannotCreateForOtherTenant() {
            assertThatThrownBy(() -> ruleService.createRule(rule("acme-corp")))
                    .isInstanceOf(AccessDeniedException.class);
            verify(rules, never()).save(any());
        }

        @Test
        @DisplayName("cannot rewrite acme-corp's rule by id — and nothing is saved")
        void cannotUpdateOtherTenantRuleById() {
            assertThatThrownBy(() -> ruleService.updateRule(ACME_RULE_ID, rule("beta-inc")))
                    .isInstanceOf(AccessDeniedException.class);
            verify(rules, never()).save(any());
        }

        @Test
        @DisplayName("cannot deactivate acme-corp's rule by id")
        void cannotDeleteOtherTenantRuleById() {
            assertThatThrownBy(() -> ruleService.deleteRule(ACME_RULE_ID))
                    .isInstanceOf(AccessDeniedException.class);
            verify(rules, never()).save(any());
        }

        @Test
        @DisplayName("cannot republish every tenant's rules")
        void cannotPublishAll() {
            assertThatThrownBy(() -> ruleService.publishAllRulesRequested())
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("cannot read, edit or deactivate acme-corp's tenant record by id")
        void cannotTouchOtherTenantRecord() {
            assertThatThrownBy(() -> tenantService.getTenant(ACME_TENANT_ID))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> tenantService.updateTenant(ACME_TENANT_ID, new TenantDto()))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> tenantService.deleteTenant(ACME_TENANT_ID))
                    .isInstanceOf(AccessDeniedException.class);
            verify(tenants, never()).save(any());
        }

        @Test
        @DisplayName("cannot create tenants")
        void cannotCreateTenants() {
            assertThatThrownBy(() -> tenantService.createTenant(new TenantDto()))
                    .isInstanceOf(AccessDeniedException.class);
            verify(tenants, never()).save(any());
        }
    }

    @Nested
    @DisplayName("a super administrator")
    class SuperAdmin {

        @BeforeEach
        void asSuper() {
            signIn("acme-corp", AdminPrincipal.ROLE_ADMIN, AdminPrincipal.ROLE_SUPER_ADMIN);
        }

        @Test
        @DisplayName("lists every tenant's rules")
        void listsAll() {
            ruleService.getAllRules();
            verify(rules).findAll();
        }

        @Test
        @DisplayName("may update any tenant's rule")
        void mayUpdateAnyRule() {
            RateLimitRuleDto updated = ruleService.updateRule(ACME_RULE_ID, rule("acme-corp"));
            assertThat(updated.getRequestLimit()).isEqualTo(1_000_000);
        }

        @Test
        @DisplayName("creates a tenant with a generated key, stores only its hash, returns it once")
        void createGeneratesApiKey() {
            TenantDto dto = new TenantDto();
            dto.setTenantId("new-co");
            dto.setName("New Co");
            dto.setTier("TIER_FREE");
            dto.setApiKey("chosen-by-caller");

            TenantDto created = tenantService.createTenant(dto);

            org.mockito.ArgumentCaptor<Tenant> saved = org.mockito.ArgumentCaptor.forClass(Tenant.class);
            verify(tenants).save(saved.capture());
            String key = created.getApiKey();
            assertThat(key).startsWith("rk_").hasSizeGreaterThan(40).isNotEqualTo("chosen-by-caller");
            assertThat(saved.getValue().getApiKeyHash())
                    .isEqualTo(com.ratelimiter.admin.security.ApiKeys.hash(key))
                    .doesNotContain(key);
            assertThat(saved.getValue().getApiKeyPrefix()).isEqualTo(key.substring(0, 12));
            verify(apiKeyPublisher).publish();
        }

        @Test
        @DisplayName("rotating a key replaces its hash, returns the new key once and republishes")
        void rotateReplacesKey() {
            String oldHash = tenants.findById(ACME_TENANT_ID).orElseThrow().getApiKeyHash();

            TenantDto rotated = tenantService.rotateApiKey(ACME_TENANT_ID);

            org.mockito.ArgumentCaptor<Tenant> saved = org.mockito.ArgumentCaptor.forClass(Tenant.class);
            verify(tenants).save(saved.capture());
            assertThat(saved.getValue().getApiKeyHash())
                    .isNotEqualTo(oldHash)
                    .isEqualTo(com.ratelimiter.admin.security.ApiKeys.hash(rotated.getApiKey()));
            verify(apiKeyPublisher).publish();
        }

        @Test
        @DisplayName("refuses a tenant ID that already exists")
        void createRejectsDuplicate() {
            when(tenants.findByTenantId("acme-corp")).thenReturn(Optional.of(new Tenant()));
            TenantDto dto = new TenantDto();
            dto.setTenantId("acme-corp");

            assertThatThrownBy(() -> tenantService.createTenant(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
            verify(tenants, never()).save(any());
        }
    }

    @Test
    @DisplayName("tenant responses never contain the raw API key")
    void apiKeyIsMasked() {
        signIn("acme-corp", AdminPrincipal.ROLE_ADMIN);

        TenantDto dto = tenantService.getTenant(ACME_TENANT_ID);

        assertThat(dto.getApiKey()).isEqualTo("api_key_acme****").doesNotContain("_123");
    }
}
