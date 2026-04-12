package com.exam_bank.auth_service.dto.request;

public record UpdateNotificationPreferencesRequest(
        Boolean emailEnabled,
        Boolean webPushEnabled) {
}
