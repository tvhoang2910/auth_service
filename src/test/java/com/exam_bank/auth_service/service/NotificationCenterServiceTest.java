package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.request.UpdateNotificationPreferencesRequest;
import com.exam_bank.auth_service.dto.response.NotificationPreferenceResponse;
import com.exam_bank.auth_service.dto.response.UserNotificationItemResponse;
import com.exam_bank.auth_service.dto.response.UserNotificationPageResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserNotification;
import com.exam_bank.auth_service.repository.UserNotificationRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationCenterService Unit Tests")
class NotificationCenterServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserNotificationRepository userNotificationRepository;

    @Mock
    private SecurityAuditService securityAuditService;

    @InjectMocks
    private NotificationCenterService service;

    @Test
    @DisplayName("updateMyPreferences persists user flags")
    void updateMyPreferencesPersistsUserFlags() {
        User user = buildUser(1L, "user@example.com");
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationPreferenceResponse response = service.updateMyPreferences(
                "user@example.com",
                new UpdateNotificationPreferencesRequest(false, true));

        assertThat(response.emailEnabled()).isFalse();
        assertThat(response.webPushEnabled()).isTrue();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().isEmailNotificationsEnabled()).isFalse();
        assertThat(userCaptor.getValue().isWebPushNotificationsEnabled()).isTrue();
    }

    @Test
    @DisplayName("getMyPreferences returns persisted user flags")
    void getMyPreferencesReturnsPersistedFlags() {
        User user = buildUser(1L, "user@example.com");
        user.setEmailNotificationsEnabled(false);
        user.setWebPushNotificationsEnabled(true);

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

        NotificationPreferenceResponse response = service.getMyPreferences("user@example.com");

        assertThat(response.emailEnabled()).isFalse();
        assertThat(response.webPushEnabled()).isTrue();
    }

    @Test
    @DisplayName("getMyNotifications maps page response and unread count")
    void getMyNotificationsMapsPageResponse() {
        User user = buildUser(1L, "user@example.com");
        UserNotification notification = new UserNotification();
        notification.setId(101L);
        notification.setUser(user);
        notification.setType("SUBSCRIPTION_REVIEWED");
        notification.setTitle("Yêu cầu Premium đã được duyệt");
        notification.setMessage("Gói Premium 30 đã được duyệt");
        notification.setActionUrl("/dashboard/subscription-payments");
        notification.setCreatedAt(Instant.parse("2026-04-11T12:00:00Z"));

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userNotificationRepository.findByUserIdOrderByCreatedAtDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(notification), PageRequest.of(0, 20), 1));
        when(userNotificationRepository.countByUserIdAndReadAtIsNull(1L)).thenReturn(3L);

        UserNotificationPageResponse response = service.getMyNotifications("user@example.com", PageRequest.of(0, 20));

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().id()).isEqualTo(101L);
        assertThat(response.content().getFirst().read()).isFalse();
        assertThat(response.unreadCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("markAsRead sets readAt when notification is unread")
    void markAsReadSetsReadAt() {
        User user = buildUser(1L, "user@example.com");
        UserNotification notification = new UserNotification();
        notification.setId(102L);
        notification.setUser(user);
        notification.setType("SUBSCRIPTION_EXPIRY_REMINDER");
        notification.setTitle("Gói Premium sắp hết hạn");
        notification.setMessage("Hết hạn vào 2026-04-12T00:05:00Z");

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userNotificationRepository.findByIdAndUserId(102L, 1L)).thenReturn(Optional.of(notification));
        when(userNotificationRepository.save(any(UserNotification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.markAsRead("user@example.com", 102L);

        verify(userNotificationRepository).save(any(UserNotification.class));
        assertThat(notification.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("markAsRead does not save when notification is already read")
    void markAsReadDoesNotSaveAlreadyReadNotification() {
        User user = buildUser(1L, "user@example.com");
        UserNotification notification = new UserNotification();
        notification.setId(103L);
        notification.setUser(user);
        notification.setType("SUBSCRIPTION_REVIEWED");
        notification.setTitle("Yêu cầu Premium đã được duyệt");
        notification.setMessage("Gói Premium 30 đã được duyệt");
        Instant existingReadAt = Instant.parse("2026-04-11T12:30:00Z");
        notification.setReadAt(existingReadAt);

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userNotificationRepository.findByIdAndUserId(103L, 1L)).thenReturn(Optional.of(notification));

        UserNotificationItemResponse response = service.markAsRead("user@example.com", 103L);

        verify(userNotificationRepository, never()).save(any(UserNotification.class));
        assertThat(response.read()).isTrue();
        assertThat(response.readAt()).isEqualTo(existingReadAt);
    }

    @Test
    @DisplayName("markAllAsRead returns updated count")
    void markAllAsReadReturnsUpdatedCount() {
        User user = buildUser(1L, "user@example.com");

        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userNotificationRepository.markAllAsRead(eq(1L), any(Instant.class))).thenReturn(4);

        int updatedCount = service.markAllAsRead("user@example.com");

        assertThat(updatedCount).isEqualTo(4);
        verify(userNotificationRepository).markAllAsRead(eq(1L), any(Instant.class));
    }

    @Test
    @DisplayName("createNotification ignores invalid user")
    void createNotificationIgnoresInvalidUser() {
        User invalidUser = new User();
        invalidUser.setId(0L);

        service.createNotification(
                invalidUser,
                "SUBSCRIPTION_REVIEWED",
                "Yêu cầu Premium đã được duyệt",
                "Gói Premium 30 đã được duyệt",
                "/dashboard/subscription-payments");

        verify(userNotificationRepository, never()).save(any(UserNotification.class));
    }

    private User buildUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setFullName("User Demo");
        user.setRole(Role.USER);
        user.setStatus(true);
        user.setEmailNotificationsEnabled(true);
        user.setWebPushNotificationsEnabled(true);
        return user;
    }
}
