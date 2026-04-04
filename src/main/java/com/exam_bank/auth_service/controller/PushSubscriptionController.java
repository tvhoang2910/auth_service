package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.internal.PushSubscriptionDto;
import com.exam_bank.auth_service.dto.request.PushSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.PushSubscriptionResponse;
import com.exam_bank.auth_service.dto.response.VapidPublicKeyResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.service.PushSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/push-subscription")
@RequiredArgsConstructor
@Slf4j
public class PushSubscriptionController {

    @Value("${notification.vapid.public-key}")
    private String vapidPublicKey;

    @Value("${notification.internal-token}")
    private String internalToken;

    private final PushSubscriptionService pushSubscriptionService;
    private final UserRepository userRepository;

    /**
     * Public endpoint — returns the VAPID public key for the browser to use
     * when subscribing to push notifications.
     */
    @GetMapping("/vapid-public-key")
    public ResponseEntity<VapidPublicKeyResponse> getVapidPublicKey() {
        return ResponseEntity.ok(new VapidPublicKeyResponse(vapidPublicKey));
    }

    /**
     * Authenticated — saves or updates a push subscription for the current user.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PushSubscriptionResponse> subscribe(
            Authentication authentication,
            @Valid @RequestBody PushSubscriptionRequest request) {
        Long userId = resolveUserId(authentication.getName());
        PushSubscriptionResponse response = pushSubscriptionService.subscribe(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticated — soft-deletes (deactivates) a push subscription for the current user.
     */
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> unsubscribe(
            Authentication authentication,
            @RequestBody Map<String, String> body) {
        Long userId = resolveUserId(authentication.getName());
        String endpoint = body.get("endpoint");
        pushSubscriptionService.unsubscribe(userId, endpoint);
        return ResponseEntity.ok(Map.of("message", "Unsubscribed successfully"));
    }

    /**
     * Internal endpoint — returns active push subscriptions for a given userId.
     * Secured by matching the X-Internal-Token header against the configured internal token.
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getSubscriptionsByUserId(
            @PathVariable Long userId,
            @RequestHeader("X-Internal-Token") String providedToken) {
        if (!internalToken.equals(providedToken)) {
            log.warn("getSubscriptionsByUserId: invalid internal token from client");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden"));
        }
        List<PushSubscriptionDto> subscriptions = pushSubscriptionService.getSubscriptionsByUserId(userId);
        return ResponseEntity.ok(subscriptions);
    }

    /**
     * Internal endpoint — returns all active push subscriptions for every user of a given role.
     * Used by notification_service to send admin/contributor alerts.
     * Secured by matching the X-Internal-Token header against the configured internal token.
     */
    @GetMapping("/role/{role}")
    public ResponseEntity<?> getSubscriptionsByRole(
            @PathVariable String role,
            @RequestHeader("X-Internal-Token") String providedToken) {
        if (!internalToken.equals(providedToken)) {
            log.warn("getSubscriptionsByRole: invalid internal token from role={}", role);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Forbidden"));
        }
        try {
            Role roleEnum = Role.valueOf(role.toUpperCase());
            List<User> users = userRepository.findByRoleInAndStatusTrue(List.of(roleEnum));
            List<PushSubscriptionDto> all = new ArrayList<>();
            for (User user : users) {
                all.addAll(pushSubscriptionService.getSubscriptionsByUserId(user.getId()));
            }
            return ResponseEntity.ok(all);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role: " + role));
        }
    }

    private Long resolveUserId(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    log.error("resolveUserId: user not found for email={}", email);
                    return new IllegalStateException("User not found: " + email);
                })
                .getId();
    }
}
