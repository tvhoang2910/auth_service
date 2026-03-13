package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@SuppressWarnings("java:S1220")
public record ForgotPasswordRequest(
        @NotBlank @Email String email) {
}
