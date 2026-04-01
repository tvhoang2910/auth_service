package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.request.ForgotPasswordRequest;
import com.exam_bank.auth_service.dto.request.VerifyEmailOtpRequest;
import com.exam_bank.auth_service.dto.response.AuthTokenResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.exception.GlobalExceptionHandler;
import com.exam_bank.auth_service.service.AuthService;
import com.exam_bank.auth_service.service.OAuth2LoginExchangeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIntegrationTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    private AuthService authService;

    private OAuth2LoginExchangeService oauth2LoginExchangeService;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        oauth2LoginExchangeService = mock(OAuth2LoginExchangeService.class);
        objectMapper = new ObjectMapper();
        AuthController authController = new AuthController(authService, oauth2LoginExchangeService);
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void verifyRegisterEmail_shouldReturnOk_whenOtpValid() throws Exception {
        VerifyEmailOtpRequest request = new VerifyEmailOtpRequest("user@example.com", "123456");

        mockMvc.perform(post("/register/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email verified successfully"));

        verify(authService).verifyRegisterEmailOtp("user@example.com", "123456");
    }

    @Test
    void verifyRegisterEmail_shouldReturnBadRequest_whenOtpInvalid() throws Exception {
        VerifyEmailOtpRequest request = new VerifyEmailOtpRequest("user@example.com", "abc123");

        mockMvc.perform(post("/register/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void resendRegisterVerification_shouldReturnOk() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("user@example.com");

        mockMvc.perform(post("/register/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification OTP has been resent if account exists"));

        verify(authService).resendRegisterVerificationOtp("user@example.com");
    }

    @Test
    void login_shouldReturnUnauthorized_whenEmailNotVerified() throws Exception {
        doThrow(new BadCredentialsException("Email is not verified"))
                .when(authService)
                .login(org.mockito.ArgumentMatchers.any());

        mockMvc.perform(post("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email is not verified"));
    }

    @Test
    void oauth2Exchange_shouldReturnTokenPair_whenCodeIsValid() throws Exception {
        AuthTokenResponse tokenResponse = AuthTokenResponse.builder()
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .tokenType("Bearer")
                .expiresIn(3600)
                .refreshExpiresIn(604800)
                .email("user@example.com")
                .role(Role.USER)
                .build();

        when(oauth2LoginExchangeService.consumeUserId("exchange-code")).thenReturn(7L);
        when(authService.issueTokenByUserId(7L)).thenReturn(tokenResponse);

        mockMvc.perform(post("/oauth2/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"exchange-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));

        verify(oauth2LoginExchangeService).consumeUserId("exchange-code");
        verify(authService).issueTokenByUserId(7L);
    }
}
