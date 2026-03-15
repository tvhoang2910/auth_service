package com.exam_bank.auth_service.dto.message;

public record AccountLockedMessage(
        String email,
        String fullName,
        String reason,
        String changedBy,
        String changedAt) {
}
