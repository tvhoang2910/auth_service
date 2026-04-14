package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.message.SubscriptionExpiryReminderMessage;
import com.exam_bank.auth_service.dto.message.SubscriptionReviewedMessage;
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
import static org.mockito.Mockito.never;
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

        @Mock
        private NotificationCenterService notificationCenterService;

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
                                new CancelSubscriptionRequest("Khách hàng yêu cầu hủy"));

                assertThat(response.currentStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
                assertThat(response.previousStatus()).isEqualTo(SubscriptionStatus.APPROVED);
                assertThat(response.refundAmount()).isGreaterThan(BigDecimal.ZERO);
                assertThat(response.refundPolicy()).isEqualTo("PRORATED_BY_REMAINING_TIME");

                ArgumentCaptor<UserSubscription> subscriptionCaptor = ArgumentCaptor.forClass(UserSubscription.class);
                verify(userSubscriptionRepository).save(subscriptionCaptor.capture());
                UserSubscription savedSubscription = subscriptionCaptor.getValue();
                assertThat(savedSubscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
                assertThat(savedSubscription.getCancellationReason()).isEqualTo("Khách hàng yêu cầu hủy");
                assertThat(savedSubscription.getCancelledByEmail()).isEqualTo("admin@example.com");

                verify(userProfileCacheService).evict(subscriber.getId(), subscriber.getEmail());
                verify(securityAuditService).success(eq("CANCEL_SUBSCRIPTION"), eq("admin@example.com"), any());
        }

        @Test
        @DisplayName("cancelSubscription publishes reviewed notifications with CANCELLED decision")
        void cancelSubscriptionPublishesCancelledNotifications() {
                User admin = buildUser(1L, "admin@example.com", "Admin", Role.ADMIN);
                User subscriber = buildUser(2L, "user@example.com", "Premium User", Role.USER);
                PremiumPlan plan = buildPlan(3L, "Premium 30", BigDecimal.valueOf(200000));
                plan.setLifetime(false);

                Instant now = Instant.now();
                UserSubscription subscription = new UserSubscription();
                subscription.setId(23L);
                subscription.setUser(subscriber);
                subscription.setPlan(plan);
                subscription.setPurchasedPrice(BigDecimal.valueOf(200000));
                subscription.setStatus(SubscriptionStatus.APPROVED);
                subscription.setStartDate(now.minus(5, ChronoUnit.DAYS));
                subscription.setEndDate(now.plus(25, ChronoUnit.DAYS));

                when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
                when(userSubscriptionRepository.findById(23L)).thenReturn(Optional.of(subscription));
                when(userSubscriptionRepository.save(any(UserSubscription.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
                when(notificationRabbitProperties.getEmailSubscriptionReviewedRoutingKey())
                                .thenReturn("notification.send.email.subscription-reviewed");
                when(notificationRabbitProperties.getWebPushSubscriptionReviewedRoutingKey())
                                .thenReturn("notification.send.webpush.subscription-reviewed");

                service.cancelSubscription(23L, "admin@example.com",
                                new CancelSubscriptionRequest("Khách hàng yêu cầu hủy"));

                ArgumentCaptor<SubscriptionReviewedMessage> reviewedMessageCaptor = ArgumentCaptor
                                .forClass(SubscriptionReviewedMessage.class);
                verify(rabbitTemplate).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.email.subscription-reviewed"),
                                reviewedMessageCaptor.capture());
                verify(rabbitTemplate).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.webpush.subscription-reviewed"),
                                any(SubscriptionReviewedMessage.class));

                assertThat(reviewedMessageCaptor.getValue().decision()).isEqualTo("CANCELLED");
                assertThat(reviewedMessageCaptor.getValue().reviewedBy()).isEqualTo("admin@example.com");
        }

        @Test
        @DisplayName("cancelSubscription publishes only enabled channels and creates in-app notification")
        void cancelSubscriptionPublishesOnlyEnabledChannels() {
                User admin = buildUser(1L, "admin@example.com", "Admin", Role.ADMIN);
                User subscriber = buildUser(2L, "user@example.com", "Premium User", Role.USER);
                subscriber.setEmailNotificationsEnabled(false);
                subscriber.setWebPushNotificationsEnabled(true);

                PremiumPlan plan = buildPlan(3L, "Premium 30", BigDecimal.valueOf(200000));
                plan.setLifetime(false);

                Instant now = Instant.now();
                UserSubscription subscription = new UserSubscription();
                subscription.setId(24L);
                subscription.setUser(subscriber);
                subscription.setPlan(plan);
                subscription.setPurchasedPrice(BigDecimal.valueOf(200000));
                subscription.setStatus(SubscriptionStatus.APPROVED);
                subscription.setStartDate(now.minus(5, ChronoUnit.DAYS));
                subscription.setEndDate(now.plus(25, ChronoUnit.DAYS));

                when(userRepository.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(admin));
                when(userSubscriptionRepository.findById(24L)).thenReturn(Optional.of(subscription));
                when(userSubscriptionRepository.save(any(UserSubscription.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
                when(notificationRabbitProperties.getWebPushSubscriptionReviewedRoutingKey())
                                .thenReturn("notification.send.webpush.subscription-reviewed");

                service.cancelSubscription(24L, "admin@example.com",
                                new CancelSubscriptionRequest("Khách hàng yêu cầu hủy"));

                verify(rabbitTemplate, never()).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.email.subscription-reviewed"),
                                any(SubscriptionReviewedMessage.class));
                verify(rabbitTemplate).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.webpush.subscription-reviewed"),
                                any(SubscriptionReviewedMessage.class));
                verify(notificationCenterService).createNotification(
                                eq(subscriber),
                                eq("SUBSCRIPTION_REVIEWED"),
                                eq("Gói Premium đã bị hủy"),
                                eq("Gói Premium 30: Đã bị hủy"),
                                eq("/dashboard/subscription-payments"));
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

                SubscriptionAnalyticsOverviewResponse response = service
                                .getSubscriptionAnalyticsOverview("admin@example.com");

                assertThat(response.monthlyRevenue()).isEqualByComparingTo("2500000.00");
                assertThat(response.activePremiumCount()).isEqualTo(42L);
                assertThat(response.topPlanName()).isEqualTo("Premium 30");
                assertThat(response.topPlanSubscriptions()).isEqualTo(18L);
        }

        @Test
        @DisplayName("runSubscriptionAutomation expires approved subscriptions and sends expiry reminders")
        void runSubscriptionAutomationExpiresAndSendsReminders() {
                Instant now = Instant.parse("2026-04-10T00:05:00Z");

                User expiredUser = buildUser(10L, "expired@example.com", "Expired User", Role.USER);
                PremiumPlan expiredPlan = buildPlan(20L, "Premium 30", BigDecimal.valueOf(199000));
                UserSubscription expiredSubscription = new UserSubscription();
                expiredSubscription.setId(100L);
                expiredSubscription.setUser(expiredUser);
                expiredSubscription.setPlan(expiredPlan);
                expiredSubscription.setPurchasedPrice(BigDecimal.valueOf(199000));
                expiredSubscription.setStatus(SubscriptionStatus.APPROVED);
                expiredSubscription.setStartDate(now.minus(31, ChronoUnit.DAYS));
                expiredSubscription.setEndDate(now.minus(1, ChronoUnit.MINUTES));

                User reminderUser = buildUser(11L, "reminder@example.com", "Reminder User", Role.USER);
                PremiumPlan reminderPlan = buildPlan(21L, "Premium 90", BigDecimal.valueOf(399000));
                UserSubscription reminderSubscription = new UserSubscription();
                reminderSubscription.setId(101L);
                reminderSubscription.setUser(reminderUser);
                reminderSubscription.setPlan(reminderPlan);
                reminderSubscription.setPurchasedPrice(BigDecimal.valueOf(399000));
                reminderSubscription.setStatus(SubscriptionStatus.APPROVED);
                reminderSubscription.setStartDate(now.minus(10, ChronoUnit.DAYS));
                reminderSubscription.setEndDate(now.plus(3, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS));

                when(userSubscriptionRepository.findByStatusAndEndDateBefore(SubscriptionStatus.APPROVED, now))
                                .thenReturn(List.of(expiredSubscription));
                when(userSubscriptionRepository.findForExpiryReminder(
                                SubscriptionStatus.APPROVED,
                                now,
                                now.plus(3, ChronoUnit.DAYS)))
                                .thenReturn(List.of(reminderSubscription));
                when(userSubscriptionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
                when(userSubscriptionRepository.existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
                                eq(10L),
                                eq(SubscriptionStatus.APPROVED),
                                any(Instant.class),
                                any(Instant.class)))
                                .thenReturn(false);
                when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
                when(notificationRabbitProperties.getEmailSubscriptionExpiryReminderRoutingKey())
                                .thenReturn("notification.send.email.subscription-expiry-reminder");
                when(notificationRabbitProperties.getWebPushSubscriptionExpiryReminderRoutingKey())
                                .thenReturn("notification.send.webpush.subscription-expiry-reminder");

                SubscriptionRequestService.SubscriptionAutomationResult result = service.runSubscriptionAutomation(now);

                assertThat(result.executedAt()).isEqualTo(now);
                assertThat(result.expiredCount()).isEqualTo(1);
                assertThat(result.reminderCount()).isEqualTo(1);
                assertThat(expiredSubscription.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
                assertThat(expiredSubscription.getExpiryReminderSentAt()).isEqualTo(now);
                assertThat(reminderSubscription.getExpiryReminderSentAt()).isEqualTo(now);

                verify(userProfileCacheService).evict(10L, "expired@example.com");
                verify(authUserProfileEventPublisher).publish(eq(expiredUser), eq(false));

                ArgumentCaptor<SubscriptionExpiryReminderMessage> reminderMessageCaptor = ArgumentCaptor
                                .forClass(SubscriptionExpiryReminderMessage.class);
                verify(rabbitTemplate).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.email.subscription-expiry-reminder"),
                                reminderMessageCaptor.capture());
                verify(rabbitTemplate).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.webpush.subscription-expiry-reminder"),
                                any(SubscriptionExpiryReminderMessage.class));

                assertThat(reminderMessageCaptor.getValue().subscriptionId()).isEqualTo(101L);
                assertThat(reminderMessageCaptor.getValue().userEmail()).isEqualTo("reminder@example.com");
        }

        @Test
        @DisplayName("runSubscriptionAutomation sends reminder via enabled channel and creates in-app notification")
        void runSubscriptionAutomationRespectsReminderChannelPreferences() {
                Instant now = Instant.parse("2026-04-10T00:05:00Z");

                User reminderUser = buildUser(11L, "reminder@example.com", "Reminder User", Role.USER);
                reminderUser.setEmailNotificationsEnabled(false);
                reminderUser.setWebPushNotificationsEnabled(true);

                PremiumPlan reminderPlan = buildPlan(21L, "Premium 90", BigDecimal.valueOf(399000));
                UserSubscription reminderSubscription = new UserSubscription();
                reminderSubscription.setId(101L);
                reminderSubscription.setUser(reminderUser);
                reminderSubscription.setPlan(reminderPlan);
                reminderSubscription.setPurchasedPrice(BigDecimal.valueOf(399000));
                reminderSubscription.setStatus(SubscriptionStatus.APPROVED);
                reminderSubscription.setStartDate(now.minus(10, ChronoUnit.DAYS));
                reminderSubscription.setEndDate(now.plus(2, ChronoUnit.DAYS));

                when(userSubscriptionRepository.findByStatusAndEndDateBefore(SubscriptionStatus.APPROVED, now))
                                .thenReturn(List.of());
                when(userSubscriptionRepository.findForExpiryReminder(
                                SubscriptionStatus.APPROVED,
                                now,
                                now.plus(3, ChronoUnit.DAYS)))
                                .thenReturn(List.of(reminderSubscription));
                when(userSubscriptionRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
                when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
                when(notificationRabbitProperties.getWebPushSubscriptionExpiryReminderRoutingKey())
                                .thenReturn("notification.send.webpush.subscription-expiry-reminder");

                SubscriptionRequestService.SubscriptionAutomationResult result = service.runSubscriptionAutomation(now);

                assertThat(result.reminderCount()).isEqualTo(1);
                verify(rabbitTemplate, never()).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.email.subscription-expiry-reminder"),
                                any(SubscriptionExpiryReminderMessage.class));
                verify(rabbitTemplate).convertAndSend(
                                eq("notification.events"),
                                eq("notification.send.webpush.subscription-expiry-reminder"),
                                any(SubscriptionExpiryReminderMessage.class));
                verify(notificationCenterService).createNotification(
                                eq(reminderUser),
                                eq("SUBSCRIPTION_EXPIRY_REMINDER"),
                                eq("Gói Premium sắp hết hạn"),
                                anyString(),
                                eq("/dashboard/subscription-payments"));
        }

        private User buildUser(Long id, String email, String fullName, Role role) {
                User user = new User();
                user.setId(id);
                user.setEmail(email);
                user.setFullName(fullName);
                user.setRole(role);
                user.setEmailNotificationsEnabled(true);
                user.setWebPushNotificationsEnabled(true);
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
