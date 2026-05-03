package com.exam_bank.auth_service.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record VipStatusResponse(
        Long userId,
        boolean active,
        String planType,
        String status,
        Instant startDate,
        Instant endDate,
        BigDecimal amount) {
}
