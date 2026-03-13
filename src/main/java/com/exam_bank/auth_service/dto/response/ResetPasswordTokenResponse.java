package com.exam_bank.auth_service.dto.response;

public record ResetPasswordTokenResponse(
        String resetToken,
        String message) {
}
