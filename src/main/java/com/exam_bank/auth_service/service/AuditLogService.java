package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.entity.SecurityAuditLog;
import com.exam_bank.auth_service.repository.SecurityAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AuditLogService {

    private final SecurityAuditLogRepository auditLogRepository;

    public Page<SecurityAuditLog> search(
            String email,
            String action,
            String outcome,
            Instant fromDate,
            Instant toDate,
            Pageable pageable) {
        String normalizedEmail = normalize(email);
        String normalizedAction = normalizeUpper(action);
        String normalizedOutcome = normalizeUpper(outcome);
        boolean useEmail = normalizedEmail != null;
        boolean useAction = normalizedAction != null;
        boolean useOutcome = normalizedOutcome != null;
        boolean useFromDate = fromDate != null;
        boolean useToDate = toDate != null;

        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate must be before or equal to toDate");
        }

        return auditLogRepository.search(
                useEmail,
                useEmail ? normalizedEmail : "",
                useAction,
                useAction ? normalizedAction : "",
                useOutcome,
                useOutcome ? normalizedOutcome : "",
                useFromDate,
                useFromDate ? fromDate : Instant.EPOCH,
                useToDate,
                useToDate ? toDate : Instant.EPOCH,
                pageable);
    }

    public List<SecurityAuditLog> getRecentByEmail(String email, int limit) {
        return auditLogRepository.findByEmailOrderByCreatedAtDesc(email,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    public long countByActionAndOutcome(String action, String outcome) {
        return auditLogRepository.countByActionAndOutcome(action, outcome);
    }

    public long countFailedLoginsToday() {
        Instant start = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        return auditLogRepository.countByActionAndOutcomeAndCreatedAtBetween(
                "LOGIN", "FAILURE", start, Instant.now());
    }

    public long countSuccessfulLoginsToday() {
        Instant start = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC);
        return auditLogRepository.countByActionAndOutcomeAndCreatedAtBetween(
                "LOGIN", "SUCCESS", start, Instant.now());
    }

    public Map<String, String> getActionLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("LOGIN", "Đăng nhập");
        labels.put("LOGOUT", "Đăng xuất");
        labels.put("REFRESH", "Làm mới token");
        labels.put("FORGOT_PASSWORD", "Quên mật khẩu");
        labels.put("VERIFY_OTP", "Xác minh OTP reset");
        labels.put("RESET_PASSWORD", "Đặt lại mật khẩu");
        labels.put("VERIFY_EMAIL", "Xác minh email đăng ký");
        labels.put("UPDATE_PROFILE", "Cập nhật hồ sơ");
        labels.put("UPLOAD_AVATAR", "Upload avatar");
        labels.put("CANCEL_SUBSCRIPTION", "Hủy gói Premium");

        for (String action : auditLogRepository.findDistinctActions()) {
            labels.putIfAbsent(action, action);
        }

        return labels;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeUpper(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
