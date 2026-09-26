package com.ratelimiter.admin.service;

import com.ratelimiter.admin.dto.TenantDto;
import com.ratelimiter.admin.entity.Tenant;
import com.ratelimiter.admin.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantService {
    private final TenantRepository repository;

    public List<TenantDto> getAllTenants() {
        return repository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }

    public TenantDto getTenant(Long id) {
        return repository.findById(id).map(this::mapToDto).orElseThrow(() -> new RuntimeException("Tenant not found"));
    }

    public TenantDto createTenant(TenantDto dto) {
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
        tenant.setName(dto.getName());
        tenant.setTier(dto.getTier());
        tenant.setActive(dto.isActive());
        return mapToDto(repository.save(tenant));
    }

    public void deleteTenant(Long id) {
        Tenant tenant = repository.findById(id).orElseThrow(() -> new RuntimeException("Tenant not found"));
        tenant.setActive(false);
        repository.save(tenant);
    }

    private TenantDto mapToDto(Tenant entity) {
        TenantDto dto = new TenantDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setApiKey(entity.getApiKey());
        dto.setTier(entity.getTier());
        dto.setActive(entity.isActive());
        return dto;
    }
}
