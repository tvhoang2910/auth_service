package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.request.RegisterRequest;
import com.exam_bank.auth_service.dto.request.LoginRequest;
import com.exam_bank.auth_service.dto.request.RefreshTokenRequest;
import com.exam_bank.auth_service.dto.request.UpdateMyProfileRequest;
import com.exam_bank.auth_service.dto.message.EmailOtpMessage;
import com.exam_bank.auth_service.dto.response.AuthTokenResponse;
import com.exam_bank.auth_service.dto.response.RegisterResponse;
import com.exam_bank.auth_service.dto.response.UserProfileResponse;
import com.exam_bank.auth_service.config.properties.AuthEmailVerificationProperties;
import com.exam_bank.auth_service.config.properties.AuthForgotPasswordProperties;
import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.exception.BruteForceBlockedException;
import com.exam_bank.auth_service.exception.ConflictException;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.util.StringUtils.hasText;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private static final String AUDIT_LOGIN = "LOGIN";
    private static final String AUDIT_LOGOUT = "LOGOUT";
    private static final String AUDIT_REFRESH = "REFRESH_TOKEN";
    private static final String AUDIT_FORGOT_PASSWORD = "FORGOT_PASSWORD";
    private static final String AUDIT_VERIFY_OTP = "VERIFY_RESET_OTP";
    private static final String AUDIT_EMAIL_VERIFICATION = "VERIFY_REGISTER_EMAIL";
    private static final String AUDIT_RESET_PASSWORD = "RESET_PASSWORD";
    private static final String AUDIT_UPDATE_PROFILE = "UPDATE_PROFILE";
    private static final String AUDIT_UPLOAD_AVATAR = "UPLOAD_AVATAR";

    private static final String RESET_PASSWORD_OTP_KEY_PREFIX = "reset_password:otp:";
    private static final String RESET_PASSWORD_EMAIL_KEY_PREFIX = "reset_password:email:";
    private static final String RESET_PASSWORD_TOKEN_KEY_PREFIX = "reset_password:token:";
    private static final String REGISTER_EMAIL_VERIFICATION_OTP_KEY_PREFIX = "register_verify:otp:";
    private static final String REGISTER_EMAIL_VERIFICATION_EMAIL_KEY_PREFIX = "register_verify:email:";
    private static final Duration RESET_PASSWORD_TOKEN_TTL = Duration.ofMinutes(10);
    private static final String OTP_PURPOSE_FORGOT_PASSWORD = "FORGOT_PASSWORD";
    private static final String OTP_PURPOSE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";
    private static final String USER_NOT_FOUND_MESSAGE = "User not found";

    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final UserProfileCacheService userProfileCacheService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final AuthForgotPasswordProperties forgotPasswordProperties;
    private final AuthEmailVerificationProperties emailVerificationProperties;
    private final NotificationRabbitProperties notificationRabbitProperties;
    private final OtpRateLimitService otpRateLimitService;
    private final SecurityAuditService securityAuditService;
    private final AvatarStorageService avatarStorageService;
    private final AuthUserProfileEventPublisher authUserProfileEventPublisher;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Email already exists");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPassword(hasText(request.getPassword()) ? passwordEncoder.encode(request.getPassword()) : null);
        user.setFullName(request.getFullName().trim());
        user.setRole(request.getRole() == null ? Role.USER : request.getRole());
        user.setEmailVerified(false);
        user.setAvatarUrl(null);
        user.setPhoneNumber(null);
        user.setSchool(null);
        user.setSubject(null);

        User savedUser = userRepository.save(user);
        issueRegisterVerificationOtp(savedUser.getEmail());
        evictUserProfileCache(savedUser.getId(), savedUser.getEmail());
        authUserProfileEventPublisher.publish(savedUser, false);
        return RegisterResponse.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .fullName(savedUser.getFullName())
                .role(savedUser.getRole())
                .createdAt(savedUser.getCreatedAt())
                .build();
    }

    @Transactional
    public AuthTokenResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        if (loginAttemptService.isBlocked(normalizedEmail)) {
            securityAuditService.failure(AUDIT_LOGIN, normalizedEmail, "blocked-by-login-attempt-limiter");
            throw new BruteForceBlockedException("Too many failed login attempts. Try again in 30 minutes.");
        }

        Optional<User> existingUser = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser.isPresent() && !existingUser.get().isStatus()) {
            securityAuditService.failure(AUDIT_LOGIN, normalizedEmail, "user-account-locked");
            throw new BadCredentialsException("Account is locked");
        }
        if (existingUser.isPresent() && !existingUser.get().isEmailVerified()) {
            securityAuditService.failure(AUDIT_LOGIN, normalizedEmail, "email-not-verified");
            throw new BadCredentialsException("Email is not verified");
        }

        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalizedEmail, request.getPassword()));
        } catch (BadCredentialsException exception) {
            loginAttemptService.recordFailedAttempt(normalizedEmail);
            securityAuditService.failure(AUDIT_LOGIN, normalizedEmail, "invalid-credentials");

            throw exception;
        }

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> {
                    loginAttemptService.recordFailedAttempt(normalizedEmail);
                    securityAuditService.failure(AUDIT_LOGIN, normalizedEmail,
                            "user-not-found-after-authentication");
                    return new BadCredentialsException("Invalid credentials");
                });

        loginAttemptService.clearAttempts(normalizedEmail);
        securityAuditService.success(AUDIT_LOGIN, normalizedEmail, "token-pair-issued");

        return issueTokenPair(user);
    }

    @Transactional
    public User upsertGoogleUser(String email, String fullName) {
        if (!hasText(email)) {
            throw new BadCredentialsException("Google account email is missing");
        }

        String normalizedEmail = email.trim().toLowerCase();
        String normalizedGoogleName = normalizeGoogleFullName(fullName, normalizedEmail);

        return userRepository.findByEmailIgnoreCase(normalizedEmail)
                .map(existingUser -> {
                    String existingName = normalizeWhitespace(existingUser.getFullName());
                    if (isDuplicatedAdjacentName(existingName)) {
                        existingUser.setFullName(extractFirstHalf(existingName));
                        existingUser.setEmailVerified(true);
                        User savedUser = userRepository.save(existingUser);
                        evictUserProfileCache(savedUser.getId(), savedUser.getEmail());
                        authUserProfileEventPublisher.publish(savedUser, isPremiumActive(savedUser.getId()));
                        return savedUser;
                    }
                    if (!existingUser.isEmailVerified()) {
                        existingUser.setEmailVerified(true);
                        User savedUser = userRepository.save(existingUser);
                        evictUserProfileCache(savedUser.getId(), savedUser.getEmail());
                        authUserProfileEventPublisher.publish(savedUser, isPremiumActive(savedUser.getId()));
                        return savedUser;
                    }
                    return existingUser;
                })
                .orElseGet(() -> {
                    User user = new User();
                    user.setEmail(normalizedEmail);
                    user.setFullName(normalizedGoogleName);
                    user.setRole(Role.USER);
                    user.setPassword(null);
                    user.setStatus(true);
                    user.setEmailVerified(true);
                    User savedUser = userRepository.save(user);
                    evictUserProfileCache(savedUser.getId(), savedUser.getEmail());
                    authUserProfileEventPublisher.publish(savedUser, false);
                    return savedUser;
                });
    }

    public void resendRegisterVerificationOtp(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        issueRegisterVerificationOtp(normalizedEmail);
        securityAuditService.success(AUDIT_EMAIL_VERIFICATION, normalizedEmail, "otp-resent");
    }

    @Transactional
    public void verifyRegisterEmailOtp(String email, String otp) {
        String normalizedEmail = email.trim().toLowerCase();
        String normalizedOtp = otp.trim();

        String storedOtp = stringRedisTemplate.opsForValue().get(registerVerificationEmailOtpKey(normalizedEmail));
        if (!hasText(storedOtp) || !storedOtp.equals(normalizedOtp)) {
            securityAuditService.failure(AUDIT_EMAIL_VERIFICATION, normalizedEmail, "otp-mismatch-or-expired");
            throw new IllegalArgumentException("OTP is invalid or expired");
        }

        String storedEmail = stringRedisTemplate.opsForValue().get(registerVerificationOtpKey(normalizedOtp));
        if (!hasText(storedEmail) || !normalizedEmail.equalsIgnoreCase(storedEmail)) {
            securityAuditService.failure(AUDIT_EMAIL_VERIFICATION, normalizedEmail, "otp-email-binding-invalid");
            throw new IllegalArgumentException("OTP is invalid or expired");
        }

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND_MESSAGE));
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            User savedUser = userRepository.save(user);
            evictUserProfileCache(savedUser.getId(), savedUser.getEmail());
        }

        clearRegisterVerificationOtp(normalizedEmail, normalizedOtp);
        securityAuditService.success(AUDIT_EMAIL_VERIFICATION, normalizedEmail, "email-verified");
    }

    private String normalizeGoogleFullName(String fullName, String fallbackValue) {
        String normalized = normalizeWhitespace(hasText(fullName) ? fullName : fallbackValue);
        if (isDuplicatedAdjacentName(normalized)) {
            return extractFirstHalf(normalized);
        }
        return normalized;
    }

    private boolean isDuplicatedAdjacentName(String value) {
        if (!hasText(value)) {
            return false;
        }
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length < 2 || tokens.length % 2 != 0) {
            return false;
        }

        int half = tokens.length / 2;
        for (int i = 0; i < half; i++) {
            if (!tokens[i].equalsIgnoreCase(tokens[i + half])) {
                return false;
            }
        }
        return true;
    }

    private String extractFirstHalf(String value) {
        String[] tokens = value.trim().split("\\s+");
        int half = tokens.length / 2;
        return String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, half));
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public AuthTokenResponse issueToken(User user) {
        return issueTokenPair(user);
    }

    public AuthTokenResponse issueTokenByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BadCredentialsException(USER_NOT_FOUND_MESSAGE);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException(USER_NOT_FOUND_MESSAGE));
        if (!user.isStatus()) {
            throw new BadCredentialsException("Account is locked");
        }
        return issueTokenPair(user);
    }

    public void forgotPassword(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        otpRateLimitService.checkForgotAllowed(normalizedEmail);

        issueForgotPasswordOtp(normalizedEmail);
    }

    public void resendForgotPasswordOtp(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        otpRateLimitService.checkResendAllowed(normalizedEmail);

        issueForgotPasswordOtp(normalizedEmail);
        securityAuditService.success(AUDIT_FORGOT_PASSWORD, normalizedEmail, "otp-resent");
    }

    private void issueForgotPasswordOtp(String normalizedEmail) {
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        long ttlSeconds = forgotPasswordProperties.getOtpTtlSeconds();

        String otpKey = otpKey(otp);
        String emailKey = emailOtpKey(normalizedEmail);
        executeOtpAtomicSet(otpKey, normalizedEmail, emailKey, otp, ttlSeconds);

        publishOtpMessage(normalizedEmail, otp, OTP_PURPOSE_FORGOT_PASSWORD, AUDIT_FORGOT_PASSWORD,
                "otp-issued-and-published", "otp-issued-but-rabbit-publish-failed");
    }

    private void issueRegisterVerificationOtp(String normalizedEmail) {
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (user == null || user.isEmailVerified()) {
            return;
        }

        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        long ttlSeconds = emailVerificationProperties.getOtpTtlSeconds();

        String otpKey = registerVerificationOtpKey(otp);
        String emailKey = registerVerificationEmailOtpKey(normalizedEmail);
        executeOtpAtomicSet(otpKey, normalizedEmail, emailKey, otp, ttlSeconds);

        publishOtpMessage(normalizedEmail, otp, OTP_PURPOSE_EMAIL_VERIFICATION, AUDIT_EMAIL_VERIFICATION,
                "otp-issued-and-published", "otp-issued-but-rabbit-publish-failed");
    }

    /**
     * Atomically sets both OTP→email and email→OTP keys using a Lua script
     * to guarantee consistency even if Redis fails mid-operation.
     */
    private void executeOtpAtomicSet(String otpKey, String otpValue,
            String emailKey, String emailValue, long ttlSeconds) {
        String luaScript = "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3]) " +
                "redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3]) " +
                "return 1";
        RedisScript<Long> script = RedisScript.of(luaScript, Long.class);
        stringRedisTemplate.execute(script,
                List.of(otpKey, emailKey),
                otpValue, emailValue, String.valueOf(ttlSeconds));
    }

    private void publishOtpMessage(
            String normalizedEmail,
            String otp,
            String purpose,
            String auditAction,
            String successDetail,
            String failureDetail) {
        EmailOtpMessage message = new EmailOtpMessage(normalizedEmail, otp, purpose);
        try {
            rabbitTemplate.convertAndSend(
                    notificationRabbitProperties.getExchange(),
                    notificationRabbitProperties.getEmailOtpRoutingKey(),
                    message);
            securityAuditService.success(auditAction, normalizedEmail, successDetail);
        } catch (AmqpException exception) {
            log.error("Failed to publish {} OTP message for email {}", purpose, normalizedEmail, exception);
            securityAuditService.failure(auditAction, normalizedEmail, failureDetail);
        }
    }

    private void clearRegisterVerificationOtp(String email, String otp) {
        stringRedisTemplate.delete(registerVerificationEmailOtpKey(email));
        stringRedisTemplate.delete(registerVerificationOtpKey(otp));
    }

    @Transactional
    public String verifyForgotPasswordOtp(String email, String otp) {
        String normalizedEmail = email.trim().toLowerCase();
        String normalizedOtp = otp.trim();
        otpRateLimitService.checkVerifyAllowed(normalizedEmail);

        String storedOtp = stringRedisTemplate.opsForValue().get(emailOtpKey(normalizedEmail));
        if (!hasText(storedOtp) || !storedOtp.equals(normalizedOtp)) {
            securityAuditService.failure(AUDIT_VERIFY_OTP, normalizedEmail, "otp-mismatch-or-expired");
            throw new IllegalArgumentException("OTP is invalid or expired");
        }

        String storedEmail = stringRedisTemplate.opsForValue().get(otpKey(normalizedOtp));
        if (!hasText(storedEmail) || !normalizedEmail.equalsIgnoreCase(storedEmail)) {
            securityAuditService.failure(AUDIT_VERIFY_OTP, normalizedEmail, "otp-email-binding-invalid");
            throw new IllegalArgumentException("OTP is invalid or expired");
        }

        String resetToken = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(resetPasswordTokenKey(resetToken), normalizedEmail,
                RESET_PASSWORD_TOKEN_TTL);
        securityAuditService.success(AUDIT_VERIFY_OTP, normalizedEmail, "otp-verified-reset-token-issued");
        return resetToken;
    }

    @Transactional
    public void resetPassword(String resetToken, String newPassword) {
        String normalizedToken = resetToken == null ? "" : resetToken.trim();
        if (!hasText(normalizedToken)) {
            securityAuditService.failure(AUDIT_RESET_PASSWORD, null, "missing-reset-token");
            throw new IllegalArgumentException("Reset token is missing");
        }

        String email = stringRedisTemplate.opsForValue().get(resetPasswordTokenKey(normalizedToken));
        if (!hasText(email)) {
            securityAuditService.failure(AUDIT_RESET_PASSWORD, null, "invalid-or-expired-reset-token");
            throw new IllegalArgumentException("Reset token is invalid or expired");
        }

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new BadCredentialsException(USER_NOT_FOUND_MESSAGE));

        user.setPassword(passwordEncoder.encode(newPassword));
        User savedUser = userRepository.save(user);
        evictUserProfileCache(savedUser.getId(), savedUser.getEmail());

        stringRedisTemplate.delete(resetPasswordTokenKey(normalizedToken));
        stringRedisTemplate.delete(emailOtpKey(email));
        securityAuditService.success(AUDIT_RESET_PASSWORD, email, "password-updated");
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(String email) {
        String normalizedEmail = email.trim().toLowerCase();

        Optional<UserProfileResponse> cachedByEmail = userProfileCacheService.findByEmail(normalizedEmail);
        if (cachedByEmail.isPresent()) {
            return ensurePublicAvatarUrl(cachedByEmail.get());
        }

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException(USER_NOT_FOUND_MESSAGE));

        return userProfileCacheService.find(user.getId())
                .map(this::ensurePublicAvatarUrl)
                .orElseGet(() -> {
                    UserProfileResponse profile = mapToUserProfile(user);
                    userProfileCacheService.store(user.getId(), profile);
                    return profile;
                });
    }

    @Transactional(readOnly = true)
    public AvatarStorageService.AvatarFileContent getUserAvatar(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User id is invalid");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(USER_NOT_FOUND_MESSAGE));

        if (!hasText(user.getAvatarUrl())) {
            throw new IllegalArgumentException("User avatar not found");
        }

        return avatarStorageService.downloadAvatarByUrl(user.getAvatarUrl());
    }

    @Transactional
    public UserProfileResponse updateMyProfile(String email, UpdateMyProfileRequest request) {
        String normalizedEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException(USER_NOT_FOUND_MESSAGE));

        ProfileUpdateContext updateContext = buildProfileUpdateContext(request);
        validateProfileUpdateRequest(user, normalizedEmail, updateContext);

        boolean changed = applyProfileFieldUpdates(user, updateContext);
        changed = applyPasswordUpdateIfNeeded(user, request, normalizedEmail, updateContext, changed);

        User savedUser = changed ? userRepository.save(user) : user;
        if (changed) {
            evictUserProfileCache(savedUser.getId(), savedUser.getEmail());
            if (updateContext.hasNameChange()) {
                authUserProfileEventPublisher.publish(savedUser, isPremiumActive(savedUser.getId()));
            }
        }

        String detail = determineProfileAuditDetail(updateContext.hasNameChange(), updateContext.hasCurrentPassword());
        securityAuditService.success(AUDIT_UPDATE_PROFILE, normalizedEmail, detail);
        return mapToUserProfile(savedUser);
    }

    private ProfileUpdateContext buildProfileUpdateContext(UpdateMyProfileRequest request) {
        String normalizedName = normalizeWhitespace(request.fullName());
        boolean hasNameChange = hasText(normalizedName);
        boolean hasAvatarFieldProvided = request.avatarUrl() != null;
        boolean hasPhoneFieldProvided = request.phoneNumber() != null;
        boolean hasSchoolFieldProvided = request.school() != null;
        boolean hasSubjectFieldProvided = request.subject() != null;
        String normalizedAvatarUrl = normalizeOptionalText(request.avatarUrl());
        String normalizedPhone = normalizeOptionalText(request.phoneNumber());
        String normalizedSchool = normalizeOptionalText(request.school());
        String normalizedSubject = normalizeOptionalText(request.subject());
        boolean hasCurrentPassword = hasText(request.currentPassword());
        boolean hasNewPassword = hasText(request.newPassword());
        boolean hasProfileFieldChange = hasAvatarFieldProvided
                || hasPhoneFieldProvided
                || hasSchoolFieldProvided
                || hasSubjectFieldProvided;

        return new ProfileUpdateContext(
                normalizedName,
                hasNameChange,
                hasAvatarFieldProvided,
                hasPhoneFieldProvided,
                hasSchoolFieldProvided,
                hasSubjectFieldProvided,
                normalizedAvatarUrl,
                normalizedPhone,
                normalizedSchool,
                normalizedSubject,
                hasCurrentPassword,
                hasNewPassword,
                hasProfileFieldChange);
    }

    private void validateProfileUpdateRequest(
            User user,
            String normalizedEmail,
            ProfileUpdateContext updateContext) {
        if (!updateContext.hasNameChange()
                && !updateContext.hasProfileFieldChange()
                && !updateContext.hasCurrentPassword()
                && !updateContext.hasNewPassword()) {
            securityAuditService.failure(AUDIT_UPDATE_PROFILE, normalizedEmail, "empty-update-payload");
            throw new IllegalArgumentException("At least one profile field or password change is required");
        }

        if (updateContext.hasCurrentPassword() != updateContext.hasNewPassword()) {
            securityAuditService.failure(AUDIT_UPDATE_PROFILE, normalizedEmail, "password-fields-incomplete");
            throw new IllegalArgumentException("Both currentPassword and newPassword are required");
        }

        if (updateContext.hasCurrentPassword() && !hasText(user.getPassword())) {
            securityAuditService.failure(AUDIT_UPDATE_PROFILE, normalizedEmail, "oauth-user-no-local-password");
            throw new IllegalArgumentException("Password update is not available for this account");
        }

    }

    private boolean applyProfileFieldUpdates(User user, ProfileUpdateContext updateContext) {
        boolean changed = false;

        if (updateContext.hasNameChange() && !updateContext.normalizedName().equals(user.getFullName())) {
            user.setFullName(updateContext.normalizedName());
            changed = true;
        }

        if (updateContext.hasAvatarFieldProvided()
                && !equalsNullable(updateContext.normalizedAvatarUrl(), user.getAvatarUrl())) {
            user.setAvatarUrl(updateContext.normalizedAvatarUrl());
            changed = true;
        }

        if (updateContext.hasPhoneFieldProvided()
                && !equalsNullable(updateContext.normalizedPhone(), user.getPhoneNumber())) {
            user.setPhoneNumber(updateContext.normalizedPhone());
            changed = true;
        }

        if (updateContext.hasSchoolFieldProvided()
                && !equalsNullable(updateContext.normalizedSchool(), user.getSchool())) {
            user.setSchool(updateContext.normalizedSchool());
            changed = true;
        }

        if (updateContext.hasSubjectFieldProvided()
                && !equalsNullable(updateContext.normalizedSubject(), user.getSubject())) {
            user.setSubject(updateContext.normalizedSubject());
            changed = true;
        }

        return changed;
    }

    private boolean applyPasswordUpdateIfNeeded(
            User user,
            UpdateMyProfileRequest request,
            String normalizedEmail,
            ProfileUpdateContext updateContext,
            boolean changed) {
        if (!updateContext.hasCurrentPassword()) {
            return changed;
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            securityAuditService.failure(AUDIT_UPDATE_PROFILE, normalizedEmail, "current-password-mismatch");
            throw new BadCredentialsException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        return true;
    }

    private String determineProfileAuditDetail(boolean hasNameChange, boolean hasPasswordChange) {
        if (hasNameChange && hasPasswordChange) {
            return "full-name-and-password-updated";
        }
        if (hasNameChange) {
            return "full-name-updated";
        }
        return "password-updated";
    }

    private record ProfileUpdateContext(
            String normalizedName,
            boolean hasNameChange,
            boolean hasAvatarFieldProvided,
            boolean hasPhoneFieldProvided,
            boolean hasSchoolFieldProvided,
            boolean hasSubjectFieldProvided,
            String normalizedAvatarUrl,
            String normalizedPhone,
            String normalizedSchool,
            String normalizedSubject,
            boolean hasCurrentPassword,
            boolean hasNewPassword,
            boolean hasProfileFieldChange) {
    }

    @Transactional
    public UserProfileResponse uploadMyAvatar(String email, MultipartFile file) {
        String normalizedEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException(USER_NOT_FOUND_MESSAGE));

        String avatarUrl = avatarStorageService.uploadAvatar(user.getId(), file);
        user.setAvatarUrl(avatarUrl);

        User savedUser = userRepository.save(user);
        evictUserProfileCache(savedUser.getId(), savedUser.getEmail());
        securityAuditService.success(AUDIT_UPLOAD_AVATAR, normalizedEmail, "avatar-uploaded");
        return mapToUserProfile(savedUser);
    }

    @Transactional
    public AuthTokenResponse refresh(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken().trim();
        if (!hasText(refreshToken)) {
            securityAuditService.failure(AUDIT_REFRESH, null, "missing-refresh-token");
            throw new BadCredentialsException("Refresh token is missing");
        }

        Long userId = refreshTokenService.resolveUserId(refreshToken)
                .orElseThrow(() -> new BadCredentialsException("Refresh token is invalid or expired"));

        String currentToken = refreshTokenService.findByUserId(userId)
                .orElseThrow(() -> new BadCredentialsException("Refresh token is invalid or expired"));

        if (!currentToken.equals(refreshToken)) {
            refreshTokenService.revokeByToken(refreshToken);
            securityAuditService.failure(AUDIT_REFRESH, null, "refresh-token-mismatch");
            throw new BadCredentialsException("Refresh token is invalid or expired");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException(USER_NOT_FOUND_MESSAGE));

        refreshTokenService.revokeByUserId(userId);
        securityAuditService.success(AUDIT_REFRESH, user.getEmail(), "refresh-token-rotated");
        return issueTokenPair(user);
    }

    private AuthTokenResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtService.generateRefreshToken();
        long refreshTtlSeconds = jwtService.getRefreshTokenExpirationSeconds();
        refreshTokenService.store(user.getId(), refreshToken, Duration.ofSeconds(refreshTtlSeconds));
        authUserProfileEventPublisher.publish(user, isPremiumActive(user.getId()));

        return AuthTokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationSeconds())
                .refreshExpiresIn(refreshTtlSeconds)
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

    private UserProfileResponse mapToUserProfile(User user) {
        boolean premium = isPremiumActive(user.getId());
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .avatarUrl(avatarStorageService.toPublicAvatarUrl(user.getId(), user.getAvatarUrl()))
                .phoneNumber(user.getPhoneNumber())
                .school(user.getSchool())
                .subject(user.getSubject())
                .role(user.getRole())
                .premium(premium)
                .build();
    }

    private UserProfileResponse ensurePublicAvatarUrl(UserProfileResponse profile) {
        if (profile == null) {
            return null;
        }

        String publicAvatarUrl = avatarStorageService.toPublicAvatarUrl(profile.id(), profile.avatarUrl());
        if (equalsNullable(publicAvatarUrl, profile.avatarUrl())) {
            return profile;
        }

        UserProfileResponse normalized = UserProfileResponse.builder()
                .id(profile.id())
                .email(profile.email())
                .fullName(profile.fullName())
                .avatarUrl(publicAvatarUrl)
                .phoneNumber(profile.phoneNumber())
                .school(profile.school())
                .subject(profile.subject())
                .role(profile.role())
                .premium(profile.premium())
                .build();

        if (normalized.id() != null && normalized.id() > 0) {
            userProfileCacheService.store(normalized.id(), normalized);
        }
        return normalized;
    }

    private boolean isPremiumActive(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }

        Instant now = Instant.now();
        return userSubscriptionRepository.existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
                userId,
                SubscriptionStatus.APPROVED,
                now,
                now);
    }

    private String normalizeOptionalText(String value) {
        if (!hasText(value)) {
            return null;
        }
        return normalizeWhitespace(value);
    }

    private boolean equalsNullable(String first, String second) {
        return java.util.Objects.equals(first, second);
    }

    @Transactional
    public void logout(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        tokenBlacklistService.blacklist(token, jwtService.extractExpiration(token));

        String subject = jwtService.extractSubject(token);
        securityAuditService.success(AUDIT_LOGOUT, subject, "access-token-blacklisted");
        userRepository.findByEmailIgnoreCase(subject)
                .ifPresent(user -> refreshTokenService.revokeByUserId(user.getId()));
    }

    private String extractBearerToken(String authorizationHeader) {
        if (!hasText(authorizationHeader) || !authorizationHeader.startsWith("Bearer ")) {
            securityAuditService.failure(AUDIT_LOGOUT, null, "invalid-authorization-header");
            throw new BadCredentialsException("Authorization header is missing or invalid");
        }

        String token = authorizationHeader.substring(7).trim();
        if (!hasText(token)) {
            securityAuditService.failure(AUDIT_LOGOUT, null, "missing-bearer-token");
            throw new BadCredentialsException("Bearer token is missing");
        }

        return token;
    }

    public void evictUserProfileCache(Long userId) {
        userProfileCacheService.evict(userId);
    }

    public void evictUserProfileCache(Long userId, String email) {
        userProfileCacheService.evict(userId, email);
    }

    private String otpKey(String otp) {
        return RESET_PASSWORD_OTP_KEY_PREFIX + otp;
    }

    private String emailOtpKey(String email) {
        return RESET_PASSWORD_EMAIL_KEY_PREFIX + email;
    }

    private String resetPasswordTokenKey(String token) {
        return RESET_PASSWORD_TOKEN_KEY_PREFIX + token;
    }

    private String registerVerificationOtpKey(String otp) {
        return REGISTER_EMAIL_VERIFICATION_OTP_KEY_PREFIX + otp;
    }

    private String registerVerificationEmailOtpKey(String email) {
        return REGISTER_EMAIL_VERIFICATION_EMAIL_KEY_PREFIX + email;
    }
}
