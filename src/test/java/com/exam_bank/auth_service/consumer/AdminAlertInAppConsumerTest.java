package com.exam_bank.auth_service.consumer;

import com.exam_bank.auth_service.dto.message.AdminAlertMessage;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.service.NotificationCenterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAlertInAppConsumer Unit Tests")
class AdminAlertInAppConsumerTest {

        @Mock
        private UserRepository userRepository;

        @Mock
        private NotificationCenterService notificationCenterService;

        @InjectMocks
        private AdminAlertInAppConsumer consumer;

        @Test
        @DisplayName("handleAdminAlert persists achievement notification for target user id")
        void handleAdminAlertPersistsAchievementNotificationForTargetUserId() {
                User user = buildUser(101L, Role.USER, true);
                when(userRepository.findAllById(anyCollection())).thenReturn(List.of(user));

                AdminAlertMessage message = new AdminAlertMessage(
                                "ACHIEVEMENT_UNLOCKED",
                                "Bạn đã đạt thành tựu mới",
                                "Khởi động thi cử",
                                List.of(),
                                "/gamification",
                                Map.of(
                                                "targetUserId", 101L,
                                                "achievementNames", List.of("Khởi động thi cử")));

                consumer.handleAdminAlert(message);

                verify(notificationCenterService).createNotification(
                                same(user),
                                eq("ACHIEVEMENT_UNLOCKED"),
                                eq("Bạn đã đạt thành tựu mới"),
                                eq("Chúc mừng! Bạn vừa mở khóa: Khởi động thi cử"),
                                eq("/dashboard/gamification"));
        }

        @Test
        @DisplayName("handleAdminAlert resolves targets by role when user ids are absent")
        void handleAdminAlertResolvesTargetsByRoleWhenUserIdsAbsent() {
                User userA = buildUser(201L, Role.USER, true);
                User userB = buildUser(202L, Role.CONTRIBUTOR, true);
                when(userRepository.findByRoleInAndStatusTrue(anyCollection())).thenReturn(List.of(userA, userB));

                AdminAlertMessage message = new AdminAlertMessage(
                                "STREAK_QUALIFIED",
                                null,
                                null,
                                List.of("user", "CONTRIBUTOR"),
                                null,
                                Map.of(
                                                "streakDays", 3,
                                                "todayStudyMinutes", 45));

                consumer.handleAdminAlert(message);

                verify(userRepository).findByRoleInAndStatusTrue(List.of(Role.USER, Role.CONTRIBUTOR));
                verify(notificationCenterService, times(2)).createNotification(
                                org.mockito.ArgumentMatchers.any(User.class),
                                anyString(),
                                anyString(),
                                anyString(),
                                anyString());
        }

        @Test
        @DisplayName("handleAdminAlert persists upload extraction notification for reviewer")
        void handleAdminAlertPersistsUploadExtractionNotificationForReviewer() {
                User user = buildUser(101L, Role.ADMIN, true);
                when(userRepository.findAllById(anyCollection())).thenReturn(List.of(user));

                AdminAlertMessage message = new AdminAlertMessage(
                                "EXAM_UPLOAD_EXTRACTED",
                                "Trích xuất đề thi hoàn tất",
                                "Đề \"Toán 12\" đã trích xuất xong. Bạn có thể mở để kiểm tra.",
                                List.of(),
                                null,
                                Map.of(
                                                "targetUserId", 101L,
                                                "uploadId", 55L,
                                                "title", "Toán 12"));

                consumer.handleAdminAlert(message);

                verify(notificationCenterService).createNotification(
                                same(user),
                                eq("EXAM_UPLOAD_EXTRACTED"),
                                eq("Trích xuất đề thi hoàn tất"),
                                eq("Đề \"Toán 12\" đã trích xuất xong. Bạn có thể mở để kiểm tra."),
                                eq("/admin/upload-queue"));
        }

        @Test
        @DisplayName("handleAdminAlert ignores unsupported types")
        void handleAdminAlertIgnoresUnsupportedTypes() {
                AdminAlertMessage message = new AdminAlertMessage(
                                "GENERIC_ALERT",
                                "title",
                                "body",
                                List.of("USER"),
                                "/dashboard",
                                Map.of("targetUserId", 1L));

                consumer.handleAdminAlert(message);

                verify(userRepository, never()).findAllById(anyCollection());
                verify(userRepository, never()).findByRoleInAndStatusTrue(anyCollection());
                verify(notificationCenterService, never()).createNotification(
                                org.mockito.ArgumentMatchers.any(User.class),
                                anyString(),
                                anyString(),
                                anyString(),
                                anyString());
        }

        private User buildUser(Long id, Role role, boolean active) {
                User user = new User();
                user.setId(id);
                user.setRole(role);
                user.setStatus(active);
                user.setEmail("user" + id + "@example.com");
                user.setFullName("User " + id);
                return user;
        }
}
