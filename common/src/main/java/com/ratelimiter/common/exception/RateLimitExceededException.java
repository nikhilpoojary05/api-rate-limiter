package com.ratelimiter.common.exception;

public class RateLimitExceededException extends RuntimeException {

    private final String tenantId;
    private final String userId;
    private final long retryAfterMs;

    public RateLimitExceededException(String tenantId, String userId, long retryAfterMs) {
        super(String.format("Rate limit exceeded for tenant=%s user=%s. Retry after %dms",
                tenantId, userId, retryAfterMs));
        this.tenantId = tenantId;
        this.userId = userId;
        this.retryAfterMs = retryAfterMs;
    }

    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public long getRetryAfterMs() { return retryAfterMs; }
}
