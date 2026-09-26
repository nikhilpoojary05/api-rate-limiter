package com.ratelimiter.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for /auth/refresh and /auth/logout.
 *
 * <p>These used to take the refresh token as a query parameter, which put a
 * seven-day credential into access logs, browser history and Referer headers.
 * The token now travels in an HttpOnly cookie, with this body as the fallback
 * for non-browser clients.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRequest {
    private String refreshToken;
}
