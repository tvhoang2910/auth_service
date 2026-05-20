package com.exam_bank.auth_service;

import com.exam_bank.auth_service.dto.request.LoginRequest;
import com.exam_bank.auth_service.dto.request.RefreshTokenRequest;
import com.exam_bank.auth_service.dto.request.RegisterRequest;
import com.exam_bank.auth_service.dto.request.UpdateMyProfileRequest;
import com.exam_bank.auth_service.dto.response.AuthTokenResponse;
import com.exam_bank.auth_service.dto.response.RegisterResponse;
import com.exam_bank.auth_service.dto.response.UserProfileResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.service.AvatarStorageService;
import com.exam_bank.auth_service.service.PresenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for AuthController.
 * Uses running infrastructure: PostgreSQL, Redis.
 * Mocks RabbitMQ publishing and MinIO to avoid side-effects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@DisplayName("AuthController Integration Tests")
class AuthControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private AvatarStorageService avatarStorageService;

    @MockitoBean
    private PresenceService presenceService;

    private static final String BASE = "/api/v1/auth";

    /** Unique test email per test run to avoid collisions. */
    private String testEmail;
    private String testPassword;
    private String testFullName;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        testEmail = "testuser+" + suffix + "@example.com";
        testPassword = "TestPass@123";
        testFullName = "Test User " + suffix;
    }

    @AfterEach
    void cleanup() {
        userRepository.findByEmailIgnoreCase(testEmail)
                .ifPresent(user -> userRepository.delete(user));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private RegisterResponse registerUser() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(testEmail);
        request.setPassword(testPassword);
        request.setFullName(testFullName);
        request.setRole(Role.USER);

        ResponseEntity<RegisterResponse> response = restTemplate.postForEntity(
                BASE + "/register", request, RegisterResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void verifyEmail() {
        // Directly set emailVerified = true via repository so login can proceed
        userRepository.findByEmailIgnoreCase(testEmail).ifPresent(user -> {
            user.setEmailVerified(true);
            userRepository.save(user);
        });
    }

    private AuthTokenResponse loginUser() {
        verifyEmail();
        LoginRequest request = new LoginRequest();
        request.setEmail(testEmail);
        request.setPassword(testPassword);

        ResponseEntity<AuthTokenResponse> response = restTemplate.postForEntity(
                BASE + "/login", request, AuthTokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders bearerHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    // ─── Register Tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("register_success_shouldReturn201WithRegisterResponse")
    void register_success_shouldReturn201WithRegisterResponse() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(testEmail);
        request.setPassword(testPassword);
        request.setFullName(testFullName);

        ResponseEntity<RegisterResponse> response = restTemplate.postForEntity(
                BASE + "/register", request, RegisterResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo(testEmail);
        assertThat(response.getBody().fullName()).isEqualTo(testFullName);
        assertThat(response.getBody().id()).isPositive();
    }

    @Test
    @DisplayName("register_duplicateEmail_shouldReturn409Conflict")
    void register_duplicateEmail_shouldReturn409Conflict() {
        // Register first time
        registerUser();

        // Register again with same email
        RegisterRequest duplicate = new RegisterRequest();
        duplicate.setEmail(testEmail);
        duplicate.setPassword(testPassword);
        duplicate.setFullName("Another Person");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                BASE + "/register", duplicate, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("register_invalidEmail_shouldReturn400")
    void register_invalidEmail_shouldReturn400() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("not-an-email");
        request.setPassword(testPassword);
        request.setFullName(testFullName);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                BASE + "/register", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ─── Login Tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("login_success_shouldReturn200WithTokens")
    void login_success_shouldReturn200WithTokens() {
        registerUser();
        AuthTokenResponse tokens = loginUser();

        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.email()).isEqualTo(testEmail);
        assertThat(tokens.role()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("login_wrongPassword_shouldReturn401")
    void login_wrongPassword_shouldReturn401() {
        registerUser();
        verifyEmail();

        LoginRequest request = new LoginRequest();
        request.setEmail(testEmail);
        request.setPassword("WrongPassword@999");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                BASE + "/login", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("login_nonexistentEmail_shouldReturn401")
    void login_nonexistentEmail_shouldReturn401() {
        LoginRequest request = new LoginRequest();
        request.setEmail("ghost_" + UUID.randomUUID() + "@example.com");
        request.setPassword("SomePass@123");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                BASE + "/login", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── /me Tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMe_withValidToken_shouldReturn200WithUserProfile")
    void getMe_withValidToken_shouldReturn200WithUserProfile() {
        registerUser();
        AuthTokenResponse tokens = loginUser();

        HttpHeaders headers = bearerHeaders(tokens.accessToken());
        ResponseEntity<UserProfileResponse> response = restTemplate.exchange(
                BASE + "/me", HttpMethod.GET,
                new HttpEntity<>(headers), UserProfileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo(testEmail);
        assertThat(response.getBody().fullName()).isEqualTo(testFullName);
    }

    @Test
    @DisplayName("getMe_withoutToken_shouldReturn401")
    void getMe_withoutToken_shouldReturn401() {
        ResponseEntity<Map> response = restTemplate.getForEntity(BASE + "/me", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("updateMe_withValidToken_shouldReturn200WithUpdatedProfile")
    void updateMe_withValidToken_shouldReturn200WithUpdatedProfile() {
        registerUser();
        AuthTokenResponse tokens = loginUser();

        UpdateMyProfileRequest updateRequest = new UpdateMyProfileRequest(
                "Updated Name", null, "0912345678", "Hanoi University", "Math", null, null);

        HttpHeaders headers = bearerHeaders(tokens.accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<UserProfileResponse> response = restTemplate.exchange(
                BASE + "/me", HttpMethod.PATCH,
                new HttpEntity<>(updateRequest, headers), UserProfileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fullName()).isEqualTo("Updated Name");
    }

    // ─── Refresh Token Tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("refresh_withValidToken_shouldReturn200WithNewTokens")
    void refresh_withValidToken_shouldReturn200WithNewTokens() {
        registerUser();
        AuthTokenResponse originalTokens = loginUser();

        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken(originalTokens.refreshToken());

        ResponseEntity<AuthTokenResponse> response = restTemplate.postForEntity(
                BASE + "/refresh", refreshRequest, AuthTokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("refresh_withInvalidToken_shouldReturn401")
    void refresh_withInvalidToken_shouldReturn401() {
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest();
        refreshRequest.setRefreshToken("invalid-refresh-token-that-does-not-exist");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                BASE + "/refresh", refreshRequest, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── Logout Tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout_withValidToken_shouldReturn200")
    void logout_withValidToken_shouldReturn200() {
        registerUser();
        AuthTokenResponse tokens = loginUser();

        HttpHeaders headers = bearerHeaders(tokens.accessToken());
        ResponseEntity<Map> response = restTemplate.exchange(
                BASE + "/logout", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("message");
    }

    // ─── Forgot Password Tests ────────────────────────────────────────────────

    @Test
    @DisplayName("forgotPassword_withAnyEmail_shouldReturn200ToPreventEnumeration")
    void forgotPassword_withAnyEmail_shouldReturn200ToPreventEnumeration() {
        // Even for a nonexistent email, must return 200 to prevent user enumeration
        Map<String, String> requestBody = Map.of("email", "nonexistent_" + UUID.randomUUID() + "@example.com");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                BASE + "/forgot-password", requestBody, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("message");
    }

    @Test
    @DisplayName("forgotPassword_withExistingEmail_shouldReturn200")
    void forgotPassword_withExistingEmail_shouldReturn200() {
        registerUser();

        Map<String, String> requestBody = Map.of("email", testEmail);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                BASE + "/forgot-password", requestBody, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ─── Token Blacklist Test ─────────────────────────────────────────────────

    @Test
    @DisplayName("getMe_afterLogout_shouldReturn401BecauseTokenIsBlacklisted")
    void getMe_afterLogout_shouldReturn401BecauseTokenIsBlacklisted() {
        registerUser();
        AuthTokenResponse tokens = loginUser();

        // Logout first
        HttpHeaders logoutHeaders = bearerHeaders(tokens.accessToken());
        restTemplate.exchange(BASE + "/logout", HttpMethod.POST,
                new HttpEntity<>(logoutHeaders), Map.class);

        // Try to use blacklisted token
        HttpHeaders meHeaders = bearerHeaders(tokens.accessToken());
        ResponseEntity<Map> meResponse = restTemplate.exchange(
                BASE + "/me", HttpMethod.GET,
                new HttpEntity<>(meHeaders), Map.class);

        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
