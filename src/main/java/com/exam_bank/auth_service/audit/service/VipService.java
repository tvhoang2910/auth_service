package com.exam_bank.auth_service.audit.service;

import com.exam_bank.auth_service.dto.response.VipStatusResponse;
import com.exam_bank.auth_service.entity.Subscription;
import com.exam_bank.auth_service.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class VipService {

    private static final String VIP_EXPIRED_STATUS = "EXPIRED";
    private static final String VIP_INACTIVE_STATUS = "INACTIVE";

    private final BillingService billingService;
    private final SubscriptionRepository subscriptionRepository;
    private final AuditLogService auditLogService;

    public Subscription upgrade(Long userId, String planType, BigDecimal amount, Integer durationDays) {
        int safeDurationDays = durationDays == null || durationDays <= 0 ? 30 : durationDays;
        Instant now = Instant.now();
        Instant endDate = now.plus(Duration.ofDays(safeDurationDays));

        Subscription subscription = billingService.createInvoice(userId, planType, amount, now, endDate);
        auditLogService.save(userId, "VIP_UPGRADE", "VIP",
                "Upgraded to plan " + planType + " for " + safeDurationDays + " days");
        return subscription;
    }

    @Transactional(readOnly = true)
    public VipStatusResponse check(Long userId) {
        Subscription subscription = subscriptionRepository.findTopByUserIdOrderByStartDateDesc(userId).orElse(null);
        if (subscription == null) {
            return new VipStatusResponse(userId, false, null, VIP_INACTIVE_STATUS, null, null, null);
        }

        boolean active = BillingService.VIP_ACTIVE_STATUS.equalsIgnoreCase(subscription.getStatus())
                && subscription.getEndDate() != null
                && subscription.getEndDate().isAfter(Instant.now());
        String status = active ? BillingService.VIP_ACTIVE_STATUS : VIP_EXPIRED_STATUS;
        return new VipStatusResponse(
                userId,
                active,
                subscription.getPlanType(),
                status,
                subscription.getStartDate(),
                subscription.getEndDate(),
                subscription.getAmount());
    }

    public Subscription expire(Long userId) {
        Subscription subscription = subscriptionRepository.findTopByUserIdOrderByStartDateDesc(userId)
                .orElseThrow(() -> new IllegalArgumentException("VIP subscription not found"));

        subscription.setStatus(VIP_EXPIRED_STATUS);
        Subscription saved = subscriptionRepository.save(subscription);
        auditLogService.save(userId, "VIP_EXPIRE", "VIP", "VIP expired manually or by scheduled process");
        return saved;
    }
}
