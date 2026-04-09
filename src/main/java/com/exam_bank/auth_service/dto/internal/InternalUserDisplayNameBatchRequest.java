package com.exam_bank.auth_service.dto.internal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class InternalUserDisplayNameBatchRequest {

    @NotEmpty(message = "userIds must not be empty")
    private List<@NotNull @Positive Long> userIds;
}
