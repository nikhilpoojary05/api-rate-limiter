package com.ratelimiter.admin.dto;

import lombok.Data;

@Data
public class TenantDto {
    private Long id;
    private String tenantId;
    private String name;
    private String apiKey;
    private String tier;
    private boolean active;
}
