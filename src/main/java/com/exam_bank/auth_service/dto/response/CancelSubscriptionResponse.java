package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record CancelSubscriptionResponse(
        Long subscriptionId,
        SubscriptionStatus previousStatus,
        SubscriptionStatus currentStatus,
        String reason,
        String refundPolicy,
        BigDecimal refundRate,
        BigDecimal refundAmount,
        Instant cancelledAt) {
}
