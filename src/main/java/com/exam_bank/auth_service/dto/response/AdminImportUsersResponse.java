package com.exam_bank.auth_service.dto.response;

import java.util.List;

public record AdminImportUsersResponse(
        int total,
        int created,
        int skipped,
        int failed,
        List<AdminImportUserErrorResponse> errors) {
}
