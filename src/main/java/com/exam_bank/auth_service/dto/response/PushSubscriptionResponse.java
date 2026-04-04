package com.exam_bank.auth_service.dto.response;

import java.time.Instant;

public record PushSubscriptionResponse(
    Long id,
    String endpoint,
    Instant createdAt
) {}
