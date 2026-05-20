package com.exam_bank.auth_service.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record PaymentStatsResponse(
        BigDecimal totalAmount,
        BigDecimal totalFee,
        long count,
        List<PaymentUserStatsResponse> byUser) {
}
