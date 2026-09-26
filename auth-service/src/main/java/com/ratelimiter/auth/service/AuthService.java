package com.ratelimiter.auth.service;

import com.ratelimiter.auth.dto.AuthResponse;
import com.ratelimiter.auth.dto.LoginRequest;
import com.ratelimiter.auth.dto.RegisterRequest;
import com.ratelimiter.auth.dto.UserDto;
import com.ratelimiter.auth.entity.AppUser;
import com.ratelimiter.auth.entity.RefreshToken;
import com.ratelimiter.auth.entity.Tenant;
import com.ratelimiter.auth.repository.RefreshTokenRepository;
import com.ratelimiter.auth.repository.TenantRepository;
import com.ratelimiter.auth.repository.UserRepository;
import com.ratelimiter.common.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RedisTemplate<String, String> redisTemplate;

    private static final long REFRESH_TOKEN_EXPIRY_DAYS = 7;
    private static final String REDIS_REFRESH_PREFIX = "refresh:";

    /**
     * Failed-login throttling. The gateway limits login attempts per source IP; this
     * complements it by limiting attempts per account, so a distributed attempt against
     * one account is slowed too.
     *
     * <p>Counting per username means an attacker can deliberately lock a known account
     * out. That is why this is a short cooling-off window rather than a lock that needs
     * an administrator to clear, and why a successful login resets it immediately.
     */
    private static final String REDIS_LOGIN_FAIL_PREFIX = "login:fail:";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_MINUTES = 15;

    @Transactional
    public UserDto register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username is already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email is already in use");
        }

        Tenant tenant = tenantRepository.findById(UUID.fromString(request.getTenantId()))
        .orElseThrow(() -> new RuntimeException("Tenant not found: " + request.getTenantId()));
        Set<String> roles = new HashSet<>();
        roles.add("ROLE_USER");

        AppUser user = AppUser.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .tenantId(tenant.getId())
                .tier(tenant.getTier())
                .roles(roles)
                .active(true)
                .emailVerified(false)
                .build();

        AppUser savedUser = userRepository.save(user);
        log.info("Registered user '{}' in tenant '{}'", savedUser.getUsername(), savedUser.getTenantId());
        return mapToUserDto(savedUser);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String username = request.getUsername();

        if (isTemporarilyLocked(username)) {
            log.warn("Rejected login for '{}': too many recent failed attempts", username);
            throw new BadCredentialsException("Invalid username or password");
        }

        // Same exception and message for unknown user, wrong password and disabled
        // account, so responses cannot be used to enumerate valid usernames.
        AppUser user = userRepository.findByUsername(username).orElse(null);

        if (user == null
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())
                || !user.isActive()) {
            recordFailedAttempt(username);
            throw new BadCredentialsException("Invalid username or password");
        }

        clearFailedAttempts(username);

        String userId = String.valueOf(user.getId());

        String accessToken = jwtUtils.generateAccessToken(
            userId,
            user.getUsername(),
            resolveTenantSlug(user),
            user.getTenantId().toString(),
            user.getTier(),
            user.getRoles()
            );

        // Opaque refresh token stored in DB + Redis
        String refreshTokenStr = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .token(refreshTokenStr)
                .userId(userId)
                .tenantId(user.getTenantId().toString())    
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_EXPIRY_DAYS))
                .revoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);

        // Redis TTL for instant-revocation support
        redisTemplate.opsForValue().set(
                REDIS_REFRESH_PREFIX + refreshTokenStr,
                userId,
                REFRESH_TOKEN_EXPIRY_DAYS,
                TimeUnit.DAYS
        );

        log.info("User '{}' logged in (tenant: {})", user.getUsername(), user.getTenantId());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenStr)
                .user(mapToUserDto(user))
                .build();
    }

    @Transactional
    public AuthResponse refresh(String refreshTokenStr) {
        // Check Redis first (fast revocation check)
        String cachedUserId = redisTemplate.opsForValue().get(REDIS_REFRESH_PREFIX + refreshTokenStr);
        if (cachedUserId == null) {
            throw new RuntimeException("Invalid or expired refresh token");
        }

        RefreshToken refreshToken = refreshTokenRepository.findByTokenAndRevokedFalse(refreshTokenStr)
                .orElseThrow(() -> new RuntimeException("Refresh token not found or revoked"));

        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Refresh token has expired");
        }

        AppUser user = userRepository.findById(Long.valueOf(refreshToken.getUserId()))
                .orElseThrow(() -> new RuntimeException("User not found"));

        String newAccessToken = jwtUtils.generateAccessToken(
            String.valueOf(user.getId()),
            user.getUsername(),
            resolveTenantSlug(user),
            user.getTenantId().toString(),
            user.getTier(),
            user.getRoles()
        );

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshTokenStr)
                .user(mapToUserDto(user))
                .build();
    }

    private boolean isTemporarilyLocked(String username) {
        String attempts = redisTemplate.opsForValue().get(REDIS_LOGIN_FAIL_PREFIX + username);
        return attempts != null && Integer.parseInt(attempts) >= MAX_FAILED_ATTEMPTS;
    }

    private void recordFailedAttempt(String username) {
        String key = REDIS_LOGIN_FAIL_PREFIX + username;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, LOCKOUT_MINUTES, TimeUnit.MINUTES);
        }
    }

    private void clearFailedAttempts(String username) {
        redisTemplate.delete(REDIS_LOGIN_FAIL_PREFIX + username);
    }

    @Transactional
    public void logout(String refreshTokenStr) {
        refreshTokenRepository.revokeByToken(refreshTokenStr);
        redisTemplate.delete(REDIS_REFRESH_PREFIX + refreshTokenStr);
        log.info("Refresh token revoked");
    }

    public UserDto getMe(String token) {
        String userId = jwtUtils.extractUserId(token);
        AppUser user = userRepository.findById(Long.valueOf(userId))
                .orElseThrow(() -> new RuntimeException("User not found"));
        return mapToUserDto(user);
    }

    /**
     * The tenant's human-readable name is the canonical tenant key across the system —
     * rate limit rules, tenant records and traffic analytics are all stored against it.
     * The UUID stays the auth-service's own primary key.
     */
    private String resolveTenantSlug(AppUser user) {
        if (user.getTenantId() == null) {
            throw new IllegalStateException("User " + user.getId() + " has no tenant assigned");
        }
        return tenantRepository.findById(user.getTenantId())
                .map(Tenant::getName)
                .orElseThrow(() -> new IllegalStateException(
                        "Tenant " + user.getTenantId() + " referenced by user " + user.getId() + " does not exist"));
    }

    private UserDto mapToUserDto(AppUser user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .tenantId(resolveTenantSlug(user))
                .tier(user.getTier())
                .roles(user.getRoles())
                .build();
    }
}
