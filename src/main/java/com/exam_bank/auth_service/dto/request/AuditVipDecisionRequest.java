package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.Size;

public record AuditVipDecisionRequest(
        @Size(max = 500) String reviewNote) {
}
