package com.exam_bank.auth_service.dto.response;

public record SystemAdminDashboardResponse(
        long totalUsers,
        long totalAdmins,
        long lockedUsers,
        long failedLoginAttempts) {
}