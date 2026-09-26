package com.ratelimiter.admin.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class TrafficEventDto {
    private String tenantId;
    private String userId;
    private String ipAddress;
    private String path;
    private String method;
    private String status;
    private long latencyMs;
    private int httpStatus;
    private Instant timestamp;
}
