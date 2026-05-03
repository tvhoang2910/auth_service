package com.exam_bank.auth_service.audit.service;

import com.exam_bank.auth_service.entity.Subscription;
import com.exam_bank.auth_service.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class BillingService {

    public static final String VIP_ACTIVE_STATUS = "ACTIVE";

    private final SubscriptionRepository subscriptionRepository;

    public Subscription createInvoice(Long userId, String planType, BigDecimal amount, Instant startDate, Instant endDate) {
        Subscription subscription = new Subscription();
        subscription.setUserId(userId);
        subscription.setPlanType(planType.trim());
        subscription.setStatus(VIP_ACTIVE_STATUS);
        subscription.setStartDate(startDate);
        subscription.setEndDate(endDate);
        subscription.setAmount(amount);
        return subscriptionRepository.save(subscription);
    }
}
