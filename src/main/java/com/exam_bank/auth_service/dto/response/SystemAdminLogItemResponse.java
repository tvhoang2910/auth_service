package com.exam_bank.auth_service.dto.response;

import java.time.Instant;

public record SystemAdminLogItemResponse(
        Long id,
        String action,
        String actor,
        String target,
        String targetType,
        String severity,
        Instant createdAt,
        String description) {
}