package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePremiumPlanRequest(
                @NotBlank @Size(max = 100) String name,
                @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
                @Min(1) Integer durationDays,
                Boolean lifetime,
                @Size(max = 2000) String description,
                Boolean active) {
}