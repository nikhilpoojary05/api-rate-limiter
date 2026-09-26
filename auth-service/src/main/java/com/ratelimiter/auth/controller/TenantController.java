package com.ratelimiter.auth.controller;

import com.ratelimiter.auth.dto.TenantDto;
import com.ratelimiter.auth.service.TenantService;
import com.ratelimiter.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<TenantDto>>> getAllTenants() {
        List<TenantDto> tenants = tenantService.findAll();
        return ResponseEntity.ok(ApiResponse.success("Tenants retrieved", tenants));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TenantDto>> createTenant(@RequestBody TenantDto tenantDto) {
        TenantDto created = tenantService.createTenant(tenantDto);
        return ResponseEntity.ok(ApiResponse.success("Tenant created", created));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TenantDto>> getTenantById(@PathVariable String id) {
        TenantDto tenant = tenantService.findById(id);
        return ResponseEntity.ok(ApiResponse.success("Tenant retrieved", tenant));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TenantDto>> updateTenant(@PathVariable String id, @RequestBody TenantDto tenantDto) {
        TenantDto updated = tenantService.updateTenant(id, tenantDto);
        return ResponseEntity.ok(ApiResponse.success("Tenant updated", updated));
    }
}
