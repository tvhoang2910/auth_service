package com.exam_bank.auth_service.config.properties;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auth.forgot-password")
public class AuthForgotPasswordProperties {

    @Min(60)
    private long otpTtlSeconds;
}
