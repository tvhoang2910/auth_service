package com.exam_bank.auth_service.dto.message;

public record EmailOtpMessage(
        String email,
        String otp,
        String purpose) {
}
