package com.ratelimiter.admin.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Data
public class RateLimitRuleDto {
    private Long id;

    @NotBlank
    private String tenantId;

    @NotBlank
    private String tier;

    @NotBlank
    private String algorithm;

    @Positive
    private int requestLimit;

    @Positive
    private long windowMs;

    @Positive
    private int burstCapacity;

    private boolean active = true;
}
