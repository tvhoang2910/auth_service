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

import jakarta.annotation.PostConstruct;

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

    // HttpClient will be initialized in @PostConstruct so we can use injected timeoutMillis
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Instant> lastSuccessfulHeartbeatByService = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailuresByService = new ConcurrentHashMap<>();

    @Value("${auth.system-status.services:"
        + "auth_service=http://localhost:8080/api/v1/auth/,"
        + "community_service=http://localhost:8084/api/v1/community/,"
        + "exam_service=http://localhost:8082/api/v1/exam/,"
        + "notification_service=http://localhost:8081/,"
        + "study_service=http://localhost:8085/api/v1/study/}")
    private String serviceTargetsConfig;

    @Value("${auth.system-status.timeout-millis:1500}")
    private int timeoutMillis;

    @Value("${auth.system-status.failure-threshold:2}")
    private int failureThreshold;

    @PostConstruct
    private void initHttpClient() {
        // Ensure a sensible minimum timeout
        int effectiveMillis = Math.max(500, timeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(effectiveMillis))
                .build();
        logger.info("SystemStatus monitor initialized (timeout={}ms, failureThreshold={})", effectiveMillis, failureThreshold);
    }

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

    /**
     * Probes the configured candidate health endpoints for the target.
     * <p>
     * Logic summary:
     * - Try multiple candidate health endpoints derived from the base URL.
     * - On first successful probe (HTTP 200 with JSON {status: "UP"}) mark ONLINE and reset failure count.
     * - On failure increment consecutive failure count; only mark OFFLINE when failures >= failureThreshold.
     * - While failures < threshold, continue returning previous successful heartbeat (avoid flipping to OFFLINE on transient errors).
     */
    private SystemAdminServiceStatusItemResponse probeTarget(long id, MonitorTarget target, Instant now) {
        String name = target.name();

        for (URI candidateUrl : buildHealthCandidates(target.url())) {
            ProbeAttempt attempt = probe(candidateUrl);
            logAttempt(name, candidateUrl, attempt);

            if (attempt.success) {
                // Successful heartbeat: reset failures and record last success
                consecutiveFailuresByService.remove(name);
                lastSuccessfulHeartbeatByService.put(name, now);
                return new SystemAdminServiceStatusItemResponse(
                        id,
                        name,
                        "ONLINE",
                        target.port(),
                        heartbeatAgo(now, now),
                        attempt.responseTimeMs + "ms",
                        DATE_TIME_FORMATTER.format(now));
            }
        }

        // No candidate succeeded
        int failures = consecutiveFailuresByService.getOrDefault(name, 0) + 1;
        consecutiveFailuresByService.put(name, failures);

        Instant lastSuccess = lastSuccessfulHeartbeatByService.get(name);

        // If we have recent successful heartbeat and haven't exceeded failure threshold yet,
        // keep service as ONLINE (stale) to avoid flapping on transient errors. Otherwise mark OFFLINE.
        if (lastSuccess != null && failures < Math.max(1, failureThreshold)) {
            return new SystemAdminServiceStatusItemResponse(
                    id,
                    name,
                    "ONLINE",
                    target.port(),
                    heartbeatAgo(lastSuccess, now),
                    "N/A",
                    DATE_TIME_FORMATTER.format(now));
        }

        // Failure threshold reached or no previous success -> treat as OFFLINE
        return new SystemAdminServiceStatusItemResponse(
                id,
                name,
                "OFFLINE",
                target.port(),
                heartbeatAgo(lastSuccess, now),
                "N/A",
                DATE_TIME_FORMATTER.format(now));
    }

    private ProbeAttempt probe(URI url) {
        // Use configured timeout for the request; ensure a sensible minimum
        Duration reqTimeout = Duration.ofMillis(Math.max(500, timeoutMillis));
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(reqTimeout)
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
            String summary = summarizeException(attempt.exception);
            // categorize common exceptions
            if (summary.contains("timeout")) {
                logger.warn("{} -> TIMEOUT after {}ms: {}", serviceName, attempt.responseTimeMs, summary);
            } else if (summary.contains("connection refused") || summary.contains("Connection refused")) {
                logger.warn("{} -> CONNECTION REFUSED after {}ms: {}", serviceName, attempt.responseTimeMs, summary);
            } else {
                logger.warn("{} -> EXCEPTION after {}ms: {}", serviceName, attempt.responseTimeMs, summary);
            }
            return;
        }

        logger.info("{} -> HTTP {}", serviceName, attempt.httpStatus);
        if (attempt.responseBody != null && !attempt.responseBody.isBlank()) {
            logger.debug("{} -> Response: {}", serviceName, attempt.responseBody);
        }

        if (attempt.httpStatus != 200) {
            if (attempt.httpStatus == 401 || attempt.httpStatus == 403) {
                logger.warn("{} -> AUTH FAILURE HTTP {}", serviceName, attempt.httpStatus);
            } else if (attempt.httpStatus == 404) {
                logger.warn("{} -> NOT FOUND HTTP {}", serviceName, attempt.httpStatus);
            } else {
                logger.warn("{} -> UNEXPECTED HTTP {}", serviceName, attempt.httpStatus);
            }
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
