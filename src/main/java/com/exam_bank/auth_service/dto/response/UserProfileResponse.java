package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.Role;
import lombok.Builder;

@Builder
public record UserProfileResponse(
                Long id,
                String email,
                String fullName,
                String avatarUrl,
                String phoneNumber,
                String school,
                String subject,
                Role role,
                boolean premium) {
}
