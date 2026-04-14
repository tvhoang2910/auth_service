package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.AppOauth2Properties;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.service.AuthService;
import com.exam_bank.auth_service.service.OAuth2LoginExchangeService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Locale;

import static org.springframework.util.StringUtils.hasText;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final String OAUTH2_ERROR_QUERY_PARAM = "oauth2_error";
    private static final String OAUTH2_ERROR_ACCOUNT_LOCKED = "account_locked";

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
            sendOAuth2ErrorRedirect(request, response, OAUTH2_ERROR_ACCOUNT_LOCKED);
            return;
        }
        String exchangeCode = oauth2LoginExchangeService.issueCode(user.getId());

        String targetUrl = resolveRedirectUriBuilder(request, appOauth2Properties.getSuccessRedirectUrl())
                .queryParam("code", exchangeCode)
                .build(true)
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private void sendOAuth2ErrorRedirect(HttpServletRequest request,
            HttpServletResponse response,
            String errorCode) throws IOException {
        String targetUrl = resolveRedirectUriBuilder(request, appOauth2Properties.getSuccessRedirectUrl())
                .replaceQueryParam("code")
                .replaceQueryParam(OAUTH2_ERROR_QUERY_PARAM, errorCode)
                .build(true)
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private UriComponentsBuilder resolveRedirectUriBuilder(HttpServletRequest request, String configuredRedirect) {
        if (hasText(configuredRedirect)
                && (configuredRedirect.startsWith("http://") || configuredRedirect.startsWith("https://"))) {
            return UriComponentsBuilder.fromUriString(configuredRedirect);
        }

        String capturedRedirect = consumeCapturedRedirectUri(request);
        if (hasText(capturedRedirect)) {
            return UriComponentsBuilder.fromUriString(capturedRedirect);
        }

        String normalizedPath = hasText(configuredRedirect)
                ? (configuredRedirect.startsWith("/") ? configuredRedirect : "/" + configuredRedirect)
                : "/oauth2/success";

        String forwardedProto = firstForwardedValue(request.getHeader("X-Forwarded-Proto"));
        String scheme = hasText(forwardedProto) ? forwardedProto : request.getScheme();
        if (!hasText(scheme)) {
            scheme = "http";
        }

        String forwardedHost = firstForwardedValue(request.getHeader("X-Forwarded-Host"));
        String host = hasText(forwardedHost) ? forwardedHost : request.getHeader("Host");
        if (!hasText(host)) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port > 0 && port != 80 && port != 443) {
                host = host + ":" + port;
            }
        }

        String baseUri = scheme.toLowerCase(Locale.ROOT) + "://" + host;
        return UriComponentsBuilder.fromUriString(baseUri).path(normalizedPath);
    }

    private String consumeCapturedRedirectUri(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }

        Object value = session.getAttribute(OAuth2RedirectUriCaptureFilter.SUCCESS_REDIRECT_URI_SESSION_KEY);
        session.removeAttribute(OAuth2RedirectUriCaptureFilter.SUCCESS_REDIRECT_URI_SESSION_KEY);

        if (value instanceof String redirectUri && hasText(redirectUri)) {
            return redirectUri;
        }
        return null;
    }

    private String firstForwardedValue(String headerValue) {
        if (!hasText(headerValue)) {
            return null;
        }
        return headerValue.split(",", 2)[0].trim();
    }
}
