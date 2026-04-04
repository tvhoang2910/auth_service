package com.exam_bank.auth_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "security_audit_logs", indexes = {
        @Index(name = "idx_audit_email", columnList = "email"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_outcome", columnList = "outcome"),
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_email_created", columnList = "email, created_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class SecurityAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action", nullable = false, length = 50)
    private String action;   // LOGIN, LOGOUT, UPDATE_PROFILE, etc.

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome; // SUCCESS, FAILURE

    @Column(name = "email", length = 255)
    private String email;   // may be null for anonymous failures

    @Column(name = "ip_address", length = 45)  // IPv6 max 45 chars
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "details", length = 500)
    private String details;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
