package com.ratelimiter.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficEventDto {

    private String tenantId;
    private String userId;
    private String ipAddress;
    private String path;
    private String method;
    private Status status;
    private long latencyMs;
    private int httpStatus;
    private Instant timestamp;

    public enum Status {
        ALLOWED, BLOCKED
    }
}
