package com.ratelimiter.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.admin.dto.RateLimitRuleDto;
import com.ratelimiter.admin.entity.RateLimitRule;
import com.ratelimiter.admin.repository.RateLimitRuleRepository;
import com.ratelimiter.admin.security.TenantAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RuleService {

    private final RateLimitRuleRepository repository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final TenantAccess tenantAccess;

    /**
     * Authorisation lives in this service rather than the controller because the
     * id-addressed operations below can only know which tenant they touch after
     * loading the rule — putting the check here means no caller can skip it.
     */
    public List<RateLimitRuleDto> getAllRules() {
        String scope = tenantAccess.resolveScope(null);
        List<RateLimitRule> rules = scope == null
                ? repository.findAll()
                : repository.findByTenantIdAndActive(scope, true);
        return rules.stream().map(this::mapToDto).collect(Collectors.toList());
    }

    public List<RateLimitRuleDto> getRulesByTenant(String tenantId) {
        tenantAccess.requireAccessTo(tenantId);
        return repository.findByTenantIdAndActive(tenantId, true).stream()
                .map(this::mapToDto).collect(Collectors.toList());
    }

    public RateLimitRuleDto createRule(RateLimitRuleDto dto) {
        tenantAccess.requireAccessTo(dto.getTenantId());
        if (repository.findByTenantIdAndTier(dto.getTenantId(), dto.getTier()).isPresent()) {
            throw new IllegalArgumentException("Rule with tenant and tier already exists");
        }
        RateLimitRule saved = repository.save(mapFromDto(dto));
        invalidateCache(saved.getTenantId());
        return mapToDto(saved);
    }

    public RateLimitRuleDto updateRule(Long id, RateLimitRuleDto dto) {
        RateLimitRule rule = repository.findById(id).orElseThrow(() -> new RuntimeException("Rule not found"));
        tenantAccess.requireAccessTo(rule.getTenantId());
        rule.setAlgorithm(dto.getAlgorithm());
        rule.setRequestLimit(dto.getRequestLimit());
        rule.setWindowMs(dto.getWindowMs());
        rule.setBurstCapacity(dto.getBurstCapacity());
        rule.setActive(dto.isActive());
        RateLimitRule saved = repository.save(rule);
        invalidateCache(saved.getTenantId());
        return mapToDto(saved);
    }

    public void deleteRule(Long id) {
        RateLimitRule rule = repository.findById(id).orElseThrow(() -> new RuntimeException("Rule not found"));
        tenantAccess.requireAccessTo(rule.getTenantId());
        rule.setActive(false);
        repository.save(rule);
        invalidateCache(rule.getTenantId());
    }

    public void invalidateCache(String tenantId) {
        redisTemplate.delete("rules:" + tenantId);
        redisTemplate.delete("rules:all");
        publishAllRulesToRedis();
    }

    /** Endpoint-facing publish: rewrites every tenant's rules, so it is super-admin only. */
    public void publishAllRulesRequested() {
        tenantAccess.requireSuperAdmin();
        publishAllRulesToRedis();
    }

    public void publishAllRulesToRedis() {
        List<RateLimitRuleDto> allActive = repository.findAllByActive(true).stream()
                .map(this::mapToDto).collect(Collectors.toList());
        try {
            String json = objectMapper.writeValueAsString(allActive);
            redisTemplate.opsForValue().set("rules:all", json);
            log.info("Published {} rules to Redis", allActive.size());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize rules to JSON", e);
        }
    }

    private RateLimitRuleDto mapToDto(RateLimitRule entity) {
        RateLimitRuleDto dto = new RateLimitRuleDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setTier(entity.getTier());
        dto.setAlgorithm(entity.getAlgorithm());
        dto.setRequestLimit(entity.getRequestLimit());
        dto.setWindowMs(entity.getWindowMs());
        dto.setBurstCapacity(entity.getBurstCapacity());
        dto.setActive(entity.isActive());
        return dto;
    }

    private RateLimitRule mapFromDto(RateLimitRuleDto dto) {
        return RateLimitRule.builder()
                .tenantId(dto.getTenantId())
                .tier(dto.getTier())
                .algorithm(dto.getAlgorithm())
                .requestLimit(dto.getRequestLimit())
                .windowMs(dto.getWindowMs())
                .burstCapacity(dto.getBurstCapacity())
                .active(dto.isActive())
                .build();
    }
}
