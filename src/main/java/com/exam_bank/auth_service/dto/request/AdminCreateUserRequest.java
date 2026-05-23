package com.exam_bank.auth_service.dto.request;

import com.exam_bank.auth_service.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminCreateUserRequest(
                @Email @NotBlank @Size(max = 255) String email,
                @NotBlank @Size(max = 150) String fullName,
                Role role) {
}
