package com.ratelimiter.auth.controller;

import com.ratelimiter.auth.dto.AuthResponse;
import com.ratelimiter.auth.dto.LoginRequest;
import com.ratelimiter.auth.dto.RefreshTokenRequest;
import com.ratelimiter.auth.dto.RegisterRequest;
import com.ratelimiter.auth.dto.UserDto;
import com.ratelimiter.auth.service.AuthService;
import com.ratelimiter.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * The refresh token is returned as an HttpOnly cookie so browser code cannot read
     * it — previously both tokens sat in localStorage, readable by any XSS. It is also
     * still returned in the response body for non-browser API clients.
     */
    private static final String REFRESH_COOKIE = "refresh_token";

    /** Off by default so local http development works; must be true wherever TLS terminates. */
    @Value("${auth.cookie.secure:false}")
    private boolean cookieSecure;

    /** Root path: the dashboard reaches this service as /auth/** directly and /api/auth/** via the gateway. */
    @Value("${auth.cookie.path:/}")
    private String cookiePath;

    @Value("${jwt.refresh-token-expiry-ms:604800000}")
    private long refreshTokenExpiryMs;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserDto>> register(@Valid @RequestBody RegisterRequest request) {
        UserDto userDto = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success("User registered successfully", userDto));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                           HttpServletResponse response) {
        AuthResponse authResponse = authService.login(request);
        setRefreshCookie(response, authResponse.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Login successful", authResponse));
    }

    /**
     * Takes the refresh token from the cookie, falling back to the request body. It is
     * never read from the query string: a seven-day credential in a URL ends up in
     * access logs, browser history and Referer headers.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletResponse response) {

        AuthResponse authResponse = authService.refresh(tokenFrom(cookieToken, body));
        setRefreshCookie(response, authResponse.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", authResponse));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletResponse response) {

        authService.logout(tokenFrom(cookieToken, body));
        clearRefreshCookie(response);
        return ResponseEntity.ok(ApiResponse.success("Logout successful", null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> getMe(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {

        if (authorizationHeader == null
                || !authorizationHeader.startsWith("Bearer ")
                || authorizationHeader.length() <= 7) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        UserDto userDto = authService.getMe(authorizationHeader.substring(7));
        return ResponseEntity.ok(ApiResponse.success("Current user retrieved", userDto));
    }

    private String tokenFrom(String cookieToken, RefreshTokenRequest body) {
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken;
        }
        return body != null ? body.getRefreshToken() : null;
    }

    private void setRefreshCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(cookiePath)
                .maxAge(Duration.ofMillis(refreshTokenExpiryMs))
                .build()
                .toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(cookiePath)
                .maxAge(0)
                .build()
                .toString());
    }
}
