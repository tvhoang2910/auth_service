package com.exam_bank.auth_service.dto.response;

import java.time.Instant;

public record UserNotificationItemResponse(
        Long id,
        String type,
        String title,
        String message,
        String actionUrl,
        boolean read,
        Instant createdAt,
        Instant readAt) {
}
