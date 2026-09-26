package com.ratelimiter.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TenantTrafficStat {
    private String tenantId;
    private long blockedCount;
}
