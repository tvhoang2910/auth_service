package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record SubscriptionHistoryItemResponse(
        Long id,
        Long userId,
        String userEmail,
        String userFullName,
        Long planId,
        String planName,
        BigDecimal purchasedPrice,
        SubscriptionStatus status,
        String billImageUrl,
        String paymentMethod,
        String transactionRef,
        String promoCode,
        boolean trial,
        Instant startDate,
        Instant endDate,
        Instant createdAt,
        String cancellationReason,
        String cancelledByEmail,
        Instant cancelledAt,
        BigDecimal refundedAmount) {
}
