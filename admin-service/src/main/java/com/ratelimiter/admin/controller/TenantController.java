package com.ratelimiter.admin.controller;

import com.ratelimiter.admin.dto.TenantDto;
import com.ratelimiter.admin.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    public ResponseEntity<List<TenantDto>> getAllTenants() {
        return ResponseEntity.ok(tenantService.getAllTenants());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantDto> getTenant(@PathVariable Long id) {
        return ResponseEntity.ok(tenantService.getTenant(id));
    }

    @PostMapping
    public ResponseEntity<TenantDto> createTenant(@RequestBody TenantDto dto) {
        return ResponseEntity.ok(tenantService.createTenant(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TenantDto> updateTenant(@PathVariable Long id, @RequestBody TenantDto dto) {
        return ResponseEntity.ok(tenantService.updateTenant(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTenant(@PathVariable Long id) {
        tenantService.deleteTenant(id);
        return ResponseEntity.noContent().build();
    }
}
