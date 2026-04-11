package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.internal.InternalUserDisplayNameBatchRequest;
import com.exam_bank.auth_service.service.InternalUserLookupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Value("${notification.internal-token}")
    private String internalToken;

    private final InternalUserLookupService internalUserLookupService;

    @GetMapping("/{userId}/display-name")
    public ResponseEntity<?> findDisplayNameByUserId(
            @PathVariable Long userId,
            @RequestHeader("X-Internal-Token") String providedToken) {
        if (!internalToken.equals(providedToken)) {
            log.warn("findDisplayNameByUserId: invalid internal token for userId={}", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }

        return internalUserLookupService.findDisplayNameByUserId(userId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "User not found")));
    }

    @GetMapping("/{userId}/premium-status")
    public ResponseEntity<?> findPremiumStatusByUserId(
            @PathVariable Long userId,
            @RequestHeader("X-Internal-Token") String providedToken) {
        if (!internalToken.equals(providedToken)) {
            log.warn("findPremiumStatusByUserId: invalid internal token for userId={}", userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }

        return internalUserLookupService.findPremiumStatusByUserId(userId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "User not found")));
    }

    @PostMapping("/display-names")
    public ResponseEntity<?> findDisplayNamesByUserIds(
            @RequestHeader("X-Internal-Token") String providedToken,
            @Valid @RequestBody InternalUserDisplayNameBatchRequest request) {
        if (!internalToken.equals(providedToken)) {
            log.warn("findDisplayNamesByUserIds: invalid internal token");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Forbidden"));
        }

        return ResponseEntity.ok(internalUserLookupService.findDisplayNamesByUserIds(request.getUserIds()));
    }
}
