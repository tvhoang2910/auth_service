package com.exam_bank.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuth2ExchangeRequest {

    @NotBlank(message = "OAuth2 exchange code must not be blank")
    private String code;
}