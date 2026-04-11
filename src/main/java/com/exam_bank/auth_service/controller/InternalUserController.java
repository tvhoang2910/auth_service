package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.internal.InternalUserDisplayNameBatchRequest;
import com.exam_bank.auth_service.service.InternalUserLookupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
@Slf4j
public class InternalUserController {

    private static final String DEPRECATION_WARNING = "299 - \"Deprecated internal auth endpoint. Migrate to auth.events user profile sync projection before 2026-10-31.\"";
    private static final String SUNSET_HEADER = "Sat, 31 Oct 2026 23:59:59 GMT";

    @Value("${notification.internal-token}")
    private String internalToken;

    private final InternalUserLookupService internalUserLookupService;

    @Deprecated(since = "2026-04", forRemoval = false)
    @GetMapping("/{userId}/display-name")
    public ResponseEntity<?> findDisplayNameByUserId(
            @PathVariable Long userId,
            @RequestHeader("X-Internal-Token") String providedToken) {
        if (!internalToken.equals(providedToken)) {
            log.warn("findDisplayNameByUserId: invalid internal token for userId={}", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .headers(deprecationHeaders())
                    .body(Map.of("error", "Forbidden"));
        }

        log.warn("Deprecated endpoint called: GET /internal/users/{}/display-name", userId);

        return internalUserLookupService.findDisplayNameByUserId(userId)
                .<ResponseEntity<?>>map(response -> ResponseEntity.ok()
                        .headers(deprecationHeaders())
                        .body(response))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .headers(deprecationHeaders())
                        .body(Map.of("error", "User not found")));
    }

    @Deprecated(since = "2026-04", forRemoval = false)
    @GetMapping("/{userId}/premium-status")
    public ResponseEntity<?> findPremiumStatusByUserId(
            @PathVariable Long userId,
            @RequestHeader("X-Internal-Token") String providedToken) {
        if (!internalToken.equals(providedToken)) {
            log.warn("findPremiumStatusByUserId: invalid internal token for userId={}", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .headers(deprecationHeaders())
                    .body(Map.of("error", "Forbidden"));
        }

        log.warn("Deprecated endpoint called: GET /internal/users/{}/premium-status", userId);

        return internalUserLookupService.findPremiumStatusByUserId(userId)
                .<ResponseEntity<?>>map(response -> ResponseEntity.ok()
                        .headers(deprecationHeaders())
                        .body(response))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .headers(deprecationHeaders())
                        .body(Map.of("error", "User not found")));
    }

    @Deprecated(since = "2026-04", forRemoval = false)
    @PostMapping("/display-names")
    public ResponseEntity<?> findDisplayNamesByUserIds(
            @RequestHeader("X-Internal-Token") String providedToken,
            @Valid @RequestBody InternalUserDisplayNameBatchRequest request) {
        if (!internalToken.equals(providedToken)) {
            log.warn("findDisplayNamesByUserIds: invalid internal token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .headers(deprecationHeaders())
                    .body(Map.of("error", "Forbidden"));
        }

        log.warn("Deprecated endpoint called: POST /internal/users/display-names userCount={}",
                request.getUserIds() == null ? 0 : request.getUserIds().size());

        return ResponseEntity.ok()
                .headers(deprecationHeaders())
                .body(internalUserLookupService.findDisplayNamesByUserIds(request.getUserIds()));
    }

    private HttpHeaders deprecationHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Deprecation", "true");
        headers.add("Sunset", SUNSET_HEADER);
        headers.add("Warning", DEPRECATION_WARNING);
        return headers;
    }
}
