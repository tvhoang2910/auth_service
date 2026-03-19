package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.SubscriptionReviewDecision;
import com.exam_bank.auth_service.entity.SubscriptionStatus;

import java.time.Instant;

public record SubscriptionApprovalAuditResponse(
        Long id,
        Long subscriptionId,
        Long reviewerId,
        String reviewerEmail,
        Role reviewerRole,
        SubscriptionReviewDecision decision,
        SubscriptionStatus previousStatus,
        SubscriptionStatus newStatus,
        String reviewNote,
        Instant reviewedAt,
        boolean notificationDispatched,
        String sourceChannel) {
}