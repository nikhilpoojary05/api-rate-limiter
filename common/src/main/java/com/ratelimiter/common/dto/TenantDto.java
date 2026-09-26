package com.ratelimiter.common.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDto {

    private String id;

    @NotBlank(message = "Tenant name is required")
    private String name;

    @NotBlank(message = "Tier is required")
    private String tier; // TIER_A, TIER_B, TIER_FREE

    private String description;
    private boolean active;
    private Instant createdAt;
}
