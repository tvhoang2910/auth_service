package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.NotNull;

public record PaymentFeeCalculationRequest(
        @NotNull Long planId,
        boolean trial) {
}
