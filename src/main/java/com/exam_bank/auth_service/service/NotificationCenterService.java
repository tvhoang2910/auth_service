package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.request.UpdateNotificationPreferencesRequest;
import com.exam_bank.auth_service.dto.response.NotificationPreferenceResponse;
import com.exam_bank.auth_service.dto.response.UserNotificationItemResponse;
import com.exam_bank.auth_service.dto.response.UserNotificationPageResponse;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserNotification;
import com.exam_bank.auth_service.repository.UserNotificationRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationCenterService {

    private static final String AUDIT_UPDATE_NOTIFICATION_PREFERENCES = "UPDATE_NOTIFICATION_PREFERENCES";

    private final UserRepository userRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final SecurityAuditService securityAuditService;

    @Transactional(readOnly = true)
    public NotificationPreferenceResponse getMyPreferences(String email) {
        User user = getUserByEmail(email);
        return mapPreferences(user);
    }

    @Transactional
    public NotificationPreferenceResponse updateMyPreferences(
            String email,
            UpdateNotificationPreferencesRequest request) {
        User user = getUserByEmail(email);

        boolean nextEmailEnabled = request.emailEnabled() != null
                ? request.emailEnabled()
                : user.isEmailNotificationsEnabled();
        boolean nextWebPushEnabled = request.webPushEnabled() != null
                ? request.webPushEnabled()
                : user.isWebPushNotificationsEnabled();

        user.setEmailNotificationsEnabled(nextEmailEnabled);
        user.setWebPushNotificationsEnabled(nextWebPushEnabled);
        userRepository.save(user);

        securityAuditService.success(
                AUDIT_UPDATE_NOTIFICATION_PREFERENCES,
                user.getEmail(),
                "emailEnabled=" + nextEmailEnabled + ", webPushEnabled=" + nextWebPushEnabled);

        return new NotificationPreferenceResponse(nextEmailEnabled, nextWebPushEnabled);
    }

    @Transactional(readOnly = true)
    public UserNotificationPageResponse getMyNotifications(String email, Pageable pageable) {
        User user = getUserByEmail(email);

        Page<UserNotificationItemResponse> page = userNotificationRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(this::mapNotificationItem);
        long unreadCount = userNotificationRepository.countByUserIdAndReadAtIsNull(user.getId());

        return new UserNotificationPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                unreadCount);
    }

    @Transactional
    public UserNotificationItemResponse markAsRead(String email, Long notificationId) {
        User user = getUserByEmail(email);
        UserNotification notification = userNotificationRepository.findByIdAndUserId(notificationId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));

        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notification = userNotificationRepository.save(notification);
        }

        return mapNotificationItem(notification);
    }

    @Transactional
    public int markAllAsRead(String email) {
        User user = getUserByEmail(email);
        return userNotificationRepository.markAllAsRead(user.getId(), Instant.now());
    }

    @Transactional
    public void createNotification(
            User user,
            String type,
            String title,
            String message,
            String actionUrl) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return;
        }

        UserNotification notification = new UserNotification();
        notification.setUser(user);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setActionUrl(actionUrl);
        userNotificationRepository.save(notification);
    }

    private User getUserByEmail(String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        return userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private NotificationPreferenceResponse mapPreferences(User user) {
        return new NotificationPreferenceResponse(
                user.isEmailNotificationsEnabled(),
                user.isWebPushNotificationsEnabled());
    }

    private UserNotificationItemResponse mapNotificationItem(UserNotification notification) {
        boolean read = notification.getReadAt() != null;
        return new UserNotificationItemResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getActionUrl(),
                read,
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
