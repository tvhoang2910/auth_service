package com.exam_bank.auth_service.dto.response;

import java.util.List;

public record UserNotificationPageResponse(
        List<UserNotificationItemResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        long unreadCount) {
}
