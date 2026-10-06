package com.ratelimiter.auth;

import com.ratelimiter.auth.dto.AuthResponse;
import com.ratelimiter.auth.dto.LoginRequest;
import com.ratelimiter.auth.dto.RegisterRequest;
import com.ratelimiter.auth.dto.UserDto;
import com.ratelimiter.auth.entity.RefreshToken;
import com.ratelimiter.auth.exception.ClientVisibleException;
import com.ratelimiter.auth.repository.RefreshTokenRepository;
import com.ratelimiter.auth.repository.UserRepository;
import com.ratelimiter.auth.service.AuthService;
import com.ratelimiter.common.security.JwtUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth-service against real PostgreSQL and Redis. Nothing is mocked: refresh-token
 * rotation depends on two transactions committing independently, which only a real
 * database can show.
 *
 * <p>Each test registers its own users, so tests cannot interfere through shared
 * accounts, lockout counters or revoked token families.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AuthServiceIntegrationTest {

    private static final String ACME_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ACME_CODE = "acme-join-4f7c21";
    private static final String FREE_USER_CO_ID = "33333333-3333-3333-3333-333333333333";
    private static final String PASSWORD = "correct-horse-battery";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("jwt.secret", () -> "integration-test-signing-key-not-used-anywhere-else-0123456789");
    }

    @Autowired AuthService authService;
    @Autowired JwtUtils jwtUtils;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired @Qualifier("redisTemplate") RedisTemplate<String, String> redis;  // the one AuthService uses
    @Autowired MockMvc mvc;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static RegisterRequest registration(String username, String tenantId, String code) {
        return RegisterRequest.builder()
                .username(username).email(username + "@example.com").password(PASSWORD)
                .tenantId(tenantId).registrationCode(code).build();
    }

    /** A fresh acme-corp user, so no test shares an account with another. */
    private String newUser() {
        String username = unique("user");
        authService.register(registration(username, ACME_ID, ACME_CODE));
        return username;
    }

    private AuthResponse login(String username, String password) {
        return authService.login(LoginRequest.builder().username(username).password(password).build());
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("issues an access token carrying the tenant, tier and roles")
        void issuesAccessToken() {
            AuthResponse response = login("admin", "Admin@123!");

            JwtUtils.VerifiedToken token = jwtUtils.verify(response.getAccessToken());
            assertThat(token.username()).isEqualTo("admin");
            assertThat(token.tenantId()).isEqualTo("acme-corp");
            assertThat(token.tier()).isEqualTo("TIER_A");
            assertThat(token.roles()).contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
            assertThat(response.getRefreshToken()).isNotBlank();
        }

        @Test
        @DisplayName("gives the same answer for a wrong password, an unknown user and a disabled account")
        void sameAnswerForEveryFailure() {
            String disabled = newUser();
            var user = users.findByUsername(disabled).orElseThrow();
            user.setActive(false);
            users.save(user);

            for (String[] attempt : new String[][]{
                    {newUser(), "wrong-password-123"},
                    {unique("nobody"), PASSWORD},
                    {disabled, PASSWORD}}) {
                assertThatThrownBy(() -> login(attempt[0], attempt[1]))
                        .isInstanceOf(BadCredentialsException.class)
                        .hasMessage("Invalid username or password");
            }
        }

        @Test
        @DisplayName("after five failed attempts refuses even the right password")
        void locksAfterFiveFailures() {
            String username = newUser();
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> login(username, "wrong-password-123"))
                        .isInstanceOf(BadCredentialsException.class);
            }

            assertThatThrownBy(() -> login(username, PASSWORD))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Invalid username or password");
        }

        @Test
        @DisplayName("a successful login resets the failed-attempt count")
        void successResetsFailures() {
            String username = newUser();
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> login(username, "wrong-password-123")).isInstanceOf(BadCredentialsException.class);
            }
            login(username, PASSWORD);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> login(username, "wrong-password-123")).isInstanceOf(BadCredentialsException.class);
            }

            assertThat(login(username, PASSWORD).getAccessToken()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("joins the tenant named by a valid code, with the tenant's tier")
        void joinsTenant() {
            UserDto user = authService.register(registration(unique("new"), ACME_ID, ACME_CODE));

            assertThat(user.getTenantId()).isEqualTo("acme-corp");
            assertThat(user.getTier()).isEqualTo("TIER_A");
            assertThat(user.getRoles()).containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("refuses a wrong code, an unknown or malformed tenant and a closed tenant with one message")
        void refusesWithOneMessage() {
            for (RegisterRequest request : new RegisterRequest[]{
                    registration(unique("a"), ACME_ID, "wrong-code"),
                    registration(unique("b"), UUID.randomUUID().toString(), ACME_CODE),
                    registration(unique("c"), "not-a-uuid", ACME_CODE),
                    registration(unique("d"), FREE_USER_CO_ID, "anything")}) {
                assertThatThrownBy(() -> authService.register(request))
                        .isInstanceOf(ClientVisibleException.class)
                        .hasMessage("Unknown tenant or invalid registration code");
            }
        }

        @Test
        @DisplayName("refuses a username that is already taken")
        void refusesDuplicateUsername() {
            String username = newUser();
            RegisterRequest again = registration(username, ACME_ID, ACME_CODE);
            again.setEmail(unique("other") + "@example.com");

            assertThatThrownBy(() -> authService.register(again))
                    .isInstanceOf(ClientVisibleException.class)
                    .hasMessage("Username is already taken");
        }
    }

    @Nested
    @DisplayName("refresh tokens")
    class RefreshTokens {

        @Test
        @DisplayName("rotate on every use: the presented token is spent")
        void rotate() {
            String spent = login(newUser(), PASSWORD).getRefreshToken();

            AuthResponse refreshed = authService.refresh(spent);

            assertThat(refreshed.getRefreshToken()).isNotEqualTo(spent);
            assertThat(refreshTokens.findByToken(spent).orElseThrow().isRevoked()).isTrue();
            assertThat(redis.hasKey("refresh:" + spent)).isFalse();
            assertThat(jwtUtils.verify(refreshed.getAccessToken()).tenantId()).isEqualTo("acme-corp");
        }

        @Test
        @DisplayName("replaying a spent token revokes the user's every token, and the revocation sticks")
        void replayRevokesTheFamily() {
            String username = newUser();
            String sessionA = login(username, PASSWORD).getRefreshToken();
            String sessionB = login(username, PASSWORD).getRefreshToken();
            String rotatedA = authService.refresh(sessionA).getRefreshToken();

            // The replay is rejected, which rolls back the request's transaction. The
            // revocation runs in its own transaction and must survive that.
            assertThatThrownBy(() -> authService.refresh(sessionA))
                    .isInstanceOf(ClientVisibleException.class);

            String userId = String.valueOf(users.findByUsername(username).orElseThrow().getId());
            assertThat(refreshTokens.findAllByUserIdAndRevokedFalse(userId)).isEmpty();
            for (String token : new String[]{sessionB, rotatedA}) {
                assertThat(redis.hasKey("refresh:" + token)).isFalse();
                assertThatThrownBy(() -> authService.refresh(token)).isInstanceOf(ClientVisibleException.class);
            }
        }

        @Test
        @DisplayName("are refused once expired")
        void refuseExpired() {
            String token = login(newUser(), PASSWORD).getRefreshToken();
            RefreshToken row = refreshTokens.findByToken(token).orElseThrow();
            row.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            refreshTokens.save(row);

            assertThatThrownBy(() -> authService.refresh(token))
                    .isInstanceOf(ClientVisibleException.class)
                    .hasMessage("Invalid or expired refresh token");
        }

        @Test
        @DisplayName("are refused when missing from Redis, the revocation check")
        void refuseWithoutRedisEntry() {
            String token = login(newUser(), PASSWORD).getRefreshToken();
            redis.delete("refresh:" + token);

            assertThatThrownBy(() -> authService.refresh(token)).isInstanceOf(ClientVisibleException.class);
        }

        @Test
        @DisplayName("are refused after logout")
        void refuseAfterLogout() {
            String token = login(newUser(), PASSWORD).getRefreshToken();

            authService.logout(token);

            assertThatThrownBy(() -> authService.refresh(token)).isInstanceOf(ClientVisibleException.class);
        }
    }

    @Nested
    @DisplayName("over HTTP")
    class OverHttp {

        private Cookie loginCookie(String username) throws Exception {
            MvcResult result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            return result.getResponse().getCookie("refresh_token");
        }

        @Test
        @DisplayName("login sets the refresh token as an HttpOnly, SameSite=Strict cookie")
        void loginSetsCookie() throws Exception {
            MvcResult result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + newUser() + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andReturn();

            String setCookie = result.getResponse().getHeader("Set-Cookie");
            assertThat(setCookie).startsWith("refresh_token=").contains("HttpOnly").contains("SameSite=Strict");
        }

        @Test
        @DisplayName("refresh reads the cookie and rotates it")
        void refreshRotatesCookie() throws Exception {
            Cookie cookie = loginCookie(newUser());

            MvcResult result = mvc.perform(post("/auth/refresh").cookie(cookie))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getCookie("refresh_token").getValue()).isNotEqualTo(cookie.getValue());
        }

        @Test
        @DisplayName("a refresh token in the query string is ignored")
        void ignoresQueryString() throws Exception {
            Cookie cookie = loginCookie(newUser());

            mvc.perform(post("/auth/refresh").param("refreshToken", cookie.getValue()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("logout with the cookie revokes the refresh token")
        void logoutRevokes() throws Exception {
            Cookie cookie = loginCookie(newUser());

            mvc.perform(post("/auth/logout").cookie(cookie)).andExpect(status().isOk());

            assertThat(refreshTokens.findByToken(cookie.getValue()).orElseThrow().isRevoked()).isTrue();
        }

        @Test
        @DisplayName("bad credentials get 401 and a generic message")
        void badCredentials() throws Exception {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + unique("nobody") + "\",\"password\":\"whatever-123\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid username or password"));
        }

        @Test
        @DisplayName("a password under 12 characters is refused at registration")
        void shortPassword() throws Exception {
            mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + unique("short") + "\",\"email\":\"s@example.com\","
                                    + "\"password\":\"short\",\"tenantId\":\"" + ACME_ID + "\","
                                    + "\"registrationCode\":\"" + ACME_CODE + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
