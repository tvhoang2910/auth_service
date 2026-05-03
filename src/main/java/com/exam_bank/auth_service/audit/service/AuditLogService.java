package com.exam_bank.auth_service.audit.service;

import com.exam_bank.auth_service.entity.AuditLog;
import com.exam_bank.auth_service.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLog save(Long userId, String action, String module, String description) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(userId);
        auditLog.setAction(normalize(action));
        auditLog.setModule(normalize(module));
        auditLog.setDescription(normalizeDescription(description));
        return auditLogRepository.save(auditLog);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(Long userId, String module, String action, Pageable pageable) {
        return auditLogRepository.searchByUserId(userId, normalizeFilter(module), normalizeFilter(action), pageable);
    }

    private String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeFilter(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
