package com.ratelimiter.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitRuleDto {

    private Long id;

    @NotBlank(message = "Tenant ID is required")
    private String tenantId;

    @NotBlank(message = "Tier is required")
    private String tier;

    @NotNull(message = "Algorithm is required")
    private Algorithm algorithm;

    @Positive(message = "Limit must be positive")
    private int requestLimit;

    @Positive(message = "Window must be positive")
    private long windowMs;

    private int burstCapacity;

    @Builder.Default
    private boolean active = true;

    private Instant createdAt;
    private Instant updatedAt;

    public enum Algorithm {
        SLIDING_WINDOW,
        TOKEN_BUCKET
    }
}
