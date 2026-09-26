package com.ratelimiter.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantDto {
    private String id;
    private String name;
    private String tier;
    private String description;
    private boolean active;
}
