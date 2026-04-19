package com.exam_bank.auth_service.config.properties;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auth.notification")
public class NotificationRabbitProperties {

    @NotBlank
    private String exchange;

    @NotBlank
    private String emailOtpRoutingKey;

    @NotBlank
    private String emailAccountLockedRoutingKey;

    @NotBlank
    private String emailAccountUnlockedRoutingKey;

    @NotBlank
    private String emailQueue;

    @NotBlank
    private String emailSubscriptionReviewRoutingKey;

    @NotBlank
    private String webPushSubscriptionReviewRoutingKey;

    @NotBlank
    private String emailSubscriptionReviewedRoutingKey;

    @NotBlank
    private String webPushSubscriptionReviewedRoutingKey;

    @NotBlank
    private String emailSubscriptionExpiryReminderRoutingKey;

    @NotBlank
    private String webPushSubscriptionExpiryReminderRoutingKey;

    @NotBlank
    private String adminAlertRoutingKey;

    @NotBlank
    private String inAppAdminAlertQueue;
}
