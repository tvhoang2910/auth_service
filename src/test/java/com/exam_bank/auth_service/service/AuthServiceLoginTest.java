package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.AuthEmailVerificationProperties;
import com.exam_bank.auth_service.config.properties.AuthForgotPasswordProperties;
import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.request.LoginRequest;
import com.exam_bank.auth_service.dto.request.RegisterRequest;
import com.exam_bank.auth_service.dto.response.RegisterResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;
import com.exam_bank.auth_service.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService login guard tests")
class AuthServiceLoginTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

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
    private AuthEmailVerificationProperties emailVerificationProperties;

    @Mock
    private NotificationRabbitProperties notificationRabbitProperties;

    @Mock
    private OtpRateLimitService otpRateLimitService;

    @Mock
    private SecurityAuditService securityAuditService;

    @Mock
    private AvatarStorageService avatarStorageService;

    @Mock
    private AuthUserProfileEventPublisher authUserProfileEventPublisher;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("register ignores caller supplied role and stores public users as USER")
    void registerIgnoresCallerSuppliedRole() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("  NEW.USER@example.com ");
        request.setPassword("example-password");
        request.setFullName("New User");
        request.setRole(Role.ADMIN);

        when(userRepository.existsByEmailIgnoreCase("new.user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("example-password")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });
        when(userRepository.findByEmailIgnoreCase("new.user@example.com"))
                .thenAnswer(invocation -> {
                    User saved = new User();
                    saved.setId(42L);
                    saved.setEmail("new.user@example.com");
                    saved.setFullName("New User");
                    saved.setRole(Role.USER);
                    saved.setEmailVerified(false);
                    return Optional.of(saved);
                });
        when(emailVerificationProperties.getOtpTtlSeconds()).thenReturn(300L);
        when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
        when(notificationRabbitProperties.getEmailOtpRoutingKey()).thenReturn("notification.send.email.otp");

        RegisterResponse response = authService.register(request);

        assertThat(response.role()).isEqualTo(Role.USER);
        verify(authUserProfileEventPublisher).publish(any(User.class), any(Boolean.class));
    }

    @Test
    @DisplayName("login rejects account locked before authentication attempt")
    void loginRejectsLockedAccountBeforeAuthenticationAttempt() {
        String normalizedEmail = "locked.user@example.com";
        LoginRequest request = new LoginRequest();
        request.setEmail("  LOCKED.USER@example.com ");
        request.setPassword("example-password");

        User lockedUser = new User();
        lockedUser.setEmail(normalizedEmail);
        lockedUser.setStatus(false);
        lockedUser.setEmailVerified(true);

        when(loginAttemptService.isBlocked(normalizedEmail)).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase(normalizedEmail)).thenReturn(Optional.of(lockedUser));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Account is locked");

        verify(authenticationManager, never()).authenticate(any());
        verify(securityAuditService).failure("LOGIN", normalizedEmail, "user-account-locked");
    }

    @Test
    @DisplayName("login rejects unverified email before authentication attempt")
    void loginRejectsUnverifiedEmailBeforeAuthenticationAttempt() {
        String normalizedEmail = "unverified.user@example.com";
        LoginRequest request = new LoginRequest();
        request.setEmail("  UNVERIFIED.USER@example.com ");
        request.setPassword("example-password");

        User unverifiedUser = new User();
        unverifiedUser.setEmail(normalizedEmail);
        unverifiedUser.setStatus(true);
        unverifiedUser.setEmailVerified(false);

        when(loginAttemptService.isBlocked(normalizedEmail)).thenReturn(false);
        when(userRepository.findByEmailIgnoreCase(normalizedEmail)).thenReturn(Optional.of(unverifiedUser));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Email is not verified");

        verify(authenticationManager, never()).authenticate(any());
        verify(securityAuditService).failure("LOGIN", normalizedEmail, "email-not-verified");
    }
}
