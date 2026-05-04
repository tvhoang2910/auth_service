package com.exam_bank.auth_service.dto.response;

import java.math.BigDecimal;

public record PaymentUserStatsResponse(
        Long userId,
        String userEmail,
        long transactionCount,
        BigDecimal totalAmount) {
}
