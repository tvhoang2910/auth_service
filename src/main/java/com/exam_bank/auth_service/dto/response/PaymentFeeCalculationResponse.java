package com.exam_bank.auth_service.dto.response;

import java.math.BigDecimal;

public record PaymentFeeCalculationResponse(
        Long planId,
        String planName,
        BigDecimal baseAmount,
        BigDecimal platformFee,
        BigDecimal totalAmount,
        BigDecimal payableAmount,
        boolean trial) {
}
