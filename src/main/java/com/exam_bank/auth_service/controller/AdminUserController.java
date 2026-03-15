package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.request.AdminCreateUserRequest;
import com.exam_bank.auth_service.dto.request.AdminImportUsersRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserRoleRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserStatusRequest;
import com.exam_bank.auth_service.dto.response.AdminImportUsersResponse;
import com.exam_bank.auth_service.dto.response.AdminUserItemResponse;
import com.exam_bank.auth_service.dto.response.AdminUsersPageResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<AdminUsersPageResponse> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Role role,
            Pageable pageable) {
        Page<AdminUserItemResponse> response = adminUserService.getUsers(search, role, pageable);
        AdminUsersPageResponse body = new AdminUsersPageResponse(
                response.getContent(),
                response.getNumber(),
                response.getSize(),
                response.getTotalElements(),
                response.getTotalPages(),
                response.isFirst(),
                response.isLast());
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<AdminUserItemResponse> createUser(@Valid @RequestBody AdminCreateUserRequest request) {
        AdminUserItemResponse response = adminUserService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<AdminUserItemResponse> updateUserStatus(
            @PathVariable Long id,
            Authentication authentication,
            @Valid @RequestBody AdminUpdateUserStatusRequest request) {
        AdminUserItemResponse response = adminUserService.updateUserStatus(id, request, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<AdminUserItemResponse> updateUserRole(
            @PathVariable Long id,
            @Valid @RequestBody AdminUpdateUserRoleRequest request) {
        AdminUserItemResponse response = adminUserService.updateUserRole(id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/import-json")
    public ResponseEntity<AdminImportUsersResponse> importUsers(@Valid @RequestBody AdminImportUsersRequest request) {
        AdminImportUsersResponse response = adminUserService.importUsers(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
