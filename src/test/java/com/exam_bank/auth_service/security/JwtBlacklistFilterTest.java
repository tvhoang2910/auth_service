package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.service.TokenBlacklistService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtBlacklistFilterTest {

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @InjectMocks
    private JwtBlacklistFilter jwtBlacklistFilter;

    @Test
    void doFilterInternal_shouldReturn401_whenTokenIsBlacklisted() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer revoked-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(tokenBlacklistService.isBlacklisted("revoked-token")).thenReturn(true);

        jwtBlacklistFilter.doFilter(request, response, chain);

        verify(tokenBlacklistService).isBlacklisted("revoked-token");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Token has been revoked");
    }

    @Test
    void doFilterInternal_shouldContinue_whenTokenIsNotBlacklisted() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer active-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(tokenBlacklistService.isBlacklisted("active-token")).thenReturn(false);

        jwtBlacklistFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
