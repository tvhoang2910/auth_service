package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.AppOauth2Properties;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.service.AuthService;
import com.exam_bank.auth_service.service.OAuth2LoginExchangeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2AuthenticationSuccessHandler Unit Tests")
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock
    private AuthService authService;

    @Mock
    private OAuth2LoginExchangeService oauth2LoginExchangeService;

    @Mock
    private AppOauth2Properties appOauth2Properties;

    @InjectMocks
    private OAuth2AuthenticationSuccessHandler handler;

    @Test
    @DisplayName("redirects to captured frontend redirect URI when configured redirect is relative")
    void redirectsToCapturedFrontendRedirectUriWhenConfiguredRedirectIsRelative() throws Exception {
        when(appOauth2Properties.getSuccessRedirectUrl()).thenReturn("/oauth2/success");
        when(authService.upsertGoogleUser("user@example.com", "Exam User")).thenReturn(activeUser(101L));
        when(oauth2LoginExchangeService.issueCode(101L)).thenReturn("code-123");

        MockHttpServletRequest request = buildCallbackRequest();
        request.getSession(true)
                .setAttribute(OAuth2RedirectUriCaptureFilter.SUCCESS_REDIRECT_URI_SESSION_KEY,
                        "http://localhost:5173/oauth2/success");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, buildAuthentication());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/oauth2/success?code=code-123");
        assertThat(request.getSession(false)
                .getAttribute(OAuth2RedirectUriCaptureFilter.SUCCESS_REDIRECT_URI_SESSION_KEY)).isNull();
    }

    @Test
    @DisplayName("falls back to backend host when no captured redirect and configured redirect is relative")
    void fallsBackToBackendHostWhenNoCapturedRedirectAndConfiguredRedirectIsRelative() throws Exception {
        when(appOauth2Properties.getSuccessRedirectUrl()).thenReturn("/oauth2/success");
        when(authService.upsertGoogleUser("user@example.com", "Exam User")).thenReturn(activeUser(202L));
        when(oauth2LoginExchangeService.issueCode(202L)).thenReturn("code-456");

        MockHttpServletRequest request = buildCallbackRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, buildAuthentication());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8080/oauth2/success?code=code-456");
    }

    @Test
    @DisplayName("uses absolute configured redirect URL when provided")
    void usesAbsoluteConfiguredRedirectUrlWhenProvided() throws Exception {
        when(appOauth2Properties.getSuccessRedirectUrl())
                .thenReturn("https://unuxorious-shana-cavalierly.ngrok-free.dev/oauth2/success");
        when(authService.upsertGoogleUser("user@example.com", "Exam User")).thenReturn(activeUser(303L));
        when(oauth2LoginExchangeService.issueCode(303L)).thenReturn("code-789");

        MockHttpServletRequest request = buildCallbackRequest();
        request.getSession(true)
                .setAttribute(OAuth2RedirectUriCaptureFilter.SUCCESS_REDIRECT_URI_SESSION_KEY,
                        "http://localhost:5173/oauth2/success");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, buildAuthentication());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://unuxorious-shana-cavalierly.ngrok-free.dev/oauth2/success?code=code-789");
    }

    private Authentication buildAuthentication() {
        OAuth2User oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("email", "user@example.com", "name", "Exam User"),
                "email");
        return new TestingAuthenticationToken(oauth2User, null);
    }

    private MockHttpServletRequest buildCallbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.addHeader("Host", "localhost:8080");
        request.setRequestURI("/api/v1/auth/login/oauth2/code/google");
        return request;
    }

    private User activeUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setStatus(true);
        return user;
    }
}
