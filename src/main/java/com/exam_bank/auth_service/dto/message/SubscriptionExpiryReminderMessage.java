package com.exam_bank.auth_service.dto.message;

import java.math.BigDecimal;

public record SubscriptionExpiryReminderMessage(
        Long subscriptionId,
        Long subscriberUserId,
        String userEmail,
        String userFullName,
        String planName,
        BigDecimal purchasedPrice,
        String expiresAt,
        String remindedAt) {
}
