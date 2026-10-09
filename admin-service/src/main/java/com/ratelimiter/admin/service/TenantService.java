package com.ratelimiter.admin.service;

import com.ratelimiter.admin.dto.TenantDto;
import com.ratelimiter.admin.entity.Tenant;
import com.ratelimiter.admin.repository.TenantRepository;
import com.ratelimiter.admin.security.ApiKeys;
import com.ratelimiter.admin.security.TenantAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantService {
    private final TenantRepository repository;
    private final TenantAccess tenantAccess;
    private final ApiKeyPublisher apiKeyPublisher;

    public List<TenantDto> getAllTenants() {
        String scope = tenantAccess.resolveScope(null);
        List<Tenant> tenants = scope == null
                ? repository.findAll()
                : repository.findByTenantId(scope).map(List::of).orElseGet(List::of);
        return tenants.stream().map(this::mapToDto).collect(Collectors.toList());
    }

    public TenantDto getTenant(Long id) {
        Tenant tenant = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenantAccess.requireAccessTo(tenant.getTenantId());
        return mapToDto(tenant);
    }

    /** Creating a tenant is inherently cross-tenant. */
    public TenantDto createTenant(TenantDto dto) {
        tenantAccess.requireSuperAdmin();
        if (dto.getTenantId() == null || dto.getTenantId().isBlank()) {
            throw new IllegalArgumentException("Tenant ID is required");
        }
        if (repository.findByTenantId(dto.getTenantId()).isPresent()) {
            throw new IllegalArgumentException("Tenant ID already exists");
        }
        // Generated here, never taken from the request: a caller must not be able to
        // choose a tenant's key.
        String apiKey = ApiKeys.generate();
        Tenant tenant = Tenant.builder()
                .tenantId(dto.getTenantId())
                .name(dto.getName())
                .apiKeyHash(ApiKeys.hash(apiKey))
                .apiKeyPrefix(ApiKeys.prefix(apiKey))
                .tier(dto.getTier())
                .active(dto.isActive())
                .build();
        TenantDto created = mapToDto(repository.save(tenant));
        apiKeyPublisher.publish();
        created.setApiKey(apiKey);  // the only time the full key is returned
        return created;
    }

    /**
     * Replaces the tenant's API key; the old one stops working as soon as the gateway
     * sees the new set. The new key is returned this once and cannot be read back.
     */
    public TenantDto rotateApiKey(Long id) {
        Tenant tenant = repository.findById(id).orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenantAccess.requireAccessTo(tenant.getTenantId());
        String apiKey = ApiKeys.generate();
        tenant.setApiKeyHash(ApiKeys.hash(apiKey));
        tenant.setApiKeyPrefix(ApiKeys.prefix(apiKey));
        TenantDto rotated = mapToDto(repository.save(tenant));
        apiKeyPublisher.publish();
        rotated.setApiKey(apiKey);
        return rotated;
    }

    public TenantDto updateTenant(Long id, TenantDto dto) {
        Tenant tenant = repository.findById(id).orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenantAccess.requireAccessTo(tenant.getTenantId());
        tenant.setName(dto.getName());
        tenant.setTier(dto.getTier());
        tenant.setActive(dto.isActive());
        TenantDto updated = mapToDto(repository.save(tenant));
        apiKeyPublisher.publish();  // a deactivated tenant's key must stop working
        return updated;
    }

    public void deleteTenant(Long id) {
        Tenant tenant = repository.findById(id).orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenantAccess.requireAccessTo(tenant.getTenantId());
        tenant.setActive(false);
        repository.save(tenant);
        apiKeyPublisher.publish();
    }


    private TenantDto mapToDto(Tenant entity) {
        TenantDto dto = new TenantDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        // Only the prefix: the key itself is not stored, and is returned in full only by
        // create and rotate.
        dto.setApiKey(entity.getApiKeyPrefix() + "****");
        dto.setTier(entity.getTier());
        dto.setActive(entity.isActive());
        return dto;
    }
}
