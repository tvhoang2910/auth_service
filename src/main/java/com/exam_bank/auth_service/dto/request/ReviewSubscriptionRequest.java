package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewSubscriptionRequest(
        @NotNull Boolean approved,
        @Size(max = 500) String reviewNote) {
}