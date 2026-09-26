package com.ratelimiter.admin.repository;

import com.ratelimiter.admin.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {
    List<Tenant> findAllByActive(boolean active);
    Optional<Tenant> findByTenantId(String tenantId);
    long countByActive(boolean active);
}
