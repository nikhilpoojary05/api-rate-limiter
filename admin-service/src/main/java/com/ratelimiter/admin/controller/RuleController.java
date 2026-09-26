package com.ratelimiter.admin.controller;

import com.ratelimiter.admin.dto.RateLimitRuleDto;
import com.ratelimiter.admin.service.RuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/rules")
@RequiredArgsConstructor
public class RuleController {
    
    private final RuleService ruleService;

    @GetMapping
    public ResponseEntity<Map<String, List<RateLimitRuleDto>>> getAllRules() {
        return ResponseEntity.ok(Map.of("data", ruleService.getAllRules()));
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<List<RateLimitRuleDto>> getRulesByTenant(@PathVariable String tenantId) {
        return ResponseEntity.ok(ruleService.getRulesByTenant(tenantId));
    }

    @PostMapping
    public ResponseEntity<RateLimitRuleDto> createRule(@RequestBody @Valid RateLimitRuleDto dto) {
        return ResponseEntity.ok(ruleService.createRule(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RateLimitRuleDto> updateRule(@PathVariable Long id, @RequestBody @Valid RateLimitRuleDto dto) {
        return ResponseEntity.ok(ruleService.updateRule(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        ruleService.deleteRule(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/publish")
    public ResponseEntity<Void> publishRules() {
        ruleService.publishAllRulesToRedis();
        return ResponseEntity.ok().build();
    }
}
