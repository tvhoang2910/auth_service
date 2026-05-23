package com.exam_bank.auth_service.dto.message;

public record UserProfileSyncMessage(
        Long userId,
        String fullName,
        String avatarUrl,
        Boolean premium,
        String role,
        Boolean active) {
}
