package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.entity.SecurityAuditLog;
import com.exam_bank.auth_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<Page<SecurityAuditLog>> getAuditLogs(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Instant from = parseDateTime(fromDate, false);
        Instant to = parseDateTime(toDate, true);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(BAD_REQUEST, "fromDate must be before or equal to toDate");
        }

        Page<SecurityAuditLog> result = auditLogService.search(email, action, outcome, from, to, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long failedToday = auditLogService.countFailedLoginsToday();
        long successToday = auditLogService.countSuccessfulLoginsToday();
        return ResponseEntity.ok(Map.of(
                "failedLoginsToday", failedToday,
                "successfulLoginsToday", successToday,
                "failedLoginsTodayChange", 0 // placeholder — compare with yesterday if needed
        ));
    }

    @GetMapping("/actions")
    public ResponseEntity<Map<String, String>> getActionTypes() {
        return ResponseEntity.ok(auditLogService.getActionLabels());
    }

    private Instant parseDateTime(String raw, boolean endOfDayIfDateOnly) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            // fallback to datetime-local format from browser
        }

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(raw);
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // fallback to date-only format
        }

        try {
            LocalDate localDate = LocalDate.parse(raw);
            if (endOfDayIfDateOnly) {
                return localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusNanos(1);
            }
            return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Invalid date format. Use ISO-8601 (e.g. 2026-04-04T10:30:00Z)");
        }
    }
}
