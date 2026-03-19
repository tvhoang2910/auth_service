package com.exam_bank.auth_service.dto.response;

import java.math.BigDecimal;

public record PremiumPlanSummaryResponse(
                Long id,
                String name,
                BigDecimal price,
                Integer durationDays,
                boolean lifetime,
                String description,
                boolean active) {
}