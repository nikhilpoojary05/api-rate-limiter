package com.ratelimiter.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesPointDto {
    private Instant timestamp;
    private long allowed;
    private long blocked;
    private double avgLatency;
}
