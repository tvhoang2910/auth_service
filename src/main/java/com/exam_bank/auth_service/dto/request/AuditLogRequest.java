package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuditLogRequest(
        @NotBlank String action,
        @NotBlank String module,
        @Size(max = 1000) String description) {
}
