package com.exam_bank.auth_service.audit.controller;

import com.exam_bank.auth_service.audit.service.AuditLogService;
import com.exam_bank.auth_service.audit.service.VipService;
import com.exam_bank.auth_service.dto.request.AuditLogRequest;
import com.exam_bank.auth_service.dto.request.VipUpgradeRequest;
import com.exam_bank.auth_service.dto.response.VipStatusResponse;
import com.exam_bank.auth_service.entity.AuditLog;
import com.exam_bank.auth_service.entity.Subscription;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('AUDIT')")
public class AuditController {

    private final AuditLogService auditLogService;
    private final VipService vipService;

    @GetMapping("/logs")
    public ResponseEntity<Page<AuditLog>> getLogs(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = requireUserId(jwt);
        Page<AuditLog> logs = auditLogService.search(userId, module, action, PageRequest.of(page, Math.min(size, 100)));
        return ResponseEntity.ok(logs);
    }

    @PostMapping("/log")
    public ResponseEntity<AuditLog> createLog(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AuditLogRequest request) {
        Long userId = requireUserId(jwt);
        AuditLog auditLog = auditLogService.save(userId, request.action(), request.module(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(auditLog);
    }

    @PostMapping("/vip/upgrade")
    public ResponseEntity<Subscription> upgradeVip(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VipUpgradeRequest request) {
        Long userId = requireUserId(jwt);
        Subscription subscription = vipService.upgrade(userId, request.planType(), request.amount(), request.durationDays());
        return ResponseEntity.status(HttpStatus.CREATED).body(subscription);
    }

    @GetMapping("/vip/status/{userId}")
    public ResponseEntity<VipStatusResponse> vipStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long userId) {
        Long actorUserId = requireUserId(jwt);
        auditLogService.save(actorUserId, "VIP_STATUS_CHECK", "VIP",
                "Checked VIP status for userId=" + userId);
        return ResponseEntity.ok(vipService.check(userId));
    }

    private Long requireUserId(Jwt jwt) {
        Objects.requireNonNull(jwt, "JWT principal is required");
        Object claim = jwt.getClaims().get("userId");
        String value = claim.toString().trim();
        try {
            long userId = Long.parseLong(value);
            if (userId > 0) {
                return userId;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT token must contain a valid userId claim");
    }
}
