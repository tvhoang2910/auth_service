package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.request.RegisterRequest;
import com.exam_bank.auth_service.dto.request.LoginRequest;
import com.exam_bank.auth_service.dto.request.OAuth2ExchangeRequest;
import com.exam_bank.auth_service.dto.request.RefreshTokenRequest;
import com.exam_bank.auth_service.dto.request.ForgotPasswordRequest;
import com.exam_bank.auth_service.dto.request.VerifyForgotPasswordOtpRequest;
import com.exam_bank.auth_service.dto.request.VerifyEmailOtpRequest;
import com.exam_bank.auth_service.dto.request.ResetPasswordRequest;
import com.exam_bank.auth_service.dto.request.UpdateMyProfileRequest;
import com.exam_bank.auth_service.dto.response.AuthTokenResponse;
import com.exam_bank.auth_service.dto.response.RegisterResponse;
import com.exam_bank.auth_service.dto.response.ResetPasswordTokenResponse;
import com.exam_bank.auth_service.dto.response.UserProfileResponse;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.service.AuthService;
import com.exam_bank.auth_service.service.AvatarStorageService;
import com.exam_bank.auth_service.service.OAuth2LoginExchangeService;
import com.exam_bank.auth_service.security.JwtService;
import com.exam_bank.auth_service.service.PresenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private static final String MESSAGE_KEY = "message";

    private final AuthService authService;
    private final OAuth2LoginExchangeService oauth2LoginExchangeService;
    private final JwtService jwtService;
    private final PresenceService presenceService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("register: email={}", request.getEmail());
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/register/resend-verification")
    public ResponseEntity<Map<String, String>> resendRegisterVerification(
            @Valid @RequestBody ForgotPasswordRequest request) {
        log.info("resendRegisterVerification: email={}", request.email());
        authService.resendRegisterVerificationOtp(request.email());
        return ResponseEntity.ok(Map.of(MESSAGE_KEY, "Verification OTP has been resent if account exists"));
    }

    @PostMapping("/register/verify-email")
    public ResponseEntity<Map<String, String>> verifyRegisterEmail(
            @Valid @RequestBody VerifyEmailOtpRequest request) {
        log.info("verifyRegisterEmail: email={}", request.email());
        authService.verifyRegisterEmailOtp(request.email(), request.otp());
        return ResponseEntity.ok(Map.of(MESSAGE_KEY, "Email verified successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("login: email={}", request.getEmail());
        AuthTokenResponse response = authService.login(request);
        userRepository.findByEmailIgnoreCase(request.getEmail().trim().toLowerCase())
                .ifPresent(user -> presenceService.onUserLogin(user.getId(), user.getRole().name()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {
        log.info("logout: authorizationHeaderPresent={}",
                authorizationHeader != null && !authorizationHeader.isBlank());
        authService.logout(authorizationHeader);
        // Emit LEAVE presence event — look up user from token email before blacklisting
        String token = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7).trim()
                : null;
        if (token != null) {
            try {
                String email = jwtService.extractSubject(token);
                userRepository.findByEmailIgnoreCase(email)
                        .ifPresent(user -> presenceService.onUserLogout(user.getId(), user.getRole().name()));
            } catch (Exception e) {
                log.warn("logout: could not extract subject for presence: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of(MESSAGE_KEY, "Logged out successfully"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        log.debug("refresh: request received");
        AuthTokenResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/oauth2/exchange")
    public ResponseEntity<AuthTokenResponse> exchangeOAuth2Code(
            @Valid @RequestBody OAuth2ExchangeRequest request) {
        log.info("exchangeOAuth2Code: codePresent={}",
                request.getCode() != null && !request.getCode().isBlank());
        Long userId = oauth2LoginExchangeService.consumeUserId(request.getCode());
        AuthTokenResponse response = authService.issueTokenByUserId(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("forgotPassword: email={}", request.email());
        authService.forgotPassword(request.email());
        return ResponseEntity.ok(Map.of(MESSAGE_KEY, "OTP has been sent if email exists"));
    }

    @PostMapping("/forgot-password/resend")
    public ResponseEntity<Map<String, String>> resendForgotPasswordOtp(
            @Valid @RequestBody ForgotPasswordRequest request) {
        log.info("resendForgotPasswordOtp: email={}", request.email());
        authService.resendForgotPasswordOtp(request.email());
        return ResponseEntity.ok(Map.of(MESSAGE_KEY, "OTP has been resent if email exists"));
    }

    @PostMapping("/forgot-password/verify-otp")
    public ResponseEntity<ResetPasswordTokenResponse> verifyForgotPasswordOtp(
            @Valid @RequestBody VerifyForgotPasswordOtpRequest request) {
        log.info("verifyForgotPasswordOtp: email={}", request.email());
        String resetToken = authService.verifyForgotPasswordOtp(request.email(), request.otp());
        return ResponseEntity.ok(new ResetPasswordTokenResponse(resetToken, "OTP verified successfully"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("resetPassword: tokenPresent={}",
                request.resetToken() != null && !request.resetToken().isBlank());
        authService.resetPassword(request.resetToken(), request.newPassword());
        return ResponseEntity.ok(Map.of(MESSAGE_KEY, "Password reset successfully"));
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(Authentication authentication) {
        log.debug("me: email={}", authentication.getName());
        UserProfileResponse response = authService.getMyProfile(authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateMe(
            Authentication authentication,
            @Valid @RequestBody UpdateMyProfileRequest request) {
        log.info("updateMe: email={}", authentication.getName());
        UserProfileResponse response = authService.updateMyProfile(authentication.getName(), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserProfileResponse> uploadMyAvatar(
            Authentication authentication,
            @RequestPart("file") MultipartFile file) {
        log.info("uploadMyAvatar: email={}, fileName={}, size={}",
                authentication.getName(),
                file.getOriginalFilename(),
                file.getSize());
        UserProfileResponse response = authService.uploadMyAvatar(authentication.getName(), file);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/users/{userId}/avatar")
    public ResponseEntity<byte[]> getUserAvatar(@PathVariable Long userId) {
        AvatarStorageService.AvatarFileContent avatar = authService.getUserAvatar(userId);
        MediaType mediaType = MediaType.parseMediaType(avatar.contentType());

        log.debug("getUserAvatar: userId={}, objectKey={}", userId, avatar.objectKey());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"avatar-" + userId + "\"")
                .body(avatar.content());
    }
}
