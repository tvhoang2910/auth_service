package com.exam_bank.auth_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "user_subscriptions", indexes = {
        @Index(name = "idx_user_subscriptions_user_id", columnList = "user_id"),
        @Index(name = "idx_user_subscriptions_plan_id", columnList = "plan_id"),
        @Index(name = "idx_user_subscriptions_transaction_ref", columnList = "transaction_ref")
})
public class UserSubscription extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private PremiumPlan plan;

    @NotNull
    @Column(name = "purchased_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal purchasedPrice;

    @NotNull
    @Column(name = "start_date", nullable = false)
    private Instant startDate;

    @NotNull
    @Column(name = "end_date", nullable = false)
    private Instant endDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private SubscriptionStatus status;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_by_email", length = 255)
    private String cancelledByEmail;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expiry_reminder_sent_at")
    private Instant expiryReminderSentAt;

    @Column(name = "refunded_amount", precision = 12, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "bill_image_url", length = 255)
    private String billImageUrl;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "transaction_ref", length = 100)
    private String transactionRef;

    @Column(name = "promo_code", length = 50)
    private String promoCode;

    @Column(name = "is_trial", nullable = false)
    private boolean trial = false;

    @OneToMany(mappedBy = "subscription")
    private List<SubscriptionApprovalAudit> approvalAudits = new ArrayList<>();
}
