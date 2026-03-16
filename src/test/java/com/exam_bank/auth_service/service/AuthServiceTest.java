package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.AuthForgotPasswordProperties;
import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.message.ForgotPasswordOtpMessage;
import com.exam_bank.auth_service.dto.request.LoginRequest;
import com.exam_bank.auth_service.dto.request.RefreshTokenRequest;
import com.exam_bank.auth_service.dto.request.RegisterRequest;
import com.exam_bank.auth_service.dto.request.UpdateMyProfileRequest;
import com.exam_bank.auth_service.dto.response.AuthTokenResponse;
import com.exam_bank.auth_service.dto.response.RegisterResponse;
import com.exam_bank.auth_service.dto.response.UserProfileResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.exception.BruteForceBlockedException;
import com.exam_bank.auth_service.exception.ConflictException;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private UserProfileCacheService userProfileCacheService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private AuthForgotPasswordProperties forgotPasswordProperties;

    @Mock
    private NotificationRabbitProperties notificationRabbitProperties;

    @Mock
    private OtpRateLimitService otpRateLimitService;

    @Mock
    private SecurityAuditService securityAuditService;

    @Mock
    private AvatarStorageService avatarStorageService;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private AuthService authService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User();
        existingUser.setId(7L);
        existingUser.setEmail("john@example.com");
        existingUser.setFullName("John Doe");
        existingUser.setRole(Role.USER);
        existingUser.setPassword("encoded-password");
        existingUser.setStatus(true);
        existingUser.setCreatedAt(Instant.parse("2026-03-13T00:00:00Z"));
    }

    @Test
    void register_shouldSaveAndReturnResponse_whenEmailNotExists() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("John@Example.com");
        request.setPassword("password123");
        request.setFullName("John Doe");

        when(userRepository.existsByEmailIgnoreCase("john@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            user.setCreatedAt(Instant.parse("2026-03-13T10:00:00Z"));
            return user;
        });

        RegisterResponse response = authService.register(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("john@example.com");
        assertThat(response.fullName()).isEqualTo("John Doe");
        assertThat(response.role()).isEqualTo(Role.USER);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded-password");
    }

    @Test
    void register_shouldThrowConflict_whenEmailExists() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("john@example.com");
        request.setPassword("password123");
        request.setFullName("John Doe");

        when(userRepository.existsByEmailIgnoreCase("john@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Email already exists");
    }

    @Test
    void login_shouldAuthenticateAndReturnToken() {
        LoginRequest request = new LoginRequest();
        request.setEmail("john@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));
        when(jwtService.generateToken("john@example.com", Role.USER)).thenReturn("jwt-token");
        when(jwtService.generateRefreshToken()).thenReturn("refresh-token");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
        when(jwtService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);

        AuthTokenResponse response = authService.login(request);

        verify(authenticationManager)
                .authenticate(argThat(authentication -> authentication instanceof UsernamePasswordAuthenticationToken
                        && "john@example.com".equals(authentication.getPrincipal())
                        && "password123".equals(authentication.getCredentials())));
        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        assertThat(response.refreshExpiresIn()).isEqualTo(604800L);
        assertThat(response.email()).isEqualTo("john@example.com");
        verify(refreshTokenService).store(7L, "refresh-token", Duration.ofSeconds(604800L));
        verify(loginAttemptService).clearAttempts("john@example.com");
    }

    @Test
    void login_shouldThrowBruteForceBlocked_whenEmailIsLocked() {
        LoginRequest request = new LoginRequest();
        request.setEmail("john@example.com");
        request.setPassword("password123");

        when(loginAttemptService.isBlocked("john@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BruteForceBlockedException.class)
                .hasMessage("Too many failed login attempts. Try again in 30 minutes.");

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void login_shouldThrowBadCredentials_whenUserIsBanned() {
        LoginRequest request = new LoginRequest();
        request.setEmail("john@example.com");
        request.setPassword("password123");

        User bannedUser = new User();
        bannedUser.setId(77L);
        bannedUser.setEmail("john@example.com");
        bannedUser.setPassword("encoded-password");
        bannedUser.setRole(Role.USER);
        bannedUser.setStatus(false);

        when(loginAttemptService.isBlocked("john@example.com")).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(bannedUser));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Account is locked");

        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    void login_shouldThrowBadCredentials_whenUserMissingAfterAuth() {
        LoginRequest request = new LoginRequest();
        request.setEmail("missing@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");

        verify(loginAttemptService).recordFailedAttempt("missing@example.com");
    }

    @Test
    void logout_shouldBlacklistTokenWithExpiration() {
        Instant expiresAt = Instant.parse("2026-03-14T00:00:00Z");
        when(jwtService.extractExpiration("jwt-token")).thenReturn(expiresAt);
        when(jwtService.extractSubject("jwt-token")).thenReturn("john@example.com");
        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));

        authService.logout("Bearer jwt-token");

        verify(tokenBlacklistService).blacklist("jwt-token", expiresAt);
        verify(refreshTokenService).revokeByUserId(7L);
    }

    @Test
    void logout_shouldStillBlacklist_whenUserNotFoundBySubject() {
        Instant expiresAt = Instant.parse("2026-03-14T00:00:00Z");
        when(jwtService.extractExpiration("jwt-token")).thenReturn(expiresAt);
        when(jwtService.extractSubject("jwt-token")).thenReturn("missing@example.com");
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        authService.logout("Bearer jwt-token");

        verify(tokenBlacklistService).blacklist("jwt-token", expiresAt);
        verify(refreshTokenService, never()).revokeByUserId(any());
    }

    @Test
    void refresh_shouldRotateRefreshTokenAndReturnNewPair() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("refresh-token");

        when(refreshTokenService.resolveUserId("refresh-token")).thenReturn(Optional.of(7L));
        when(refreshTokenService.findByUserId(7L)).thenReturn(Optional.of("refresh-token"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser));
        when(jwtService.generateToken("john@example.com", Role.USER)).thenReturn("new-access");
        when(jwtService.generateRefreshToken()).thenReturn("new-refresh");
        when(jwtService.getExpirationSeconds()).thenReturn(3600L);
        when(jwtService.getRefreshTokenExpirationSeconds()).thenReturn(604800L);

        AuthTokenResponse response = authService.refresh(request);

        verify(refreshTokenService).revokeByUserId(7L);
        verify(refreshTokenService).store(7L, "new-refresh", Duration.ofSeconds(604800L));
        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void logout_shouldThrowBadCredentials_whenHeaderInvalid() {
        assertThatThrownBy(() -> authService.logout("Invalid token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authorization header is missing or invalid");
    }

    @Test
    void getMyProfile_shouldReturnCachedProfile_whenRedisHasValue() {
        UserProfileResponse cachedProfile = UserProfileResponse.builder()
                .id(7L)
                .email("john@example.com")
                .fullName("John Doe")
                .role(Role.USER)
                .premium(false)
                .build();

        when(userProfileCacheService.findByEmail("john@example.com")).thenReturn(Optional.of(cachedProfile));

        UserProfileResponse response = authService.getMyProfile("John@Example.com");

        assertThat(response).isEqualTo(cachedProfile);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(userProfileCacheService, never()).find(any());
        verify(userProfileCacheService, never()).store(any(), any());
    }

    @Test
    void getMyProfile_shouldLoadFromDbAndCache_whenRedisMiss() {
        when(userProfileCacheService.findByEmail("john@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));
        when(userProfileCacheService.find(7L)).thenReturn(Optional.empty());

        UserProfileResponse response = authService.getMyProfile("john@example.com");

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.email()).isEqualTo("john@example.com");
        assertThat(response.fullName()).isEqualTo("John Doe");
        assertThat(response.role()).isEqualTo(Role.USER);
        assertThat(response.premium()).isFalse();
        verify(userProfileCacheService).store(7L, response);
    }

    @Test
    void upsertGoogleUser_shouldEvictProfileCache_whenExistingUserIsUpdated() {
        User duplicatedNameUser = new User();
        duplicatedNameUser.setId(9L);
        duplicatedNameUser.setEmail("google@example.com");
        duplicatedNameUser.setFullName("Nguyen Van A Nguyen Van A");
        duplicatedNameUser.setRole(Role.USER);
        duplicatedNameUser.setStatus(true);

        when(userRepository.findByEmailIgnoreCase("google@example.com")).thenReturn(Optional.of(duplicatedNameUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User savedUser = authService.upsertGoogleUser("google@example.com", "Nguyen Van A");

        assertThat(savedUser.getFullName()).isEqualTo("Nguyen Van A");
        verify(userProfileCacheService).evict(9L, "google@example.com");
    }

    @Test
    void forgotPassword_shouldStoreOtpInRedisAndPublishRabbitMessage() {
        when(forgotPasswordProperties.getOtpTtlSeconds()).thenReturn(300L);
        when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
        when(notificationRabbitProperties.getEmailOtpRoutingKey()).thenReturn("notification.send.email.otp");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        authService.forgotPassword("User@Gmail.com");

        verify(otpRateLimitService).checkForgotAllowed("user@gmail.com");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations, times(2)).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        int otpKeyIndex = keyCaptor.getAllValues().indexOf(
                keyCaptor.getAllValues().stream().filter(key -> key.startsWith("reset_password:otp:"))
                        .findFirst()
                        .orElseThrow());
        int emailKeyIndex = keyCaptor.getAllValues().indexOf(
                keyCaptor.getAllValues().stream().filter(key -> key.startsWith("reset_password:email:"))
                        .findFirst()
                        .orElseThrow());

        String otpKey = keyCaptor.getAllValues().get(otpKeyIndex);
        String emailKey = keyCaptor.getAllValues().get(emailKeyIndex);
        String otpValue = valueCaptor.getAllValues().get(emailKeyIndex);

        assertThat(otpKey).startsWith("reset_password:otp:");
        assertThat(otpKey.substring("reset_password:otp:".length())).matches("\\d{6}");
        assertThat(emailKey).isEqualTo("reset_password:email:user@gmail.com");
        assertThat(otpValue).matches("\\d{6}");
        assertThat(ttlCaptor.getAllValues()).containsOnly(Duration.ofSeconds(300));

        ArgumentCaptor<ForgotPasswordOtpMessage> messageCaptor = ArgumentCaptor
                .forClass(ForgotPasswordOtpMessage.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("notification.events"),
                org.mockito.ArgumentMatchers.eq("notification.send.email.otp"),
                messageCaptor.capture());

        assertThat(messageCaptor.getValue().email()).isEqualTo("user@gmail.com");
        assertThat(messageCaptor.getValue().otp()).matches("\\d{6}");
    }

    @Test
    void forgotPassword_shouldNotThrow_whenRabbitPublishFails() {
        when(forgotPasswordProperties.getOtpTtlSeconds()).thenReturn(300L);
        when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
        when(notificationRabbitProperties.getEmailOtpRoutingKey()).thenReturn("notification.send.email.otp");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        doThrow(new AmqpAuthenticationException(new RuntimeException("ACCESS_REFUSED")))
                .when(rabbitTemplate)
                .convertAndSend(any(String.class), any(String.class), any(ForgotPasswordOtpMessage.class));

        assertThatCode(() -> authService.forgotPassword("User@Gmail.com"))
                .doesNotThrowAnyException();

        verify(otpRateLimitService).checkForgotAllowed("user@gmail.com");
        verify(valueOperations, times(2)).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void forgotPassword_shouldThrowBruteForceBlocked_whenRateLimited() {
        doThrow(new BruteForceBlockedException("Too many forgot-password requests"))
                .when(otpRateLimitService)
                .checkForgotAllowed("user@gmail.com");

        assertThatThrownBy(() -> authService.forgotPassword("User@Gmail.com"))
                .isInstanceOf(BruteForceBlockedException.class)
                .hasMessageContaining("Too many forgot-password requests");

        verify(stringRedisTemplate, never()).opsForValue();
        verify(rabbitTemplate, never()).convertAndSend(
                any(String.class),
                any(String.class),
                any(ForgotPasswordOtpMessage.class));
    }

    @Test
    void verifyForgotPasswordOtp_shouldReturnResetToken_whenOtpValid() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("reset_password:email:user@gmail.com")).thenReturn("123456");
        when(valueOperations.get("reset_password:otp:123456")).thenReturn("user@gmail.com");

        String resetToken = authService.verifyForgotPasswordOtp("User@gmail.com", "123456");

        verify(otpRateLimitService).checkVerifyAllowed("user@gmail.com");
        assertThat(resetToken).isNotBlank();
        verify(valueOperations).set(
                argThat(key -> key.startsWith("reset_password:token:")),
                org.mockito.ArgumentMatchers.eq("user@gmail.com"),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(10)));
    }

    @Test
    void resetPassword_shouldUpdatePasswordAndInvalidateToken_whenTokenValid() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("reset_password:token:reset-token-123")).thenReturn("john@example.com");
        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.encode("new-password-123")).thenReturn("encoded-new-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.resetPassword("reset-token-123", "new-password-123");

        verify(userRepository).save(argThat(user -> "encoded-new-password".equals(user.getPassword())));
        verify(userProfileCacheService).evict(7L, "john@example.com");
        verify(stringRedisTemplate).delete("reset_password:token:reset-token-123");
        verify(stringRedisTemplate).delete("reset_password:email:john@example.com");
    }

    @Test
    void resendForgotPasswordOtp_shouldCheckResendRateLimit() {
        when(forgotPasswordProperties.getOtpTtlSeconds()).thenReturn(300L);
        when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
        when(notificationRabbitProperties.getEmailOtpRoutingKey()).thenReturn("notification.send.email.otp");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        authService.resendForgotPasswordOtp("User@Gmail.com");

        verify(otpRateLimitService).checkResendAllowed("user@gmail.com");
        verify(valueOperations, times(2)).set(any(String.class), any(String.class), any(Duration.class));
    }

    @Test
    void updateMyProfile_shouldUpdateFullNameAndEvictCache() {
        UpdateMyProfileRequest request = new UpdateMyProfileRequest("  John Wick  ", null, null, null, null, null,
                null);
        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = authService.updateMyProfile("john@example.com", request);

        assertThat(response.fullName()).isEqualTo("John Wick");
        verify(userRepository).save(argThat(user -> "John Wick".equals(user.getFullName())));
        verify(userProfileCacheService).evict(7L, "john@example.com");
    }

    @Test
    void updateMyProfile_shouldUpdatePassword_whenCurrentPasswordMatches() {
        UpdateMyProfileRequest request = new UpdateMyProfileRequest(null, null, null, null, null, "old-password-123",
                "new-password-123");
        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("old-password-123", "encoded-password")).thenReturn(true);
        when(passwordEncoder.encode("new-password-123")).thenReturn("encoded-new-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = authService.updateMyProfile("john@example.com", request);

        assertThat(response.email()).isEqualTo("john@example.com");
        verify(userRepository).save(argThat(user -> "encoded-new-password".equals(user.getPassword())));
        verify(userProfileCacheService).evict(7L, "john@example.com");
    }

    @Test
    void updateMyProfile_shouldThrowIllegalArgument_whenPasswordFieldsIncomplete() {
        UpdateMyProfileRequest request = new UpdateMyProfileRequest(null, null, null, null, null, null,
                "new-password-123");
        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> authService.updateMyProfile("john@example.com", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Both currentPassword and newPassword are required");

        verify(userRepository, never()).save(any());
    }

    @Test
    void uploadMyAvatar_shouldUploadToStorageAndUpdateProfile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                "sample-image-content".getBytes());

        when(userRepository.findByEmailIgnoreCase("john@example.com")).thenReturn(Optional.of(existingUser));
        when(avatarStorageService.uploadAvatar(7L, file))
                .thenReturn("http://localhost:9000/users/avatars/user-7/avatar.png");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileResponse response = authService.uploadMyAvatar("john@example.com", file);

        assertThat(response.avatarUrl()).isEqualTo("http://localhost:9000/users/avatars/user-7/avatar.png");
        verify(avatarStorageService).uploadAvatar(7L, file);
        verify(userRepository).save(
                argThat(user -> "http://localhost:9000/users/avatars/user-7/avatar.png".equals(user.getAvatarUrl())));
        verify(userProfileCacheService).evict(7L, "john@example.com");
    }
}
