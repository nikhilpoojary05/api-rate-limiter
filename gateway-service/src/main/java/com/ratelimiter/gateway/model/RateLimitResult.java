package com.ratelimiter.gateway.model;

public record RateLimitResult(
    boolean allowed,
    int remaining,
    long resetMs
) {}
