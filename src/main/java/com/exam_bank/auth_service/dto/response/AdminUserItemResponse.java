package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.Role;

import java.time.Instant;

public record AdminUserItemResponse(
        Long id,
        String email,
        String fullName,
        String avatarUrl,
        String phoneNumber,
        String school,
        String subject,
        Role role,
        boolean status,
        int statusCode,
        String statusReason,
        String statusChangedBy,
        Instant createdAt) {
}
