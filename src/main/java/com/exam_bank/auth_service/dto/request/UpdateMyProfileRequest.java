package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateMyProfileRequest(
                @Size(min = 3, max = 150) String fullName,
                @Size(max = 500) String avatarUrl,
                @Size(max = 20) String phoneNumber,
                @Size(max = 150) String school,
                @Size(max = 150) String subject,
                @Size(min = 8, max = 72) String currentPassword,
                @Size(min = 8, max = 72) String newPassword) {
}
