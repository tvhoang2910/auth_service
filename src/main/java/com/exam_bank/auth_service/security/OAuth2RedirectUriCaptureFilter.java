package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.CorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

import static org.springframework.util.StringUtils.hasText;

@Component
@RequiredArgsConstructor
public class OAuth2RedirectUriCaptureFilter extends OncePerRequestFilter {

    public static final String SUCCESS_REDIRECT_URI_SESSION_KEY = "oauth2.success.redirect.uri";

    private static final Logger log = LoggerFactory.getLogger(OAuth2RedirectUriCaptureFilter.class);

    private final CorsProperties corsProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request == null || !request.getRequestURI().endsWith("/oauth2/authorization/google");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestedRedirect = request.getParameter("redirect_uri");
        String allowedRedirect = normalizeAllowedRedirectUri(requestedRedirect);

        if (hasText(allowedRedirect)) {
            HttpSession session = request.getSession(true);
            session.setAttribute(SUCCESS_REDIRECT_URI_SESSION_KEY, allowedRedirect);
            log.debug("Captured OAuth2 redirect_uri={}", allowedRedirect);
        } else if (hasText(requestedRedirect)) {
            log.warn("Ignored OAuth2 redirect_uri because origin is not allowed: {}", requestedRedirect);
        }

        filterChain.doFilter(request, response);
    }

    private String normalizeAllowedRedirectUri(String rawRedirectUri) {
        if (!hasText(rawRedirectUri)) {
            return null;
        }

        URI parsedUri;
        try {
            parsedUri = URI.create(rawRedirectUri.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }

        if (!hasText(parsedUri.getScheme()) || !hasText(parsedUri.getHost())) {
            return null;
        }

        String scheme = parsedUri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }

        String origin = buildOrigin(scheme, parsedUri.getHost(), parsedUri.getPort());
        if (!isAllowedOrigin(origin)) {
            return null;
        }

        return UriComponentsBuilder.fromUriString(rawRedirectUri.trim())
                .build(true)
                .toUriString();
    }

    private boolean isAllowedOrigin(String origin) {
        return Arrays.stream(corsProperties.getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::trimTrailingSlash)
                .anyMatch(pattern -> originMatches(pattern, origin));
    }

    private boolean originMatches(String allowedPattern, String origin) {
        if ("*".equals(allowedPattern)) {
            return true;
        }
        return PatternMatchUtils.simpleMatch(allowedPattern, origin);
    }

    private String buildOrigin(String scheme, String host, int port) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (port <= 0) {
            return scheme + "://" + normalizedHost;
        }
        return scheme + "://" + normalizedHost + ":" + port;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
