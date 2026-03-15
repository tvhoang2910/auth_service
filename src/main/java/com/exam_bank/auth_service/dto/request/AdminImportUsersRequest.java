package com.exam_bank.auth_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AdminImportUsersRequest(
        @NotEmpty List<@Valid AdminImportUserItemRequest> users,
        Boolean skipExisting) {
}
