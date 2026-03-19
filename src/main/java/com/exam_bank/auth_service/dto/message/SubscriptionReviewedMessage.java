package com.exam_bank.auth_service.dto.message;

import java.math.BigDecimal;

public record SubscriptionReviewedMessage(
        Long subscriptionId,
        String userEmail,
        String userFullName,
        String planName,
        BigDecimal purchasedPrice,
        String decision,
        String reviewedBy,
        String reviewNote,
        String reviewedAt) {
}