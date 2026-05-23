package com.exam_bank.auth_service.consumer;

import com.exam_bank.auth_service.dto.message.AdminAlertMessage;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.service.NotificationCenterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAlertInAppConsumer {

    private static final String TYPE_ACHIEVEMENT_UNLOCKED = "ACHIEVEMENT_UNLOCKED";
    private static final String TYPE_STREAK_QUALIFIED = "STREAK_QUALIFIED";
    private static final String TYPE_EXAM_UPLOAD_EXTRACTED = "EXAM_UPLOAD_EXTRACTED";
    private static final String TYPE_EXAM_UPLOAD_EXTRACT_FAILED = "EXAM_UPLOAD_EXTRACT_FAILED";
    private static final String DEFAULT_GAMIFICATION_URL = "/dashboard/gamification";
    private static final String DEFAULT_UPLOAD_QUEUE_URL = "/admin/upload-queue";
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            TYPE_ACHIEVEMENT_UNLOCKED,
            TYPE_STREAK_QUALIFIED,
            TYPE_EXAM_UPLOAD_EXTRACTED,
            TYPE_EXAM_UPLOAD_EXTRACT_FAILED);

    private final UserRepository userRepository;
    private final NotificationCenterService notificationCenterService;

    @RabbitListener(queues = "${auth.notification.in-app-admin-alert-queue}")
    public void handleAdminAlert(AdminAlertMessage message) {
        if (message == null) {
            log.warn("Skip in-app admin alert because payload is null");
            return;
        }

        String type = normalizeType(message.getType());
        if (!SUPPORTED_TYPES.contains(type)) {
            return;
        }

        List<User> targetUsers = resolveTargetUsers(message);
        if (targetUsers.isEmpty()) {
            log.info("Skip in-app admin alert type={} because no active target users resolved", type);
            return;
        }

        String title = resolveTitle(message, type);
        String body = resolveBody(message, type);
        String actionUrl = resolveActionUrl(message.getUrl(), type);

        for (User user : targetUsers) {
            notificationCenterService.createNotification(
                    user,
                    type,
                    title,
                    body,
                    actionUrl);
        }

        log.info("Persisted in-app admin alert type={} for {} users", type, targetUsers.size());
    }

    private List<User> resolveTargetUsers(AdminAlertMessage message) {
        Set<Long> targetUserIds = extractTargetUserIds(message.getMetadata());
        if (!targetUserIds.isEmpty()) {
            return userRepository.findAllById(targetUserIds).stream()
                    .filter(User::isStatus)
                    .toList();
        }

        List<Role> targetRoles = extractTargetRoles(message.getTargetRoles());
        if (targetRoles.isEmpty()) {
            return List.of();
        }

        return userRepository.findByRoleInAndStatusTrue(targetRoles);
    }

    private String resolveTitle(AdminAlertMessage message, String type) {
        if (TYPE_ACHIEVEMENT_UNLOCKED.equals(type)) {
            return StringUtils.hasText(message.getTitle()) ? message.getTitle().trim() : "Bạn đã đạt thành tựu mới";
        }

        if (TYPE_EXAM_UPLOAD_EXTRACTED.equals(type)) {
            return StringUtils.hasText(message.getTitle()) ? message.getTitle().trim() : "Trích xuất đề thi hoàn tất";
        }

        if (TYPE_EXAM_UPLOAD_EXTRACT_FAILED.equals(type)) {
            return StringUtils.hasText(message.getTitle()) ? message.getTitle().trim() : "Trích xuất đề thi thất bại";
        }

        return StringUtils.hasText(message.getTitle()) ? message.getTitle().trim() : "Streak của bạn vừa tăng";
    }

    private String resolveBody(AdminAlertMessage message, String type) {
        if (TYPE_ACHIEVEMENT_UNLOCKED.equals(type)) {
            List<String> names = extractAchievementNames(message.getMetadata());
            if (!names.isEmpty()) {
                return names.size() == 1
                        ? "Chúc mừng! Bạn vừa mở khóa: " + names.getFirst()
                        : "Chúc mừng! Bạn vừa mở khóa: " + String.join(", ", names);
            }
        }

        if (TYPE_EXAM_UPLOAD_EXTRACTED.equals(type) || TYPE_EXAM_UPLOAD_EXTRACT_FAILED.equals(type)) {
            if (StringUtils.hasText(message.getBody())) {
                return message.getBody().trim();
            }
            String title = extractTitle(message.getMetadata());
            if (TYPE_EXAM_UPLOAD_EXTRACTED.equals(type)) {
                return title == null
                        ? "Đề thi đã trích xuất xong. Bạn có thể mở để kiểm tra."
                        : "Đề \"" + title + "\" đã trích xuất xong. Bạn có thể mở để kiểm tra.";
            }
            return title == null
                    ? "Đề thi trích xuất thất bại. Vui lòng kiểm tra lại hàng đợi."
                    : "Đề \"" + title + "\" trích xuất thất bại. Vui lòng kiểm tra lại hàng đợi.";
        }

        if (TYPE_STREAK_QUALIFIED.equals(type)) {
            int streakDays = parsePositiveInt(
                    message.getMetadata() != null ? message.getMetadata().get("streakDays") : null);
            int todayStudyMinutes = parsePositiveInt(
                    message.getMetadata() != null ? message.getMetadata().get("todayStudyMinutes") : null);

            if (streakDays > 0 && todayStudyMinutes > 0) {
                return String.format("Bạn đã giữ streak %d ngày liên tiếp (%d phút hôm nay).", streakDays,
                        todayStudyMinutes);
            }
            if (streakDays > 0) {
                return String.format("Bạn đã giữ streak %d ngày liên tiếp. Giữ phong độ nhé!", streakDays);
            }
        }

        if (StringUtils.hasText(message.getBody())) {
            return message.getBody().trim();
        }

        return TYPE_ACHIEVEMENT_UNLOCKED.equals(type)
                ? "Bạn vừa mở khóa thêm thành tựu học tập."
                : "Bạn vừa hoàn thành mục tiêu học hôm nay. Tiếp tục giữ nhịp nhé!";
    }

    private String resolveActionUrl(String rawUrl, String type) {
        if (!StringUtils.hasText(rawUrl)) {
            if (TYPE_EXAM_UPLOAD_EXTRACTED.equals(type) || TYPE_EXAM_UPLOAD_EXTRACT_FAILED.equals(type)) {
                return DEFAULT_UPLOAD_QUEUE_URL;
            }
            return DEFAULT_GAMIFICATION_URL;
        }

        String normalized = rawUrl.trim();
        return "/gamification".equals(normalized) ? DEFAULT_GAMIFICATION_URL : normalized;
    }

    private String extractTitle(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get("title");
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String normalizeType(String rawType) {
        return rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
    }

    private List<Role> extractTargetRoles(List<String> targetRoles) {
        if (targetRoles == null || targetRoles.isEmpty()) {
            return List.of();
        }

        List<Role> roles = new ArrayList<>();
        for (String role : targetRoles) {
            if (!StringUtils.hasText(role)) {
                continue;
            }

            try {
                roles.add(Role.valueOf(role.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                log.warn("Skip unsupported role '{}' from admin alert targetRoles", role);
            }
        }

        return roles.stream().distinct().toList();
    }

    private Set<Long> extractTargetUserIds(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Set.of();
        }

        Set<Long> userIds = new LinkedHashSet<>();
        Long singleUserId = parseUserId(metadata.get("targetUserId"));
        if (singleUserId != null) {
            userIds.add(singleUserId);
        }

        Object many = metadata.get("targetUserIds");
        if (many instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Long userId = parseUserId(item);
                if (userId != null) {
                    userIds.add(userId);
                }
            }
        }

        return userIds;
    }

    private Long parseUserId(Object raw) {
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }

        if (raw instanceof String text) {
            try {
                long value = Long.parseLong(text.trim());
                return value > 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private List<String> extractAchievementNames(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }

        Object rawNames = metadata.get("achievementNames");
        if (!(rawNames instanceof Collection<?> rawCollection) || rawCollection.isEmpty()) {
            return List.of();
        }

        return rawCollection.stream()
                .filter(item -> item != null)
                .map(String::valueOf)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private int parsePositiveInt(Object raw) {
        if (raw instanceof Number number) {
            int value = number.intValue();
            return value > 0 ? value : 0;
        }

        if (raw instanceof String text) {
            try {
                int value = Integer.parseInt(text.trim());
                return value > 0 ? value : 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }

        return 0;
    }
}
