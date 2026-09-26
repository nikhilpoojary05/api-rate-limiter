package com.ratelimiter.auth.service;

import com.ratelimiter.auth.dto.TenantDto;
import com.ratelimiter.auth.entity.Tenant;
import com.ratelimiter.auth.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;

    @Transactional
    public TenantDto createTenant(TenantDto tenantDto) {
        if (tenantRepository.findByName(tenantDto.getName()).isPresent()) {
            throw new RuntimeException("Tenant already exists with name: " + tenantDto.getName());
        }

        Tenant tenant = Tenant.builder()
                .name(tenantDto.getName())
                .tier(tenantDto.getTier())
                .description(tenantDto.getDescription())
                .active(tenantDto.isActive())
                .build();

        Tenant savedTenant = tenantRepository.save(tenant);
        return mapToDto(savedTenant);
    }

    @Transactional(readOnly = true)
    public TenantDto findById(String id) {
        return tenantRepository.findById(UUID.fromString(id))
                .map(this::mapToDto)
                .orElseThrow(() -> new RuntimeException("Tenant not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<TenantDto> findAll() {
        return tenantRepository.findAll().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public TenantDto updateTenant(String id, TenantDto tenantDto) {
        Tenant tenant = tenantRepository.findById(UUID.fromString(id))      
                .orElseThrow(() -> new RuntimeException("Tenant not found with id: " + id));

        tenant.setTier(tenantDto.getTier());
        tenant.setDescription(tenantDto.getDescription());
        tenant.setActive(tenantDto.isActive());

        Tenant updatedTenant = tenantRepository.save(tenant);
        return mapToDto(updatedTenant);
    }

    private TenantDto mapToDto(Tenant tenant) {
        return TenantDto.builder()
                .id(tenant.getId().toString())
                .name(tenant.getName())
                .tier(tenant.getTier())
                .description(tenant.getDescription())
                .active(tenant.isActive())
                .build();
    }
}
