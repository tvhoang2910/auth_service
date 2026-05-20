package com.exam_bank.auth_service.dto.response;

import java.time.Instant;

public record SubscriptionApprovalAuditLogResponse(
        Long userId,
        String userName,
        String userEmail,
        String action,
        Long targetId,
        String targetName,
        String targetEmail,
        String oldValue,
        String newValue,
        Instant createdAt) {
}
