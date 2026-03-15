package com.exam_bank.auth_service.dto.response;

public record AdminImportUserErrorResponse(
        int index,
        String email,
        String reason) {
}
