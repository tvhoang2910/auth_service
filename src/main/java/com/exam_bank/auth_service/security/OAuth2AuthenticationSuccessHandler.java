package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.AppOauth2Properties;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.service.AuthService;
import com.exam_bank.auth_service.service.OAuth2LoginExchangeService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

import static org.springframework.util.StringUtils.hasText;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthService authService;
    private final OAuth2LoginExchangeService oauth2LoginExchangeService;
    private final AppOauth2Properties appOauth2Properties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (authentication == null || !(authentication.getPrincipal() instanceof OAuth2User oauth2User)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "OAuth2 authentication principal is missing");
            return;
        }
        String email = oauth2User.getAttribute("email");
        String fullName = oauth2User.getAttribute("name");

        if (!hasText(email)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Google account email is missing");
            return;
        }

        User user = authService.upsertGoogleUser(email, fullName);
        if (!user.isStatus()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Account is locked");
            return;
        }
        String exchangeCode = oauth2LoginExchangeService.issueCode(user.getId());

        String targetUrl = UriComponentsBuilder.fromUriString(appOauth2Properties.getSuccessRedirectUrl())
                .queryParam("code", exchangeCode)
                .build(true)
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
