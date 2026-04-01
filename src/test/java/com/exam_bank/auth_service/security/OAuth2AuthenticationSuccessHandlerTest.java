package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.AppOauth2Properties;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.service.AuthService;
import com.exam_bank.auth_service.service.OAuth2LoginExchangeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock
    private AuthService authService;

    @Mock
    private OAuth2LoginExchangeService oauth2LoginExchangeService;

    @Mock
    private AppOauth2Properties appOauth2Properties;

    @InjectMocks
    private OAuth2AuthenticationSuccessHandler successHandler;

    @Test
    void onAuthenticationSuccess_shouldRedirectWithExchangeCodeOnly() throws Exception {
        User user = new User();
        user.setId(99L);
        user.setEmail("user@example.com");
        user.setRole(Role.USER);
        user.setStatus(true);

        when(authService.upsertGoogleUser("user@example.com", "OAuth User")).thenReturn(user);
        when(oauth2LoginExchangeService.issueCode(99L)).thenReturn("exchange-code-123");
        when(appOauth2Properties.getSuccessRedirectUrl()).thenReturn("http://localhost:5173/oauth2/success");

        OAuth2User oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(
                        "email", "user@example.com",
                        "name", "OAuth User"),
                "email");
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                oauth2User,
                null,
                oauth2User.getAuthorities());

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/oauth2/success?code=exchange-code-123");
        assertThat(response.getRedirectedUrl()).doesNotContain("token=").doesNotContain("refreshToken=");
        verify(authService).upsertGoogleUser("user@example.com", "OAuth User");
        verify(authService, never()).issueToken(user);
    }
}
