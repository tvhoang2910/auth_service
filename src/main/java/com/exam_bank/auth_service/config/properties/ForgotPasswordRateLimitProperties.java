package com.exam_bank.auth_service.config.properties;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auth.forgot-password.rate-limit")
public class ForgotPasswordRateLimitProperties {

    @Min(1)
    private long forgotMaxAttempts = 5;

    @Min(10)
    private long forgotWindowSeconds = 300;

    @Min(1)
    private long verifyMaxAttempts = 10;

    @Min(10)
    private long verifyWindowSeconds = 300;

    @Min(1)
    private long resendMaxAttempts = 3;

    @Min(10)
    private long resendWindowSeconds = 300;
}
