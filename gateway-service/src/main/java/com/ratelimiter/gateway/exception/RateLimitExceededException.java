package com.ratelimiter.gateway.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class RateLimitExceededException extends ResponseStatusException {
    
    private final int retryAfter;

    public RateLimitExceededException(int retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        this.retryAfter = retryAfter;
    }

    public int getRetryAfter() {
        return retryAfter;
    }
}
