package com.exam_bank.auth_service.dto.response;

public record SystemAdminServiceStatusItemResponse(
        long id,
        String name,
        String status,
        int port,
        String heartbeat,
        String responseTime,
        String updatedAt) {
}
