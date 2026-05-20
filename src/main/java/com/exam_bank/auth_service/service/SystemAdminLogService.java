package com.exam_bank.auth_service.service;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.util.StringUtils.hasText;

import com.exam_bank.auth_service.dto.response.SystemAdminLogItemResponse;
import com.exam_bank.auth_service.dto.response.SystemAdminLogPageResponse;
import com.exam_bank.auth_service.entity.SecurityAuditLog;
import com.exam_bank.auth_service.repository.SecurityAuditLogRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemAdminLogService {

    private final SecurityAuditLogRepository securityAuditLogRepository;

    public SystemAdminLogPageResponse getLogs(String search, String action, String outcome, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Order.desc("createdAt")));

        Specification<SecurityAuditLog> specification = (root, query, cb) -> cb.conjunction();
        if (hasText(search)) {
            String keyword = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("action")), keyword),
                    cb.like(cb.lower(cb.coalesce(root.get("email"), "")), keyword),
                    cb.like(cb.lower(cb.coalesce(root.get("details"), "")), keyword),
                    cb.like(cb.lower(cb.coalesce(root.get("ipAddress"), "")), keyword),
                    cb.like(cb.lower(cb.coalesce(root.get("userAgent"), "")), keyword)));
        }

        if (hasText(action)) {
            String normalizedAction = action.trim();
            specification = specification.and((root, query, cb) -> cb.equal(root.get("action"), normalizedAction));
        }

        if (hasText(outcome)) {
            String normalizedOutcome = outcome.trim().toUpperCase(Locale.ROOT);
            specification = specification.and((root, query, cb) -> cb.equal(root.get("outcome"), normalizedOutcome));
        }

        Page<SystemAdminLogItemResponse> result = securityAuditLogRepository.findAll(specification, pageable)
                .map(this::mapToResponse);
        return new SystemAdminLogPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast());
    }

    private SystemAdminLogItemResponse mapToResponse(SecurityAuditLog log) {
        String actor = hasText(log.getEmail()) ? log.getEmail() : "anonymous";
        String action = log.getAction();
        String severity = "FAILURE".equalsIgnoreCase(log.getOutcome()) ? "ERROR" : "INFO";
        String targetType = resolveTargetType(action);
        String target = resolveTarget(log);
        String description = hasText(log.getDetails()) ? log.getDetails() : action;
        return new SystemAdminLogItemResponse(
                log.getId(),
                action,
                actor,
                target,
                targetType,
                severity,
                log.getCreatedAt(),
                description);
    }

    private String resolveTarget(SecurityAuditLog log) {
        if (hasText(log.getDetails())) {
            String details = log.getDetails();
            String targetEmail = extractValue(details, "targetEmail=");
            if (hasText(targetEmail)) {
                return targetEmail;
            }
            String targetUser = extractValue(details, "targetUser=");
            if (hasText(targetUser)) {
                return targetUser;
            }
            return details;
        }

        if (hasText(log.getEmail())) {
            return log.getEmail();
        }

        return "-";
    }

    private String resolveTargetType(String action) {
        if (!hasText(action)) {
            return "SYSTEM";
        }

        String normalized = action.toUpperCase(Locale.ROOT);
        if (normalized.contains("USER") || "CHANGE_ROLE".equals(normalized)) {
            return "USER";
        }
        if (normalized.contains("LOGIN") || normalized.contains("LOGOUT") || normalized.contains("REFRESH")
                || normalized.contains("PASSWORD") || normalized.contains("OTP") || normalized.contains("PROFILE")
                || normalized.contains("AVATAR")) {
            return "AUTH";
        }
        return "SYSTEM";
    }

    private String extractValue(String details, String keyPrefix) {
        int start = details.indexOf(keyPrefix);
        if (start < 0) {
            return null;
        }
        int valueStart = start + keyPrefix.length();
        int end = details.indexOf(';', valueStart);
        String value = end < 0 ? details.substring(valueStart) : details.substring(valueStart, end);
        return hasText(value) ? value.trim() : null;
    }
}