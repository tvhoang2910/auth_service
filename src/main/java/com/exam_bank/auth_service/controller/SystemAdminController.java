package com.exam_bank.auth_service.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.exam_bank.auth_service.dto.request.AdminUpdateUserRoleRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserStatusRequest;
import com.exam_bank.auth_service.dto.response.AdminUserItemResponse;
import com.exam_bank.auth_service.dto.response.AdminUsersPageResponse;
import com.exam_bank.auth_service.dto.response.SystemAdminDashboardResponse;
import com.exam_bank.auth_service.dto.response.SystemAdminLogPageResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.service.AdminUserService;
import com.exam_bank.auth_service.service.SystemAdminDashboardService;
import com.exam_bank.auth_service.service.SystemAdminLogService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/system-admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemAdminController {

    private final AdminUserService adminUserService;
    private final SystemAdminDashboardService systemAdminDashboardService;
    private final SystemAdminLogService systemAdminLogService;

    @GetMapping("/users")
    public ResponseEntity<AdminUsersPageResponse> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Boolean activeStatus = parseStatus(status);
        Page<AdminUserItemResponse> response = adminUserService.getUsers(
                search,
                role,
                activeStatus,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Order.desc("createdAt"))));
        return ResponseEntity.ok(new AdminUsersPageResponse(
                response.getContent(),
                response.getNumber(),
                response.getSize(),
                response.getTotalElements(),
                response.getTotalPages(),
                response.isFirst(),
                response.isLast()));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<AdminUserItemResponse> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(adminUserService.getUserById(id));
    }

    @PutMapping("/users/{id}/lock")
    public ResponseEntity<AdminUserItemResponse> lockUser(
            @PathVariable Long id,
            Authentication authentication) {
        AdminUserItemResponse response = adminUserService.updateUserStatus(
                id,
                new AdminUpdateUserStatusRequest(0, false, "Locked by SYSTEM_ADMIN"),
                authentication == null ? null : authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/users/{id}/unlock")
    public ResponseEntity<AdminUserItemResponse> unlockUser(
            @PathVariable Long id,
            Authentication authentication) {
        AdminUserItemResponse response = adminUserService.updateUserStatus(
                id,
                new AdminUpdateUserStatusRequest(1, true, "Unlocked by SYSTEM_ADMIN"),
                authentication == null ? null : authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<AdminUserItemResponse> updateUserRole(
            @PathVariable Long id,
            Authentication authentication,
            @Valid @RequestBody AdminUpdateUserRoleRequest request) {
        AdminUserItemResponse response = adminUserService.updateUserRole(
                id,
                request,
                authentication == null ? null : authentication.getName());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/logs")
    public ResponseEntity<SystemAdminLogPageResponse> getLogs(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(systemAdminLogService.getLogs(search, action, outcome, page, size));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<SystemAdminDashboardResponse> getDashboard() {
        return ResponseEntity.ok(systemAdminDashboardService.getDashboard());
    }

    private Boolean parseStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        if ("ACTIVE".equalsIgnoreCase(status)) {
            return Boolean.TRUE;
        }
        if ("LOCKED".equalsIgnoreCase(status)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Status must be ACTIVE, LOCKED or ALL");
    }
}