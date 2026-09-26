package com.ratelimiter.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsSummaryDto {
    private long totalRequests;
    private long blockedRequests;
    private double blockRate;
    private double avgLatencyMs;
    private long activeTenants;
    private List<TenantTrafficStat> topBlockedTenants;
}
