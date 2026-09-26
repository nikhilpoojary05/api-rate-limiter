package com.ratelimiter.admin.repository;

import com.ratelimiter.admin.entity.RateLimitRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RateLimitRuleRepository extends JpaRepository<RateLimitRule, Long> {
    List<RateLimitRule> findByTenantIdAndActive(String tenantId, boolean active);
    Optional<RateLimitRule> findByTenantIdAndTier(String tenantId, String tier);
    List<RateLimitRule> findAllByActive(boolean active);
}
