package com.exam_bank.auth_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email", unique = true)
})
public class User extends BaseEntity {

    @Email
    @NotBlank
    @Size(max = 255)
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password")
    private String password;

    @NotBlank
    @Size(max = 150)
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role = Role.USER;

    @Column(name = "status", nullable = false, columnDefinition = "boolean default true")
    private boolean status = true;

    @Size(max = 500)
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Size(max = 20)
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Size(max = 150)
    @Column(name = "school", length = 150)
    private String school;

    @Size(max = 150)
    @Column(name = "subject", length = 150)
    private String subject;

    @Size(max = 255)
    @Column(name = "status_reason", length = 255)
    private String statusReason;

    @Size(max = 255)
    @Column(name = "status_changed_by", length = 255)
    private String statusChangedBy;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;
}
