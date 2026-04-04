package com.exam_bank.auth_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_push_subscriptions", indexes = {
        @Index(name = "idx_push_sub_user_id", columnList = "user_id"),
        @Index(name = "idx_push_sub_active", columnList = "active")
})
public class UserPushSubscription extends BaseEntity {

    @NotNull
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @NotNull
    @Column(name = "endpoint", nullable = false, unique = true, columnDefinition = "TEXT")
    private String endpoint;

    @NotNull
    @Column(name = "p256dh", nullable = false, columnDefinition = "TEXT")
    private String p256dh;

    @NotNull
    @Column(name = "auth", nullable = false, columnDefinition = "TEXT")
    private String auth;

    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
