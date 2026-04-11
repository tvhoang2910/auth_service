package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.request.CancelSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.CancelSubscriptionResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionAnalyticsOverviewResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionHistoryPageResponse;
import com.exam_bank.auth_service.entity.PremiumPlan;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserSubscription;
import com.exam_bank.auth_service.repository.PlanSubscriptionCountProjection;
import com.exam_bank.auth_service.repository.PremiumPlanRepository;
import com.exam_bank.auth_service.repository.SubscriptionApprovalAuditRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionRequestService Unit Tests")
class SubscriptionRequestServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PremiumPlanRepository premiumPlanRepository;

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @Mock
    private SubscriptionApprovalAuditRepository subscriptionApprovalAuditRepository;

    @Mock
    private PaymentBillStorageService paymentBillStorageService;

    @Mock
    private UserProfileCacheService userProfileCacheService;

    @Mock
    private SecurityAuditService securityAuditService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private NotificationRabbitProperties notificationRabbitProperties;

    @Mock
    private AuthUserProfileEventPublisher authUserProfileEventPublisher;

    @InjectMocks
    private SubscriptionRequestService service;

    @Test
    @DisplayName("getSubscriptionHistory returns mapped page for ADMIN")
    void getSubscriptionHistoryReturnsMappedPageForAdmin() {
        User admin = buildUser(1L, "admin@example.com", "Admin", Role.ADMIN);
        User subscriber = buildUser(2L, "user@example.com", "Premium User", Role.USER);
        PremiumPlan plan = buildPlan(3L, "Premium 30", BigDecimal.valueOf(199000));

        UserSubscription subscription = new UserSubscription();
        subscription.setId(10L);
        subscription.setUser(subscriber);
        subscription.setPlan(plan);
        subscription.setPurchasedPrice(BigDecimal.valueOf(199000));
        subscription.setStatus(SubscriptionStatus.APPROVED);
        subscription.setBillImageUrl("https://example.com/bill.jpg");
        subscription.setPaymentMethod("BANK_TRANSFER");
        subscription.setTransactionRef("TXN-1");
        subscription.setPromoCode(null);
        subscription.setTrial(false);
        subscription.setStartDate(Instant.parse("2026-04-01T00:00:00Z"));
        subscription.setEndDate(Instant.parse("2026-05-01T00:00:00Z"));
        subscription.setCreatedAt(Instant.parse("2026-04-01T01:00:00Z"));

        when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
        when(userSubscriptionRepository.searchHistory(
                anyBoolean(),
                anyString(),
                anyBoolean(),
                any(SubscriptionStatus.class),
                anyBoolean(),
                any(Instant.class),
                anyBoolean(),
                any(Instant.class),
                any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(subscription), PageRequest.of(0, 10), 1));

        SubscriptionHistoryPageResponse response = service.getSubscriptionHistory(
                "admin@example.com",
                "premium",
                SubscriptionStatus.APPROVED,
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-11T00:00:00Z"),
                PageRequest.of(0, 10));

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().userEmail()).isEqualTo("user@example.com");
        assertThat(response.content().getFirst().planName()).isEqualTo("Premium 30");
        assertThat(response.totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("cancelSubscription updates status, sets audit fields, and returns refund info")
    void cancelSubscriptionUpdatesStatusAndReturnsRefund() {
        User admin = buildUser(1L, "admin@example.com", "Admin", Role.ADMIN);
        User subscriber = buildUser(2L, "user@example.com", "Premium User", Role.USER);
        PremiumPlan plan = buildPlan(3L, "Premium 30", BigDecimal.valueOf(200000));
        plan.setLifetime(false);

        Instant now = Instant.now();
        UserSubscription subscription = new UserSubscription();
        subscription.setId(22L);
        subscription.setUser(subscriber);
        subscription.setPlan(plan);
        subscription.setPurchasedPrice(BigDecimal.valueOf(200000));
        subscription.setStatus(SubscriptionStatus.APPROVED);
        subscription.setStartDate(now.minus(5, ChronoUnit.DAYS));
        subscription.setEndDate(now.plus(25, ChronoUnit.DAYS));

        when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
        when(userSubscriptionRepository.findById(22L)).thenReturn(Optional.of(subscription));
        when(userSubscriptionRepository.save(any(UserSubscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CancelSubscriptionResponse response = service.cancelSubscription(
                22L,
                "admin@example.com",
                new CancelSubscriptionRequest("Khach hang yeu cau huy"));

        assertThat(response.currentStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(response.previousStatus()).isEqualTo(SubscriptionStatus.APPROVED);
        assertThat(response.refundAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.refundPolicy()).isEqualTo("PRORATED_BY_REMAINING_TIME");

        ArgumentCaptor<UserSubscription> subscriptionCaptor = ArgumentCaptor.forClass(UserSubscription.class);
        verify(userSubscriptionRepository).save(subscriptionCaptor.capture());
        UserSubscription savedSubscription = subscriptionCaptor.getValue();
        assertThat(savedSubscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(savedSubscription.getCancellationReason()).isEqualTo("Khach hang yeu cau huy");
        assertThat(savedSubscription.getCancelledByEmail()).isEqualTo("admin@example.com");

        verify(userProfileCacheService).evict(subscriber.getId(), subscriber.getEmail());
        verify(securityAuditService).success(eq("CANCEL_SUBSCRIPTION"), eq("admin@example.com"), any());
    }

    @Test
    @DisplayName("cancelSubscription rejects non-approved status")
    void cancelSubscriptionRejectsNonApprovedStatus() {
        User admin = buildUser(1L, "admin@example.com", "Admin", Role.ADMIN);
        User subscriber = buildUser(2L, "user@example.com", "Premium User", Role.USER);
        PremiumPlan plan = buildPlan(3L, "Premium 30", BigDecimal.valueOf(200000));

        UserSubscription subscription = new UserSubscription();
        subscription.setId(44L);
        subscription.setUser(subscriber);
        subscription.setPlan(plan);
        subscription.setPurchasedPrice(BigDecimal.valueOf(200000));
        subscription.setStatus(SubscriptionStatus.REJECTED);
        subscription.setStartDate(Instant.parse("2026-04-01T00:00:00Z"));
        subscription.setEndDate(Instant.parse("2026-05-01T00:00:00Z"));

        when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
        when(userSubscriptionRepository.findById(44L)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.cancelSubscription(
                44L,
                "admin@example.com",
                new CancelSubscriptionRequest("Huy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only approved subscriptions can be cancelled");

        verify(securityAuditService).failure(eq("CANCEL_SUBSCRIPTION"), eq("admin@example.com"), any());
    }

    @Test
    @DisplayName("getSubscriptionAnalyticsOverview returns monthly revenue, active count, and top plan")
    void getSubscriptionAnalyticsOverviewReturnsOverviewMetrics() {
        User admin = buildUser(1L, "admin@example.com", "Admin", Role.ADMIN);

        PlanSubscriptionCountProjection topPlanProjection = new PlanSubscriptionCountProjection() {
            @Override
            public String getPlanName() {
                return "Premium 30";
            }

            @Override
            public long getSubscriptionCount() {
                return 18L;
            }
        };

        when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
        when(userSubscriptionRepository.sumPurchasedPriceByStatusAndStartDateBetween(
                eq(SubscriptionStatus.APPROVED), any(), any()))
                .thenReturn(BigDecimal.valueOf(2500000));
        when(userSubscriptionRepository.countActiveByStatus(eq(SubscriptionStatus.APPROVED), any()))
                .thenReturn(42L);
        when(userSubscriptionRepository.findPlanSubscriptionStatsByStatus(
                eq(SubscriptionStatus.APPROVED), any()))
                .thenReturn(List.of(topPlanProjection));

        SubscriptionAnalyticsOverviewResponse response = service.getSubscriptionAnalyticsOverview("admin@example.com");

        assertThat(response.monthlyRevenue()).isEqualByComparingTo("2500000.00");
        assertThat(response.activePremiumCount()).isEqualTo(42L);
        assertThat(response.topPlanName()).isEqualTo("Premium 30");
        assertThat(response.topPlanSubscriptions()).isEqualTo(18L);
    }

    private User buildUser(Long id, String email, String fullName, Role role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        return user;
    }

    private PremiumPlan buildPlan(Long id, String name, BigDecimal price) {
        PremiumPlan plan = new PremiumPlan();
        plan.setId(id);
        plan.setName(name);
        plan.setPrice(price);
        plan.setDurationDays(30);
        plan.setLifetime(false);
        return plan;
    }
}
