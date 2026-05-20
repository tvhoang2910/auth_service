package com.exam_bank.auth_service.dto.response;

import java.util.List;

public record SubscriptionApprovalAuditLogPageResponse(
        List<SubscriptionApprovalAuditLogResponse> content,
        int totalPages,
        long totalElements) {
}
