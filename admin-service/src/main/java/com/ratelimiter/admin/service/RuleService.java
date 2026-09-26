package com.ratelimiter.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.admin.dto.RateLimitRuleDto;
import com.ratelimiter.admin.entity.RateLimitRule;
import com.ratelimiter.admin.repository.RateLimitRuleRepository;
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

    public List<RateLimitRuleDto> getAllRules() {
        return repository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }

    public List<RateLimitRuleDto> getRulesByTenant(String tenantId) {
        return repository.findByTenantIdAndActive(tenantId, true).stream()
                .map(this::mapToDto).collect(Collectors.toList());
    }

    public RateLimitRuleDto createRule(RateLimitRuleDto dto) {
        if (repository.findByTenantIdAndTier(dto.getTenantId(), dto.getTier()).isPresent()) {
            throw new IllegalArgumentException("Rule with tenant and tier already exists");
        }
        RateLimitRule saved = repository.save(mapFromDto(dto));
        invalidateCache(saved.getTenantId());
        return mapToDto(saved);
    }

    public RateLimitRuleDto updateRule(Long id, RateLimitRuleDto dto) {
        RateLimitRule rule = repository.findById(id).orElseThrow(() -> new RuntimeException("Rule not found"));
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
        rule.setActive(false);
        repository.save(rule);
        invalidateCache(rule.getTenantId());
    }

    public void invalidateCache(String tenantId) {
        redisTemplate.delete("rules:" + tenantId);
        redisTemplate.delete("rules:all");
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
