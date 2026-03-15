package com.exam_bank.auth_service.dto.message;

public record AccountUnlockedMessage(
        String email,
        String fullName,
        String reason,
        String changedBy,
        String changedAt) {
}
