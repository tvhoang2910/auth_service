package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.CorsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth2RedirectUriCaptureFilter Unit Tests")
class OAuth2RedirectUriCaptureFilterTest {

    @Test
    @DisplayName("stores allowed redirect_uri in session for Google OAuth2 authorization request")
    void storesAllowedRedirectUriInSession() throws Exception {
        CorsProperties corsProperties = new CorsProperties();
        corsProperties.setAllowedOrigins("http://localhost:5173,https://*.ngrok-free.dev");
        OAuth2RedirectUriCaptureFilter filter = new OAuth2RedirectUriCaptureFilter(corsProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/oauth2/authorization/google");
        request.setParameter("redirect_uri", "http://localhost:5173/oauth2/success");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getSession(false)).isNotNull();
        assertThat(
                request.getSession(false).getAttribute(OAuth2RedirectUriCaptureFilter.SUCCESS_REDIRECT_URI_SESSION_KEY))
                .isEqualTo("http://localhost:5173/oauth2/success");
    }

    @Test
    @DisplayName("ignores redirect_uri with disallowed origin")
    void ignoresRedirectUriWithDisallowedOrigin() throws Exception {
        CorsProperties corsProperties = new CorsProperties();
        corsProperties.setAllowedOrigins("http://localhost:5173,https://*.ngrok-free.dev");
        OAuth2RedirectUriCaptureFilter filter = new OAuth2RedirectUriCaptureFilter(corsProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/oauth2/authorization/google");
        request.setParameter("redirect_uri", "http://malicious.example.com/oauth2/success");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        if (request.getSession(false) != null) {
            assertThat(request.getSession(false)
                    .getAttribute(OAuth2RedirectUriCaptureFilter.SUCCESS_REDIRECT_URI_SESSION_KEY))
                    .isNull();
        }
    }
}
