package com.exam_bank.auth_service.service;

import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.util.StringUtils.hasText;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.message.EmailOtpMessage;
import com.exam_bank.auth_service.dto.message.AccountLockedMessage;
import com.exam_bank.auth_service.dto.message.AccountUnlockedMessage;
import com.exam_bank.auth_service.dto.request.AdminCreateUserRequest;
import com.exam_bank.auth_service.dto.request.AdminImportUserItemRequest;
import com.exam_bank.auth_service.dto.request.AdminImportUsersRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserRoleRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserStatusRequest;
import com.exam_bank.auth_service.dto.response.AdminImportUserErrorResponse;
import com.exam_bank.auth_service.dto.response.AdminImportUsersResponse;
import com.exam_bank.auth_service.dto.response.AdminUserItemResponse;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.exception.ConflictException;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminUserService {

    private static final int USER_STATUS_BANNED = 0;
    private static final int USER_STATUS_ACTIVE = 1;
    private static final int MAX_IMPORT_BATCH_SIZE = 1000;
    private static final String TEMP_PASSWORD_PURPOSE = "TEMP_PASSWORD";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RabbitTemplate rabbitTemplate;
    private final NotificationRabbitProperties notificationRabbitProperties;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final AuthUserProfileEventPublisher authUserProfileEventPublisher;
    private final AvatarStorageService avatarStorageService;
    private final SecurityAuditService securityAuditService;
    private final UserProfileCacheService userProfileCacheService;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public Page<AdminUserItemResponse> getUsers(String search, Role role, Pageable pageable) {
        return getUsers(search, role, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminUserItemResponse> getUsers(String search, Role role, Boolean status, Pageable pageable) {
        String normalizedSearch = normalizeSearch(search);
        Page<User> page;
        if (!hasText(normalizedSearch) && role == null && status == null) {
            page = userRepository.findAll(pageable);
        } else {
            page = userRepository.findAll(buildSearchSpecification(normalizedSearch, role, status), pageable);
        }

        return page.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public AdminUserItemResponse getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return mapToResponse(user);
    }

    private Specification<User> buildSearchSpecification(String normalizedSearch, Role role, Boolean status) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (role != null) {
                predicates.add(criteriaBuilder.equal(root.get("role"), role));
            }

            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }

            if (hasText(normalizedSearch)) {
                String keyword = "%" + normalizedSearch.toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), keyword),
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("fullName")), keyword)));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    @Transactional
    public AdminUserItemResponse createUser(AdminCreateUserRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("Email already exists");
        }

        String temporaryPassword = generateTemporaryPassword();
        User user = new User();
        user.setEmail(normalizedEmail);
        user.setFullName(request.fullName().trim());
        user.setPassword(passwordEncoder.encode(temporaryPassword));
        user.setRole(request.role() == null ? Role.CONTRIBUTOR : request.role());
        user.setStatus(true);
        user.setEmailVerified(true);
        user.setAvatarUrl(null);
        user.setPhoneNumber(null);
        user.setSchool(null);
        user.setSubject(null);

        User savedUser = userRepository.save(user);
        publishTemporaryPasswordEmail(savedUser.getEmail(), temporaryPassword);
        authUserProfileEventPublisher.publish(savedUser, isPremiumActive(savedUser.getId()));
        return mapToResponse(savedUser);
    }

    @Transactional
    public AdminUserItemResponse updateUserStatus(Long userId, AdminUpdateUserStatusRequest request,
            String actorEmail) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        boolean active = resolveActiveStatus(request);
        String normalizedReason = normalizeReason(request.reason());
        String normalizedActor = normalizeActor(actorEmail);

        boolean wasActiveBefore = user.isStatus();
        user.setStatus(active);
        user.setStatusReason(normalizedReason);
        user.setStatusChangedBy(normalizedActor);
        java.time.Instant changedAt = java.time.Instant.now();
        user.setStatusChangedAt(changedAt);

        User savedUser = userRepository.save(user);
        authUserProfileEventPublisher.publish(savedUser, null);
        if (wasActiveBefore && !active) {
            publishAccountLockedMessage(savedUser, normalizedReason, normalizedActor, changedAt);
        } else if (!wasActiveBefore && active) {
            publishAccountUnlockedMessage(savedUser, normalizedReason, normalizedActor, changedAt);
        }
        logSystemAdminAction(savedUser, normalizedActor, active ? "UNLOCK_USER" : "LOCK_USER", normalizedReason);
        return mapToResponse(savedUser);
    }

    @Transactional
    public AdminUserItemResponse updateUserRole(Long userId, AdminUpdateUserRoleRequest request) {
        return updateUserRole(userId, request, null);
    }

    @Transactional
    public AdminUserItemResponse updateUserRole(Long userId, AdminUpdateUserRoleRequest request,
            String actorEmail) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setRole(request.role());
        User savedUser = userRepository.save(user);
        userProfileCacheService.evict(savedUser.getId(), savedUser.getEmail());
        authUserProfileEventPublisher.publish(savedUser, null);
        logSystemAdminAction(savedUser, normalizeActor(actorEmail), "CHANGE_ROLE",
                "targetEmail=" + savedUser.getEmail() + "; role=" + savedUser.getRole().name());
        return mapToResponse(savedUser);
    }

    @Transactional
    public AdminImportUsersResponse importUsers(AdminImportUsersRequest request) {
        if (request == null || request.users() == null || request.users().isEmpty()) {
            throw new IllegalArgumentException("users payload is required");
        }

        if (request.users().size() > MAX_IMPORT_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Batch size exceeds maximum allowed (" + MAX_IMPORT_BATCH_SIZE + ")");
        }

        boolean skipExisting = request.skipExisting() == null || request.skipExisting();
        int created = 0;
        int skipped = 0;
        int failed = 0;
        List<AdminImportUserErrorResponse> errors = new ArrayList<>();

        List<AdminImportUserItemRequest> items = request.users();
        for (int index = 0; index < items.size(); index++) {
            AdminImportUserItemRequest item = items.get(index);
            String normalizedEmail = normalizeEmail(item.email());

            if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                if (skipExisting) {
                    skipped++;
                } else {
                    failed++;
                }
                errors.add(buildDuplicateEmailError(index, normalizedEmail, skipExisting));
                continue;
            }

            try {
                String temporaryPassword = generateTemporaryPassword();
                User user = new User();
                user.setEmail(normalizedEmail);
                user.setFullName(item.fullName().trim());
                user.setPassword(passwordEncoder.encode(temporaryPassword));
                user.setRole(item.role() == null ? Role.CONTRIBUTOR : item.role());
                user.setStatus(true);
                user.setEmailVerified(true);
                user.setAvatarUrl(normalizeOptionalText(item.avatarUrl()));
                user.setPhoneNumber(normalizeOptionalText(item.phoneNumber()));
                user.setSchool(normalizeOptionalText(item.school()));
                user.setSubject(normalizeOptionalText(item.subject()));
                User savedUser = userRepository.save(user);
                try {
                    publishTemporaryPasswordEmail(savedUser.getEmail(), temporaryPassword);
                    authUserProfileEventPublisher.publish(savedUser, Boolean.FALSE);
                } catch (RuntimeException publishException) {
                    userRepository.delete(savedUser);
                    throw publishException;
                }
                created++;
            } catch (RuntimeException exception) {
                failed++;
                errors.add(new AdminImportUserErrorResponse(index, normalizedEmail, exception.getMessage()));
            }
        }

        return new AdminImportUsersResponse(items.size(), created, skipped, failed, errors);
    }

    private AdminImportUserErrorResponse buildDuplicateEmailError(int index, String email, boolean skipExisting) {
        String reason = skipExisting ? "Email already exists (skipped)" : "Email already exists";
        return new AdminImportUserErrorResponse(index, email, reason);
    }

    private boolean resolveActiveStatus(AdminUpdateUserStatusRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Status payload is required");
        }

        if (request.active() != null) {
            return request.active();
        }

        if (request.status() != null) {
            if (request.status() == USER_STATUS_ACTIVE) {
                return true;
            }
            if (request.status() == USER_STATUS_BANNED) {
                return false;
            }
            throw new IllegalArgumentException("Status must be 0 or 1");
        }

        throw new IllegalArgumentException("Either active or status must be provided");
    }

    private String normalizeReason(String reason) {
        if (!hasText(reason)) {
            throw new IllegalArgumentException("Reason is required");
        }
        return reason.trim();
    }

    private String normalizeActor(String actorEmail) {
        if (!hasText(actorEmail)) {
            return "system";
        }
        return actorEmail.trim().toLowerCase(Locale.ROOT);
    }

    private void logSystemAdminAction(User user, String actorEmail, String action, String reason) {
        String details = "targetEmail=" + user.getEmail() + "; reason=" + reason + "; status="
                + (user.isStatus() ? "ACTIVE" : "LOCKED") + "; role=" + user.getRole().name();
        securityAuditService.success(action, actorEmail, details);
    }

    private String normalizeEmail(String email) {
        if (!hasText(email)) {
            throw new IllegalArgumentException("Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeOptionalText(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String generateTemporaryPassword() {
        byte[] randomBytes = new byte[16];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private void publishTemporaryPasswordEmail(String email, String temporaryPassword) {
        EmailOtpMessage message = new EmailOtpMessage(email, temporaryPassword, TEMP_PASSWORD_PURPOSE);
        rabbitTemplate.convertAndSend(
                notificationRabbitProperties.getExchange(),
                notificationRabbitProperties.getEmailOtpRoutingKey(),
                message);
        log.info("Queued temporary password email for {}", email);
    }

    private String normalizeSearch(String search) {
        if (!hasText(search)) {
            return null;
        }
        return search.trim().toLowerCase();
    }

    private void publishAccountLockedMessage(User user, String reason, String changedBy, java.time.Instant changedAt) {
        AccountLockedMessage message = new AccountLockedMessage(
                user.getEmail(),
                user.getFullName(),
                reason,
                changedBy,
                changedAt.toString());

        try {
            rabbitTemplate.convertAndSend(
                    notificationRabbitProperties.getExchange(),
                    notificationRabbitProperties.getEmailAccountLockedRoutingKey(),
                    message);
        } catch (AmqpException exception) {
            log.error("Failed to publish account-locked message for user {}", user.getEmail(), exception);
        }
    }

    private void publishAccountUnlockedMessage(User user, String reason, String changedBy,
            java.time.Instant changedAt) {
        AccountUnlockedMessage message = new AccountUnlockedMessage(
                user.getEmail(),
                user.getFullName(),
                reason,
                changedBy,
                changedAt.toString());

        try {
            rabbitTemplate.convertAndSend(
                    notificationRabbitProperties.getExchange(),
                    notificationRabbitProperties.getEmailAccountUnlockedRoutingKey(),
                    message);
        } catch (AmqpException exception) {
            log.error("Failed to publish account-unlocked message for user {}", user.getEmail(), exception);
        }
    }

    private AdminUserItemResponse mapToResponse(User user) {
        return new AdminUserItemResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                avatarStorageService.toPublicAvatarUrl(user.getId(), user.getAvatarUrl()),
                user.getPhoneNumber(),
                user.getSchool(),
                user.getSubject(),
                user.getRole(),
                user.isStatus(),
                user.isStatus() ? USER_STATUS_ACTIVE : USER_STATUS_BANNED,
                user.getStatusReason(),
                user.getStatusChangedBy(),
                user.getCreatedAt());
    }

    private boolean isPremiumActive(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }

        Instant now = Instant.now();
        return userSubscriptionRepository.existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
                userId,
                SubscriptionStatus.APPROVED,
                now,
                now);
    }
}
