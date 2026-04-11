package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelSubscriptionRequest(
        @NotBlank @Size(max = 500) String reason) {
}
