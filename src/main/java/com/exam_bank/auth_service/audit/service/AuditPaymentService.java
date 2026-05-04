package com.exam_bank.auth_service.audit.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.exam_bank.auth_service.dto.response.PaymentFeeCalculationResponse;
import com.exam_bank.auth_service.dto.response.PaymentStatsResponse;
import com.exam_bank.auth_service.dto.response.PaymentTransactionResponse;
import com.exam_bank.auth_service.dto.response.PaymentUserStatsResponse;
import com.exam_bank.auth_service.entity.PremiumPlan;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.UserSubscription;
import com.exam_bank.auth_service.repository.PremiumPlanRepository;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditPaymentService {

    private static final BigDecimal PLATFORM_FEE_RATE = new BigDecimal("0.03");

    private final PremiumPlanRepository premiumPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

    public PaymentFeeCalculationResponse calculateFee(Long planId, boolean trial) {
        PremiumPlan plan = premiumPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Premium plan not found"));
        BigDecimal baseAmount = plan.getPrice();
        BigDecimal platformFee = trial
                ? BigDecimal.ZERO
                : baseAmount.multiply(PLATFORM_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = trial
                ? BigDecimal.ZERO
                : baseAmount.add(platformFee).setScale(2, RoundingMode.HALF_UP);

        return new PaymentFeeCalculationResponse(
                plan.getId(),
                plan.getName(),
                baseAmount,
                platformFee,
                totalAmount,
                totalAmount,
                trial);
    }

    public Page<PaymentTransactionResponse> getPaymentTransactions(
            String search,
            SubscriptionStatus status,
            Instant from,
            Instant to,
            Pageable pageable) {
        return userSubscriptionRepository.searchPaymentTransactions(
                search,
                status,
                from != null,
                from == null ? Instant.EPOCH : from,
                to != null,
                to == null ? Instant.EPOCH : to,
                pageable).map(this::toPaymentTransactionResponse);
    }

    public PaymentStatsResponse summarizePayments(String search, SubscriptionStatus status, Instant from, Instant to) {
        long totalTransactions = userSubscriptionRepository.countPaymentTransactions(
                search,
                status,
                from != null,
                from == null ? Instant.EPOCH : from,
                to != null,
                to == null ? Instant.EPOCH : to);

        BigDecimal totalRevenue = userSubscriptionRepository.sumPaymentTransactions(
                search,
                status,
                from != null,
                from == null ? Instant.EPOCH : from,
                to != null,
                to == null ? Instant.EPOCH : to);

        BigDecimal totalFee = totalRevenue.multiply(PLATFORM_FEE_RATE).setScale(2, RoundingMode.HALF_UP);

        List<PaymentUserStatsResponse> byUser = userSubscriptionRepository.summarizePaymentsByUser(
                        status,
                        from != null,
                        from == null ? Instant.EPOCH : from,
                        to != null,
                        to == null ? Instant.EPOCH : to)
                .stream()
                .filter(item -> search == null || search.isBlank()
                        || containsIgnoreCase(item.getUserEmail(), search)
                        || containsIgnoreCase(item.getUserId() == null ? null : String.valueOf(item.getUserId()), search))
                .map(item -> new PaymentUserStatsResponse(
                        item.getUserId(),
                        item.getUserEmail(),
                        item.getTransactionCount(),
                        item.getTotalAmount()))
                .toList();

        return new PaymentStatsResponse(totalRevenue, totalFee, totalTransactions, byUser);
    }

    private PaymentTransactionResponse toPaymentTransactionResponse(UserSubscription subscription) {
        BigDecimal amount = subscription.getPurchasedPrice() == null ? BigDecimal.ZERO : subscription.getPurchasedPrice();
        BigDecimal fee = subscription.isTrial()
                ? BigDecimal.ZERO
                : amount.multiply(PLATFORM_FEE_RATE).setScale(2, RoundingMode.HALF_UP);

        return new PaymentTransactionResponse(
                subscription.getId(),
                subscription.getUser() == null ? null : subscription.getUser().getId(),
                subscription.getUser() == null ? null : subscription.getUser().getEmail(),
                subscription.getUser() == null ? null : subscription.getUser().getFullName(),
                subscription.getPlan() == null ? null : subscription.getPlan().getId(),
                subscription.getPlan() == null ? null : subscription.getPlan().getName(),
                amount,
                fee,
                subscription.getCreatedAt(),
                subscription.getStatus(),
                subscription.getTransactionRef(),
                subscription.isTrial());
    }

    private boolean containsIgnoreCase(String value, String search) {
        return value != null && search != null && value.toLowerCase().contains(search.toLowerCase());
    }
}
