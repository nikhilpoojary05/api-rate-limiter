package com.ratelimiter.auth.controller;

import com.ratelimiter.auth.dto.AuthResponse;
import com.ratelimiter.auth.dto.LoginRequest;
import com.ratelimiter.auth.dto.RegisterRequest;
import com.ratelimiter.auth.dto.UserDto;
import com.ratelimiter.auth.service.AuthService;
import com.ratelimiter.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserDto>> register(@Valid @RequestBody RegisterRequest request) {
        UserDto userDto = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success("User registered successfully", userDto));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@RequestParam String refreshToken) {
        AuthResponse response = authService.refresh(refreshToken);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestParam String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.ok(ApiResponse.success("Logout successful", null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDto>> getMe(@RequestHeader("Authorization") String authorizationHeader) {
        String token = authorizationHeader.substring(7); // Remove "Bearer "
        UserDto userDto = authService.getMe(token);
        return ResponseEntity.ok(ApiResponse.success("Current user retrieved", userDto));
    }
}
