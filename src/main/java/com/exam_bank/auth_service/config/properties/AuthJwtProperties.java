package com.exam_bank.auth_service.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auth.jwt")
public class AuthJwtProperties {

    @NotBlank
    private String issuer;

    @Min(60)
    private long expirationSeconds;

    @NotBlank
    private String secret;
}
