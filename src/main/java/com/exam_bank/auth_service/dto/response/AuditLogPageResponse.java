package com.exam_bank.auth_service.dto.response;

import com.exam_bank.auth_service.entity.SecurityAuditLog;
import org.springframework.data.domain.Page;

import java.util.List;

public record AuditLogPageResponse(
        List<SecurityAuditLog> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static AuditLogPageResponse from(Page<SecurityAuditLog> page) {
        return new AuditLogPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
