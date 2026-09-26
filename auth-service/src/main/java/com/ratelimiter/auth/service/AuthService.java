package com.ratelimiter.auth.service;

import com.ratelimiter.auth.dto.AuthResponse;
import com.ratelimiter.auth.dto.LoginRequest;
import com.ratelimiter.auth.dto.RegisterRequest;
import com.ratelimiter.auth.dto.UserDto;
import com.ratelimiter.auth.entity.AppUser;
import com.ratelimiter.auth.entity.RefreshToken;
import com.ratelimiter.auth.entity.Tenant;
import com.ratelimiter.auth.exception.ClientVisibleException;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final RefreshTokenRevoker refreshTokenRevoker;

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
        // These two do let a caller probe which usernames and emails are registered.
        // Removing that requires an email-confirmation flow, which this service has no
        // mail transport for; registration is rate limited per IP in the meantime. This
        // is a deliberate trade-off for usable signup, not an oversight.
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ClientVisibleException("Username is already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ClientVisibleException("Email is already in use");
        }

        Tenant tenant = resolveTenantForRegistration(request);

        // The tier is always the tenant's own. It is never read from the request —
        // letting a caller name their tier would hand out whatever limit they asked for.
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

    /**
     * Registration used to accept any tenant id and copy that tenant's rate limit tier
     * onto the new account, so anyone could register into a high-tier tenant and help
     * themselves to its quota. Joining now requires the tenant's registration code.
     *
     * <p>Failures deliberately share one message: distinguishing "no such tenant" from
     * "wrong code" would confirm which tenants exist.
     */
    private Tenant resolveTenantForRegistration(RegisterRequest request) {
        Tenant tenant = null;
        try {
            tenant = tenantRepository.findById(UUID.fromString(request.getTenantId())).orElse(null);
        } catch (IllegalArgumentException e) {
            // Not a UUID — fall through to the same generic failure below.
            log.debug("Registration attempted with a malformed tenant id");
        }

        boolean registrationOpen = tenant != null
                && tenant.isActive()
                && tenant.getRegistrationCode() != null
                && !tenant.getRegistrationCode().isBlank();

        if (!registrationOpen
                || !MessageDigest.isEqual(
                        tenant.getRegistrationCode().getBytes(StandardCharsets.UTF_8),
                        request.getRegistrationCode().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected registration for tenant '{}': unknown tenant or bad code",
                    request.getTenantId());
            throw new ClientVisibleException("Unknown tenant or invalid registration code");
        }

        return tenant;
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

        String refreshTokenStr = issueRefreshToken(user);

        log.info("User '{}' logged in (tenant: {})", user.getUsername(), user.getTenantId());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenStr)
                .user(mapToUserDto(user))
                .build();
    }

    /**
     * Rotates the refresh token on every use: the presented token is revoked and a new
     * one issued. Without rotation a single stolen token stayed valid for its whole
     * seven-day life no matter how often it was used.
     *
     * <p>Presenting a token that exists but is already revoked means either the
     * legitimate holder or an attacker is replaying a spent token — and there is no way
     * to tell which. Every outstanding token for that user is revoked, forcing a
     * re-login.
     */
    @Transactional
    public AuthResponse refresh(String refreshTokenStr) {
        if (refreshTokenStr == null || refreshTokenStr.isBlank()) {
            throw new ClientVisibleException("Invalid or expired refresh token");
        }

        RefreshToken stored = refreshTokenRepository.findByToken(refreshTokenStr).orElse(null);

        if (stored != null && stored.isRevoked()) {
            // Committed in its own transaction: the throw below rolls this one back.
            int revoked = refreshTokenRevoker.revokeAllForUser(stored.getUserId());
            log.warn("Refresh token replay detected for userId={} — revoked {} outstanding tokens",
                    stored.getUserId(), revoked);
            throw new ClientVisibleException("Invalid or expired refresh token");
        }

        // Redis is the fast revocation check; the row above is the source of truth.
        String cachedUserId = redisTemplate.opsForValue().get(REDIS_REFRESH_PREFIX + refreshTokenStr);
        if (cachedUserId == null || stored == null) {
            throw new ClientVisibleException("Invalid or expired refresh token");
        }

        RefreshToken refreshToken = stored;

        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ClientVisibleException("Invalid or expired refresh token");
        }

        AppUser user = userRepository.findById(Long.valueOf(refreshToken.getUserId()))
                .orElseThrow(() -> new RuntimeException("Invalid or expired refresh token"));

        // Retire the presented token before handing out its replacement.
        refreshTokenRepository.revokeByToken(refreshTokenStr);
        redisTemplate.delete(REDIS_REFRESH_PREFIX + refreshTokenStr);
        String rotated = issueRefreshToken(user);

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
                .refreshToken(rotated)
                .user(mapToUserDto(user))
                .build();
    }

    /** Mints an opaque refresh token, recorded in the database and mirrored into Redis. */
    private String issueRefreshToken(AppUser user) {
        String token = UUID.randomUUID().toString();
        String userId = String.valueOf(user.getId());

        refreshTokenRepository.save(RefreshToken.builder()
                .token(token)
                .userId(userId)
                .tenantId(user.getTenantId().toString())
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_EXPIRY_DAYS))
                .revoked(false)
                .build());

        redisTemplate.opsForValue().set(
                REDIS_REFRESH_PREFIX + token, userId, REFRESH_TOKEN_EXPIRY_DAYS, TimeUnit.DAYS);

        return token;
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
