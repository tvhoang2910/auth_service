package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.internal.UserPresenceEvent;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.security.JwtService;
import com.exam_bank.auth_service.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
@Slf4j
public class PresenceSseController {

    private static final long SSE_TIMEOUT_MS = TimeUnit.HOURS.toMillis(8);

    private final PresenceService presenceService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    @GetMapping(value = "/presence", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter presenceSse(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "token", required = false) String tokenParam) {

        String token = extractToken(authHeader, tokenParam);
        if (token == null) {
            log.warn("SSE presence: no Bearer token provided");
            return new SseEmitter(0L);
        }

        String email;
        try {
            email = jwtService.extractSubject(token);
        } catch (Exception e) {
            log.warn("SSE presence: invalid JWT: {}", e.getMessage());
            return new SseEmitter(0L);
        }

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            log.warn("SSE presence: user not found for email={}", email);
            return new SseEmitter(0L);
        }

        String role = user.getRole().name();
        if (!role.equals("ADMIN") && !role.equals("CONTRIBUTOR")) {
            log.warn("SSE presence: user role={} not allowed for SSE", role);
            return new SseEmitter(0L);
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        presenceService.registerEmitter(role, emitter);

        // Mark user online
        presenceService.onUserLogin(user.getId(), role);

        emitter.onCompletion(() -> {
            log.info("SSE presence: emitter completed for userId={}", user.getId());
            presenceService.removeEmitter(role, emitter);
            presenceService.onUserLogout(user.getId(), role);
        });

        emitter.onTimeout(() -> {
            log.info("SSE presence: emitter timed out for userId={}", user.getId());
            presenceService.removeEmitter(role, emitter);
            presenceService.onUserLogout(user.getId(), role);
        });

        emitter.onError(e -> {
            log.warn("SSE presence: emitter error for userId={}: {}", user.getId(), e.getMessage());
            presenceService.removeEmitter(role, emitter);
            presenceService.onUserLogout(user.getId(), role);
        });

        // Send initial SNAPSHOT
        try {
            UserPresenceEvent snapshot = UserPresenceEvent.snapshot(role, presenceService.getOnlineCount(role));
            emitter.send(SseEmitter.event().name("presence").data(snapshot));
        } catch (IOException e) {
            log.warn("Failed to send presence snapshot to userId={}", user.getId());
        }

        log.info("SSE presence: connected for userId={}, role={}", user.getId(), role);
        return emitter;
    }

    @PostMapping("/presence/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "token", required = false) String tokenParam) {
        String token = extractToken(authHeader, tokenParam);
        if (token == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            String email = jwtService.extractSubject(token);
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (user == null) {
                return ResponseEntity.status(401).build();
            }
            presenceService.heartbeat(user.getId(), user.getRole().name());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(401).build();
        }
    }

    private String extractToken(String authHeader, String tokenParam) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        if (tokenParam != null && !tokenParam.isBlank()) {
            return tokenParam;
        }
        return null;
    }
}
