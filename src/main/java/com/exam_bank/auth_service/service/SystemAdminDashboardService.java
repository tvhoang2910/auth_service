package com.exam_bank.auth_service.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exam_bank.auth_service.dto.response.SystemAdminDashboardResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.repository.SecurityAuditLogRepository;
import com.exam_bank.auth_service.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemAdminDashboardService {

    private final UserRepository userRepository;
    private final SecurityAuditLogRepository securityAuditLogRepository;

    public SystemAdminDashboardResponse getDashboard() {
        long totalUsers = userRepository.count();
        long totalAdmins = userRepository.countByRoleIn(List.of(Role.ADMIN, Role.AUDIT, Role.SYSTEM_ADMIN));
        long lockedUsers = userRepository.countByStatusFalse();
        long failedLoginAttempts = securityAuditLogRepository.countByActionAndOutcome("LOGIN", "FAILURE");
        return new SystemAdminDashboardResponse(totalUsers, totalAdmins, lockedUsers, failedLoginAttempts);
    }
}