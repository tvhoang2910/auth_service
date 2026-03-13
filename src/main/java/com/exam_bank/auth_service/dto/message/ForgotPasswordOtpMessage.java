package com.exam_bank.auth_service.dto.message;

public record ForgotPasswordOtpMessage(
        String email,
        String otp) {
}
