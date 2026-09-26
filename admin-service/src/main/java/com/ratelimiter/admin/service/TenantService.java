package com.ratelimiter.admin.service;

import com.ratelimiter.admin.dto.TenantDto;
import com.ratelimiter.admin.entity.Tenant;
import com.ratelimiter.admin.repository.TenantRepository;
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
        Tenant tenant = Tenant.builder()
                .tenantId(dto.getTenantId())
                .name(dto.getName())
                .apiKey(dto.getApiKey())
                .tier(dto.getTier())
                .active(dto.isActive())
                .build();
        return mapToDto(repository.save(tenant));
    }

    public TenantDto updateTenant(Long id, TenantDto dto) {
        Tenant tenant = repository.findById(id).orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenantAccess.requireAccessTo(tenant.getTenantId());
        tenant.setName(dto.getName());
        tenant.setTier(dto.getTier());
        tenant.setActive(dto.isActive());
        return mapToDto(repository.save(tenant));
    }

    public void deleteTenant(Long id) {
        Tenant tenant = repository.findById(id).orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenantAccess.requireAccessTo(tenant.getTenantId());
        tenant.setActive(false);
        repository.save(tenant);
    }

    /** Shows enough of a key to identify it, never enough to use it. */
    private static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        int keep = Math.min(4, apiKey.length());
        return apiKey.substring(0, keep) + "****";
    }

    private TenantDto mapToDto(Tenant entity) {
        TenantDto dto = new TenantDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        // Never return the raw API key. It is stored in plaintext today, so listing
        // tenants previously handed every key to anyone who could call the endpoint.
        dto.setApiKey(mask(entity.getApiKey()));
        dto.setTier(entity.getTier());
        dto.setActive(entity.isActive());
        return dto;
    }
}
