package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePremiumPlanRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal price,
        Integer durationDays,
        Boolean lifetime,
        @Size(max = 2000) String description,
        Boolean active) {

    @AssertTrue(message = "durationDays must be greater than or equal to 1")
    public boolean isDurationDaysValid() {
        if (Boolean.TRUE.equals(lifetime)) {
            return true;
        }
        return durationDays == null || durationDays >= 1;
    }
}