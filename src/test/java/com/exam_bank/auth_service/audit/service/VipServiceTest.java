package com.exam_bank.auth_service.audit.service;

import com.exam_bank.auth_service.dto.response.VipStatusResponse;
import com.exam_bank.auth_service.entity.Subscription;
import com.exam_bank.auth_service.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VipServiceTest {

    @Mock
    private BillingService billingService;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private VipService vipService;

    @Test
    void upgradeShouldCreateInvoiceAndWriteAuditLog() {
        Subscription saved = new Subscription();
        saved.setId(99L);
        saved.setUserId(7L);
        saved.setPlanType("GOLD");
        saved.setStatus(BillingService.VIP_ACTIVE_STATUS);
        saved.setStartDate(Instant.parse("2026-05-03T00:00:00Z"));
        saved.setEndDate(Instant.parse("2026-06-02T00:00:00Z"));
        saved.setAmount(new BigDecimal("120.00"));
        when(billingService.createInvoice(any(), any(), any(), any(), any())).thenReturn(saved);

        Subscription result = vipService.upgrade(7L, "GOLD", new BigDecimal("120.00"), 30);

        assertThat(result).isSameAs(saved);
        verify(auditLogService).save(7L, "VIP_UPGRADE", "VIP", "Upgraded to plan GOLD for 30 days");
    }

    @Test
    void checkShouldReturnInactiveWhenNoSubscriptionExists() {
        when(subscriptionRepository.findTopByUserIdOrderByStartDateDesc(7L)).thenReturn(Optional.empty());

        VipStatusResponse response = vipService.check(7L);

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.active()).isFalse();
        assertThat(response.status()).isEqualTo("INACTIVE");
    }

    @Test
    void expireShouldMarkLatestSubscriptionAsExpired() {
        Subscription subscription = new Subscription();
        subscription.setId(11L);
        subscription.setUserId(7L);
        subscription.setPlanType("GOLD");
        subscription.setStatus(BillingService.VIP_ACTIVE_STATUS);
        subscription.setStartDate(Instant.parse("2026-05-03T00:00:00Z"));
        subscription.setEndDate(Instant.parse("2026-06-02T00:00:00Z"));
        subscription.setAmount(new BigDecimal("120.00"));

        when(subscriptionRepository.findTopByUserIdOrderByStartDateDesc(7L)).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Subscription expired = vipService.expire(7L);

        assertThat(expired.getStatus()).isEqualTo("EXPIRED");
        verify(auditLogService).save(7L, "VIP_EXPIRE", "VIP", "VIP expired manually or by scheduled process");
    }
}
