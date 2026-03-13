package com.exam_bank.auth_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SecurityAuditService {

    public void success(String action, String email, String details) {
        log.info("SECURITY_AUDIT action={} outcome=SUCCESS email={} details={}",
                action,
                normalizeEmail(email),
                details == null ? "-" : details);
    }

    public void failure(String action, String email, String details) {
        log.warn("SECURITY_AUDIT action={} outcome=FAILURE email={} details={}",
                action,
                normalizeEmail(email),
                details == null ? "-" : details);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return "anonymous";
        }
        return email.trim().toLowerCase();
    }
}
