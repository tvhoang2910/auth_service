package com.exam_bank.auth_service.dto.message;

public record PushSubscriptionSyncMessage(
        Long userId,
        String role,
        Boolean userActive,
        String endpoint,
        String p256dh,
        String auth,
        Boolean active) {
}
