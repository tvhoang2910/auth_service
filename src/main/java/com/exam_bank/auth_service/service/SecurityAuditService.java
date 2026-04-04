package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.entity.SecurityAuditLog;
import com.exam_bank.auth_service.repository.SecurityAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
public class SecurityAuditService {

    private final SecurityAuditLogRepository auditLogRepository;

    public SecurityAuditService(SecurityAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void success(String action, String email, String details) {
        log.info("SECURITY_AUDIT action={} outcome=SUCCESS email={} details={}",
                action,
                normalizeEmail(email),
                details == null ? "-" : details);
        save(action, "SUCCESS", email, details);
    }

    public void failure(String action, String email, String details) {
        log.warn("SECURITY_AUDIT action={} outcome=FAILURE email={} details={}",
                action,
                normalizeEmail(email),
                details == null ? "-" : details);
        save(action, "FAILURE", email, details);
    }

    private void save(String action, String outcome, String email, String details) {
        try {
            SecurityAuditLog entry = new SecurityAuditLog();
            entry.setAction(action);
            entry.setOutcome(outcome);
            entry.setEmail(email != null ? email.trim().toLowerCase() : null);
            entry.setDetails(details);
            extractRequestInfo(entry);
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Never let audit logging failure break the main flow
            log.error("Failed to persist security audit log: action={}, outcome={}, email={}", action, outcome, email, e);
        }
    }

    private void extractRequestInfo(SecurityAuditLog entry) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest request = attrs.getRequest();
            entry.setIpAddress(resolveIpAddress(request));
            entry.setUserAgent(truncate(request.getHeader("User-Agent"), 500));
        } catch (Exception e) {
            // ignore — non-critical
        }
    }

    private String resolveIpAddress(HttpServletRequest request) {
        // Check common proxy headers
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                // X-Forwarded-For can have multiple IPs: client, proxy1, proxy2
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return "anonymous";
        }
        return email.trim().toLowerCase();
    }
}
