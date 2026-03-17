package com.exam_bank.auth_service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.exam_bank.auth_service.service.AvatarStorageService;
import com.exam_bank.auth_service.service.LoginAttemptService;
import com.exam_bank.auth_service.service.OtpRateLimitService;
import com.exam_bank.auth_service.service.RefreshTokenService;
import com.exam_bank.auth_service.service.TokenBlacklistService;
import com.exam_bank.auth_service.service.UserProfileCacheService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_api_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.main.allow-bean-definition-overriding=true"
})
@WithAnonymousUser
class AuthApiIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private OtpRateLimitService otpRateLimitService;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    @MockitoBean
    private UserProfileCacheService userProfileCacheService;

    @MockitoBean
    private AvatarStorageService avatarStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private ValueOperations<String, String> valueOperations;
    private Map<String, String> redisValues;

    @BeforeEach
    void setUp() {
        redisValues = new ConcurrentHashMap<>();
        valueOperations = Mockito.mock(ValueOperations.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        Mockito.doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            redisValues.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisValues.get(invocation.getArgument(0)));
        when(stringRedisTemplate.delete(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return redisValues.remove(key) != null;
        });

        doNothing().when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        doNothing().when(otpRateLimitService).checkForgotAllowed(anyString());
        doNothing().when(otpRateLimitService).checkResendAllowed(anyString());
        doNothing().when(otpRateLimitService).checkVerifyAllowed(anyString());

        when(loginAttemptService.isBlocked(anyString())).thenReturn(false);
        doNothing().when(loginAttemptService).clearAttempts(anyString());
        doNothing().when(loginAttemptService).recordFailedAttempt(anyString());

        doNothing().when(refreshTokenService).store(anyLong(), anyString(), any(Duration.class));
    }

    @Test
    void shouldRequireEmailVerificationBeforeLoginAndAllowLoginAfterVerify() throws Exception {
        String email = "integration.user@example.com";
        String password = "strong-password-123";

        HttpResponse<String> registerResponse = postJson(
                "/register",
                """
                        {
                          "email": "%s",
                          "password": "%s",
                          "fullName": "Integration User",
                          "role": "USER"
                        }
                        """.formatted(email, password));

        assertThat(registerResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());

        JsonNode registerBody = objectMapper.readTree(registerResponse.body());
        assertThat(registerBody.path("email").asText()).isEqualTo(email);

        HttpResponse<String> loginBeforeVerifyResponse = postJson(
                "/login",
                """
                        {
                          "email": "%s",
                          "password": "%s"
                        }
                        """.formatted(email, password));

        assertThat(loginBeforeVerifyResponse.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(objectMapper.readTree(loginBeforeVerifyResponse.body()).path("message").asText())
                .isEqualTo("Email is not verified");

        String otp = redisValues.get("register_verify:email:" + email);
        assertThat(otp).matches("\\d{6}");

        HttpResponse<String> verifyResponse = postJson(
                "/register/verify-email",
                """
                        {
                          "email": "%s",
                          "otp": "%s"
                        }
                        """.formatted(email, otp));

        assertThat(verifyResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(objectMapper.readTree(verifyResponse.body()).path("message").asText())
                .isEqualTo("Email verified successfully");

        HttpResponse<String> loginAfterVerifyResponse = postJson(
                "/login",
                """
                        {
                          "email": "%s",
                          "password": "%s"
                        }
                        """.formatted(email, password));

        assertThat(loginAfterVerifyResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        JsonNode loginBody = objectMapper.readTree(loginAfterVerifyResponse.body());
        assertThat(loginBody.path("accessToken").asText()).isNotBlank();
        assertThat(loginBody.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(loginBody.path("email").asText()).isEqualTo(email);
    }

    @Test
    void shouldRejectEmailVerificationWhenOtpInvalid() throws Exception {
        String email = "integration.invalid.otp@example.com";

        HttpResponse<String> registerResponse = postJson(
                "/register",
                objectMapper.writeValueAsString(Map.of(
                        "email", email,
                        "password", "strong-password-123",
                        "fullName", "Integration Invalid Otp",
                        "role", "USER")));

        assertThat(registerResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());

        HttpResponse<String> verifyResponse = postJson(
                "/register/verify-email",
                objectMapper.writeValueAsString(Map.of(
                        "email", email,
                        "otp", "000000")));

        assertThat(verifyResponse.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(objectMapper.readTree(verifyResponse.body()).path("message").asText())
                .isEqualTo("OTP is invalid or expired");
    }

    private HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth" + path))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
