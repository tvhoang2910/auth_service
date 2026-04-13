package com.exam_bank.auth_service.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "auth.bootstrap-admin")
public class AuthBootstrapAdminProperties {

    private boolean enabled = false;

    private String email;

    private String password;

    private String fullName = "System Administrator";

    private boolean updatePassword = false;
}
