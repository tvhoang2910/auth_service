package com.exam_bank.auth_service.dto.internal;

public record PushSubscriptionDto(
    String endpoint,
    String p256dh,
    String auth
) {}
