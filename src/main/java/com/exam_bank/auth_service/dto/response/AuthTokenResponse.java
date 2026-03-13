package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.Role;
import lombok.Builder;

@Builder
public record AuthTokenResponse(
                String accessToken,
                String refreshToken,
                String tokenType,
                long expiresIn,
                long refreshExpiresIn,
                String email,
                Role role) {
}
