package com.exam_bank.auth_service.dto.request;

import com.exam_bank.auth_service.entity.Role;
import jakarta.validation.constraints.NotNull;

public record AdminUpdateUserRoleRequest(
        @NotNull Role role) {
}
