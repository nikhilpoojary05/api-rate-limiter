package com.ratelimiter.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitRule {
    private String tenantId;
    private String tier;
    private String algorithm; // SLIDING_WINDOW or TOKEN_BUCKET
    private int requestLimit;
    private int burstCapacity;
    private long windowMs;
    private boolean active;
}
