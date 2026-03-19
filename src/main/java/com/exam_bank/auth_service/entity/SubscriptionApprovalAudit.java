package com.exam_bank.auth_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "subscription_approval_audits", indexes = {
        @Index(name = "idx_sub_approval_audits_subscription_id", columnList = "subscription_id"),
        @Index(name = "idx_sub_approval_audits_reviewer_id", columnList = "reviewer_id"),
        @Index(name = "idx_sub_approval_audits_reviewed_at", columnList = "reviewed_at")
})
public class SubscriptionApprovalAudit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private UserSubscription subscription;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewer_role", nullable = false, length = 30)
    private Role reviewerRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 30)
    private SubscriptionReviewDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 50)
    private SubscriptionStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 50)
    private SubscriptionStatus newStatus;

    @Size(max = 500)
    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "notification_dispatched", nullable = false)
    private boolean notificationDispatched = false;

    @Column(name = "source_channel", nullable = false, length = 30)
    private String sourceChannel = "MANUAL_REVIEW";
}