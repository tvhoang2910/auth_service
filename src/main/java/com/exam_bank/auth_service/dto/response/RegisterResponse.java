package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.Role;

import lombok.Builder;

import java.time.Instant;

@Builder
public record RegisterResponse(
        Long id,
        String email,
        String fullName,
        Role role,
        Instant createdAt) {
}
