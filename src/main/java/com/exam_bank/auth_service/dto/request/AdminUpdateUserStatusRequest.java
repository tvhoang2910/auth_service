package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminUpdateUserStatusRequest(
                Integer status,
                Boolean active,
                @NotBlank @Size(max = 255) String reason) {
}
