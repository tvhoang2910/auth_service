package com.exam_bank.auth_service.dto.response;

public record NotificationPreferenceResponse(
        boolean emailEnabled,
        boolean webPushEnabled) {
}
