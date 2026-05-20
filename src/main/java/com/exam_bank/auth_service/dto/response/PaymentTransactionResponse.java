package com.exam_bank.auth_service.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.exam_bank.auth_service.entity.SubscriptionStatus;

public record PaymentTransactionResponse(
        Long id,
        Long userId,
        String userEmail,
        String userFullName,
        Long planId,
        String planName,
        BigDecimal amount,
        BigDecimal fee,
        Instant processedAt,
        SubscriptionStatus status,
        String transactionRef,
        boolean trial) {
}