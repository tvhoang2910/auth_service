package com.exam_bank.auth_service;

import com.exam_bank.auth_service.dto.request.AdminCreateUserRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserRoleRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserStatusRequest;
import com.exam_bank.auth_service.dto.request.LoginRequest;
import com.exam_bank.auth_service.dto.request.RegisterRequest;
import com.exam_bank.auth_service.dto.response.AdminUserItemResponse;
import com.exam_bank.auth_service.dto.response.AdminUsersPageResponse;
import com.exam_bank.auth_service.dto.response.AuthTokenResponse;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for AdminUserController.
 * Creates a dedicated ADMIN user per test class lifecycle.
 * Regular users and created-admin users are cleaned up after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@DisplayName("AdminUserController Integration Tests")
class AdminUserControllerIntegrationTest {

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
    private static final String ADMIN_BASE = BASE + "/admin/users";

    private String adminEmail;
    private String adminAccessToken;

    private String regularUserEmail;
    private String regularUserAccessToken;

    private final List<String> createdUserEmails = new ArrayList<>();

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // Create admin user directly
        adminEmail = "admin+" + suffix + "@example.com";
        String adminPass = "AdminPass@123";
        registerAndVerify(adminEmail, adminPass, "Admin User " + suffix, Role.ADMIN);
        adminAccessToken = getToken(adminEmail, adminPass);

        // Create regular user
        regularUserEmail = "user+" + suffix + "@example.com";
        String userPass = "UserPass@123";
        registerAndVerify(regularUserEmail, userPass, "Regular User " + suffix, Role.USER);
        regularUserAccessToken = getToken(regularUserEmail, userPass);
    }

    @AfterEach
    void cleanup() {
        // Delete all created users
        userRepository.findByEmailIgnoreCase(adminEmail).ifPresent(userRepository::delete);
        userRepository.findByEmailIgnoreCase(regularUserEmail).ifPresent(userRepository::delete);
        for (String email : createdUserEmails) {
            userRepository.findByEmailIgnoreCase(email).ifPresent(userRepository::delete);
        }
        createdUserEmails.clear();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void registerAndVerify(String email, String password, String fullName, Role role) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword(password);
        request.setFullName(fullName);
        request.setRole(role);
        restTemplate.postForEntity(BASE + "/register", request, Map.class);

        // Directly set emailVerified
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            user.setEmailVerified(true);
            userRepository.save(user);
        });
    }

    private String getToken(String email, String password) {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail(email);
        loginRequest.setPassword(password);
        ResponseEntity<AuthTokenResponse> response = restTemplate.postForEntity(
                BASE + "/login", loginRequest, AuthTokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders bearerJsonHeaders(String token) {
        HttpHeaders headers = bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ─── GET /admin/users ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getAllUsers_asAdmin_shouldReturn200WithPaginatedList")
    void getAllUsers_asAdmin_shouldReturn200WithPaginatedList() {
        HttpHeaders headers = bearerHeaders(adminAccessToken);
        ResponseEntity<AdminUsersPageResponse> response = restTemplate.exchange(
                ADMIN_BASE, HttpMethod.GET, new HttpEntity<>(headers), AdminUsersPageResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isNotNull();
        assertThat(response.getBody().totalElements()).isPositive();
    }

    @Test
    @DisplayName("getAllUsers_asRegularUser_shouldReturn403")
    void getAllUsers_asRegularUser_shouldReturn403() {
        HttpHeaders headers = bearerHeaders(regularUserAccessToken);
        ResponseEntity<Map> response = restTemplate.exchange(
                ADMIN_BASE, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("getAllUsers_unauthenticated_shouldReturn401")
    void getAllUsers_unauthenticated_shouldReturn401() {
        ResponseEntity<Map> response = restTemplate.getForEntity(ADMIN_BASE, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ─── POST /admin/users ────────────────────────────────────────────────────

    @Test
    @DisplayName("createUser_asAdmin_shouldReturn201WithUserItem")
    void createUser_asAdmin_shouldReturn201WithUserItem() {
        String newEmail = "created+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        createdUserEmails.add(newEmail);

        AdminCreateUserRequest createRequest = new AdminCreateUserRequest(
                newEmail, "Created By Admin", "CreatedPass@123", Role.USER);

        HttpHeaders headers = bearerJsonHeaders(adminAccessToken);
        ResponseEntity<AdminUserItemResponse> response = restTemplate.exchange(
                ADMIN_BASE, HttpMethod.POST,
                new HttpEntity<>(createRequest, headers), AdminUserItemResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo(newEmail);
        assertThat(response.getBody().role()).isEqualTo(Role.USER);
    }

    // ─── PUT /admin/users/{id}/role ───────────────────────────────────────────

    @Test
    @DisplayName("updateUserRole_asAdmin_shouldReturn200WithUpdatedRole")
    void updateUserRole_asAdmin_shouldReturn200WithUpdatedRole() {
        Long userId = userRepository.findByEmailIgnoreCase(regularUserEmail)
                .map(u -> u.getId())
                .orElseThrow();

        AdminUpdateUserRoleRequest roleRequest = new AdminUpdateUserRoleRequest(Role.CONTRIBUTOR);

        HttpHeaders headers = bearerJsonHeaders(adminAccessToken);
        ResponseEntity<AdminUserItemResponse> response = restTemplate.exchange(
                ADMIN_BASE + "/" + userId + "/role", HttpMethod.PUT,
                new HttpEntity<>(roleRequest, headers), AdminUserItemResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().role()).isEqualTo(Role.CONTRIBUTOR);

        // Restore original role
        AdminUpdateUserRoleRequest restoreRequest = new AdminUpdateUserRoleRequest(Role.USER);
        restTemplate.exchange(ADMIN_BASE + "/" + userId + "/role", HttpMethod.PUT,
                new HttpEntity<>(restoreRequest, headers), AdminUserItemResponse.class);
    }

    // ─── PUT /admin/users/{id}/status ─────────────────────────────────────────

    @Test
    @DisplayName("updateUserStatus_asAdmin_shouldReturn200WithUpdatedUser")
    void updateUserStatus_asAdmin_shouldReturn200WithUpdatedUser() {
        Long userId = userRepository.findByEmailIgnoreCase(regularUserEmail)
                .map(u -> u.getId())
                .orElseThrow();

        // Deactivate user
        AdminUpdateUserStatusRequest statusRequest = new AdminUpdateUserStatusRequest(
                0, false, "Integration test deactivation");

        HttpHeaders headers = bearerJsonHeaders(adminAccessToken);
        ResponseEntity<AdminUserItemResponse> response = restTemplate.exchange(
                ADMIN_BASE + "/" + userId + "/status", HttpMethod.PUT,
                new HttpEntity<>(statusRequest, headers), AdminUserItemResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Re-activate user so cleanup works
        AdminUpdateUserStatusRequest reactivate = new AdminUpdateUserStatusRequest(
                1, true, "Integration test reactivation");
        restTemplate.exchange(ADMIN_BASE + "/" + userId + "/status", HttpMethod.PUT,
                new HttpEntity<>(reactivate, headers), AdminUserItemResponse.class);
    }
}
