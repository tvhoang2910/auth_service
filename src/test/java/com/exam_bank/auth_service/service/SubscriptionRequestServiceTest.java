package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.request.ReviewSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.UserSubscriptionQueueItemResponse;
import com.exam_bank.auth_service.dto.message.SubscriptionReviewedMessage;
import com.exam_bank.auth_service.entity.PremiumPlan;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserSubscription;
import com.exam_bank.auth_service.repository.PremiumPlanRepository;
import com.exam_bank.auth_service.repository.SubscriptionApprovalAuditRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
    private RabbitTemplate rabbitTemplate;

    @Mock
    private NotificationRabbitProperties notificationRabbitProperties;

    @InjectMocks
    private SubscriptionRequestService subscriptionRequestService;

    private User normalUser;
    private User adminReviewer;
    private PremiumPlan plan;

    @BeforeEach
    void setUp() {
        normalUser = new User();
        normalUser.setId(10L);
        normalUser.setEmail("user@example.com");
        normalUser.setFullName("Normal User");
        normalUser.setRole(Role.USER);

        adminReviewer = new User();
        adminReviewer.setId(11L);
        adminReviewer.setEmail("admin@example.com");
        adminReviewer.setFullName("Admin Reviewer");
        adminReviewer.setRole(Role.ADMIN);

        plan = new PremiumPlan();
        plan.setId(99L);
        plan.setName("Premium Plus");
        plan.setActive(true);
        plan.setPrice(BigDecimal.valueOf(99000));
        plan.setDurationDays(30);
        plan.setLifetime(false);
    }

    @Test
    void createPurchaseRequest_shouldThrow_whenUserAlreadyHasPendingOrApprovedPlanRequest() {
        MockMultipartFile bill = new MockMultipartFile("bill", "bill.png", "image/png", "png".getBytes());

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(normalUser));
        when(premiumPlanRepository.findById(99L)).thenReturn(Optional.of(plan));
        when(userSubscriptionRepository.existsByUserAndPlanAndStatusIn(eq(normalUser), eq(plan), anySet()))
                .thenReturn(true);

        assertThatThrownBy(() -> subscriptionRequestService.createPurchaseRequest(
                "user@example.com",
                99L,
                "bank_transfer",
                "FT001",
                null,
                false,
                bill))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You already have a pending or approved request for this plan");

        verify(paymentBillStorageService, never()).uploadBill(any(), any());
        verify(userSubscriptionRepository, never()).save(any());
    }

    @Test
    void reviewRequest_shouldUpdateStatusFromPendingToApproved() {
        UserSubscription pendingSubscription = new UserSubscription();
        pendingSubscription.setId(1000L);
        pendingSubscription.setUser(normalUser);
        pendingSubscription.setPlan(plan);
        pendingSubscription.setPurchasedPrice(BigDecimal.valueOf(99000));
        pendingSubscription.setStatus(SubscriptionStatus.PENDING_REVIEW);
        pendingSubscription.setStartDate(Instant.parse("2026-03-19T00:00:00Z"));
        pendingSubscription.setEndDate(Instant.parse("2026-04-18T00:00:00Z"));
        pendingSubscription.setCreatedAt(Instant.parse("2026-03-19T00:00:00Z"));

        when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(adminReviewer));
        when(userSubscriptionRepository.findById(1000L)).thenReturn(Optional.of(pendingSubscription));
        when(userSubscriptionRepository.save(any(UserSubscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptionApprovalAuditRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
        when(notificationRabbitProperties.getEmailSubscriptionReviewedRoutingKey())
                .thenReturn("notification.send.email.subscription-reviewed");
        when(notificationRabbitProperties.getWebPushSubscriptionReviewedRoutingKey())
                .thenReturn("notification.send.webpush.subscription-reviewed");

        UserSubscriptionQueueItemResponse response = subscriptionRequestService.reviewRequest(
                1000L,
                "admin@example.com",
                new ReviewSubscriptionRequest(true, "Đã đối soát"));

        assertThat(response.status()).isEqualTo(SubscriptionStatus.APPROVED);
        assertThat(pendingSubscription.getStatus()).isEqualTo(SubscriptionStatus.APPROVED);
        verify(userSubscriptionRepository).save(pendingSubscription);
        verify(rabbitTemplate).convertAndSend(
                eq("notification.events"),
                eq("notification.send.email.subscription-reviewed"),
                any(SubscriptionReviewedMessage.class));
        verify(rabbitTemplate).convertAndSend(
                eq("notification.events"),
                eq("notification.send.webpush.subscription-reviewed"),
                any(SubscriptionReviewedMessage.class));
    }
}
