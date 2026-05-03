package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record VipUpgradeRequest(
        @NotBlank String planType,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal amount,
        @NotNull @Positive Integer durationDays) {
}
