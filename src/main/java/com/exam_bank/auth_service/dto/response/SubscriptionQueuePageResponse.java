package com.exam_bank.auth_service.dto.response;

import java.util.List;

public record SubscriptionQueuePageResponse(
        List<UserSubscriptionQueueItemResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {
}