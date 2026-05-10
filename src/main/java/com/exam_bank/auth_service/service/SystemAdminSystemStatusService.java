package com.exam_bank.auth_service.service;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exam_bank.auth_service.dto.response.SystemAdminServiceStatusItemResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class SystemAdminSystemStatusService {

    private static final Logger logger = LoggerFactory.getLogger(SystemAdminSystemStatusService.class);
    private static final List<String> HEALTH_PATH_CANDIDATES = List.of(
        "/actuator/health",
        "/health",
        "/management/health",
        "/api/actuator/health");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("d/M/yyyy HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Instant> lastSuccessfulHeartbeatByService = new ConcurrentHashMap<>();

    @Value("${auth.system-status.services:"
        + "auth_service=http://localhost:8080/api/v1/auth/,"
        + "community_service=http://localhost:8084/api/v1/community/,"
        + "exam_service=http://localhost:8082/api/v1/exam/,"
        + "notification_service=http://localhost:8081/,"
        + "study_service=http://localhost:8085/api/v1/study/}")
    private String serviceTargetsConfig;

    @Value("${auth.system-status.timeout-millis:1500}")
    private int timeoutMillis;

    public List<SystemAdminServiceStatusItemResponse> getSystemStatus() {
        List<MonitorTarget> targets = parseTargets(serviceTargetsConfig);
        List<SystemAdminServiceStatusItemResponse> result = new ArrayList<>(targets.size());
        Instant now = Instant.now();

        for (int index = 0; index < targets.size(); index++) {
            MonitorTarget target = targets.get(index);
            result.add(probeTarget(index + 1L, target, now));
        }

        return result;
    }

    private SystemAdminServiceStatusItemResponse probeTarget(long id, MonitorTarget target, Instant now) {
        for (URI candidateUrl : buildHealthCandidates(target.url())) {
            ProbeAttempt attempt = probe(candidateUrl);
            logAttempt(target.name(), candidateUrl, attempt);

            if (attempt.success) {
                lastSuccessfulHeartbeatByService.put(target.name(), now);
                return new SystemAdminServiceStatusItemResponse(
                        id,
                        target.name(),
                        "ONLINE",
                        target.port(),
                        heartbeatAgo(now, now),
                        attempt.responseTimeMs + "ms",
                        DATE_TIME_FORMATTER.format(now));
            }
        }

        return downResponse(id, target, now, "N/A");
    }

    private ProbeAttempt probe(URI url) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        long startedAt = System.currentTimeMillis();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = Math.max(1, System.currentTimeMillis() - startedAt);
            String body = response.body() == null ? "" : response.body();
            boolean success = response.statusCode() == 200 && responseBodyIsUp(body);

            return new ProbeAttempt(
                    success,
                    response.statusCode(),
                    body,
                    elapsedMs,
                    null);
        } catch (Exception ex) {
            long elapsedMs = Math.max(1, System.currentTimeMillis() - startedAt);
            return new ProbeAttempt(false, -1, null, elapsedMs, ex);
        }
    }

    private void logAttempt(String serviceName, URI url, ProbeAttempt attempt) {
        logger.info("Checking {} -> {}", serviceName, url);

        if (attempt.exception != null) {
            logger.warn("{} -> EXCEPTION after {}ms: {}", serviceName, attempt.responseTimeMs, summarizeException(attempt.exception));
            return;
        }

        logger.info("{} -> HTTP {}", serviceName, attempt.httpStatus);
        if (attempt.responseBody != null) {
            logger.info("{} -> Response: {}", serviceName, attempt.responseBody);
        }
        logger.info("{} -> Response time: {}ms", serviceName, attempt.responseTimeMs);
    }

    private SystemAdminServiceStatusItemResponse downResponse(
            long id,
            MonitorTarget target,
            Instant now,
            String responseTime) {
        Instant lastSuccess = lastSuccessfulHeartbeatByService.get(target.name());
        return new SystemAdminServiceStatusItemResponse(
                id,
                target.name(),
                "DOWN",
                target.port(),
                heartbeatAgo(lastSuccess, now),
                responseTime,
                DATE_TIME_FORMATTER.format(now));
    }

    private boolean responseBodyIsUp(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode statusNode = root.get("status");
            return statusNode != null && "UP".equalsIgnoreCase(statusNode.asText());
        } catch (Exception ex) {
            return false;
        }
    }

    private String summarizeException(Exception ex) {
        if (ex instanceof java.net.http.HttpTimeoutException) {
            return "timeout";
        }
        if (ex instanceof ConnectException) {
            return "connection refused";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    private List<URI> buildHealthCandidates(URI baseUri) {
        URI normalizedBase = ensureTrailingSlash(stripKnownHealthPath(baseUri));
        LinkedHashSet<URI> candidates = new LinkedHashSet<>();

        for (String path : HEALTH_PATH_CANDIDATES) {
            candidates.add(normalizedBase.resolve(path.substring(1)));
        }

        return new ArrayList<>(candidates);
    }

    private URI stripKnownHealthPath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return uri;
        }

        for (String candidate : HEALTH_PATH_CANDIDATES) {
            if (path.endsWith(candidate)) {
                String basePath = path.substring(0, path.length() - candidate.length());
                if (basePath.isBlank()) {
                    basePath = "/";
                }
                return rebuildUri(uri, basePath);
            }
        }

        return uri;
    }

    private URI ensureTrailingSlash(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return rebuildUri(uri, "/");
        }
        if (path.endsWith("/")) {
            return uri;
        }
        return rebuildUri(uri, path + "/");
    }

    private URI rebuildUri(URI original, String newPath) {
        return URI.create(original.getScheme() + "://" + original.getAuthority() + newPath);
    }

    private String heartbeatAgo(Instant lastSuccess, Instant now) {
        if (lastSuccess == null) {
            return "N/A";
        }

        long seconds = Math.max(0L, Duration.between(lastSuccess, now).getSeconds());
        if (seconds < 60) {
            return seconds + "s ago";
        }

        long minutes = seconds / 60;
        return minutes + "m ago";
    }

    private List<MonitorTarget> parseTargets(String config) {
        List<MonitorTarget> targets = new ArrayList<>();
        if (config == null || config.isBlank()) {
            return targets;
        }

        String[] entries = config.split(",");
        for (String rawEntry : entries) {
            String entry = rawEntry.trim();
            if (entry.isBlank()) {
                continue;
            }

            int separatorIndex = entry.indexOf('=');
            if (separatorIndex <= 0 || separatorIndex >= entry.length() - 1) {
                continue;
            }

            String name = entry.substring(0, separatorIndex).trim();
            String urlText = entry.substring(separatorIndex + 1).trim();

            try {
                URI uri = URI.create(urlText);
                int port = uri.getPort() > 0
                        ? uri.getPort()
                        : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
                targets.add(new MonitorTarget(name, uri, port));
            } catch (IllegalArgumentException ignored) {
                // Skip invalid service target entries.
            }
        }

        return targets;
    }

    private record MonitorTarget(String name, URI url, int port) {
    }

    private record ProbeAttempt(boolean success, int httpStatus, String responseBody, long responseTimeMs,
            Exception exception) {
    }
}
