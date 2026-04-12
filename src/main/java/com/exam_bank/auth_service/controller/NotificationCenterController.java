package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.request.UpdateNotificationPreferencesRequest;
import com.exam_bank.auth_service.dto.response.NotificationPreferenceResponse;
import com.exam_bank.auth_service.dto.response.UserNotificationItemResponse;
import com.exam_bank.auth_service.dto.response.UserNotificationPageResponse;
import com.exam_bank.auth_service.service.NotificationCenterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationCenterController {

    private final NotificationCenterService notificationCenterService;

    @GetMapping("/preferences")
    public ResponseEntity<NotificationPreferenceResponse> getMyPreferences(Authentication authentication) {
        NotificationPreferenceResponse response = notificationCenterService.getMyPreferences(authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/preferences")
    public ResponseEntity<NotificationPreferenceResponse> updateMyPreferences(
            Authentication authentication,
            @RequestBody UpdateNotificationPreferencesRequest request) {
        NotificationPreferenceResponse response = notificationCenterService
                .updateMyPreferences(authentication.getName(), request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<UserNotificationPageResponse> getMyNotifications(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserNotificationPageResponse response = notificationCenterService
                .getMyNotifications(authentication.getName(), PageRequest.of(page, size));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<UserNotificationItemResponse> markAsRead(
            Authentication authentication,
            @PathVariable Long notificationId) {
        UserNotificationItemResponse response = notificationCenterService
                .markAsRead(authentication.getName(), notificationId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllAsRead(Authentication authentication) {
        int updatedCount = notificationCenterService.markAllAsRead(authentication.getName());
        return ResponseEntity.ok(Map.of("updatedCount", updatedCount));
    }
}
