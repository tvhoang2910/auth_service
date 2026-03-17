package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.request.AdminCreateUserRequest;
import com.exam_bank.auth_service.dto.request.AdminImportUserItemRequest;
import com.exam_bank.auth_service.dto.request.AdminImportUsersRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserRoleRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserStatusRequest;
import com.exam_bank.auth_service.dto.message.AccountLockedMessage;
import com.exam_bank.auth_service.dto.message.AccountUnlockedMessage;
import com.exam_bank.auth_service.dto.response.AdminImportUserErrorResponse;
import com.exam_bank.auth_service.dto.response.AdminImportUsersResponse;
import com.exam_bank.auth_service.dto.response.AdminUserItemResponse;
import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.exception.ConflictException;
import com.exam_bank.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.springframework.util.StringUtils.hasText;

@Service
@Slf4j
@RequiredArgsConstructor
public class AdminUserService {

    private static final int USER_STATUS_BANNED = 0;
    private static final int USER_STATUS_ACTIVE = 1;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RabbitTemplate rabbitTemplate;
    private final NotificationRabbitProperties notificationRabbitProperties;

    @Transactional(readOnly = true)
    public Page<AdminUserItemResponse> getUsers(String search, Role role, Pageable pageable) {
        String normalizedSearch = normalizeSearch(search);
        Page<User> page;
        if (!hasText(normalizedSearch) && role == null) {
            page = userRepository.findAll(pageable);
        } else if (!hasText(normalizedSearch) && role != null) {
            page = userRepository.findByRole(role, pageable);
        } else {
            page = userRepository.findAll(buildSearchSpecification(normalizedSearch, role), pageable);
        }

        return page.map(this::mapToResponse);
    }

    private Specification<User> buildSearchSpecification(String normalizedSearch, Role role) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (role != null) {
                predicates.add(criteriaBuilder.equal(root.get("role"), role));
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

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setFullName(request.fullName().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(request.role() == null ? Role.CONTRIBUTOR : request.role());
        user.setStatus(true);
        user.setEmailVerified(true);
        user.setAvatarUrl(null);
        user.setPhoneNumber(null);
        user.setSchool(null);
        user.setSubject(null);

        User savedUser = userRepository.save(user);
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
        if (wasActiveBefore && !active) {
            publishAccountLockedMessage(savedUser, normalizedReason, normalizedActor, changedAt);
        } else if (!wasActiveBefore && active) {
            publishAccountUnlockedMessage(savedUser, normalizedReason, normalizedActor, changedAt);
        }
        return mapToResponse(savedUser);
    }

    @Transactional
    public AdminUserItemResponse updateUserRole(Long userId, AdminUpdateUserRoleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setRole(request.role());
        User savedUser = userRepository.save(user);
        return mapToResponse(savedUser);
    }

    @Transactional
    public AdminImportUsersResponse importUsers(AdminImportUsersRequest request) {
        if (request == null || request.users() == null || request.users().isEmpty()) {
            throw new IllegalArgumentException("users payload is required");
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
                User user = new User();
                user.setEmail(normalizedEmail);
                user.setFullName(item.fullName().trim());
                user.setPassword(passwordEncoder.encode(item.password()));
                user.setRole(item.role() == null ? Role.CONTRIBUTOR : item.role());
                user.setStatus(true);
                user.setEmailVerified(true);
                user.setAvatarUrl(normalizeOptionalText(item.avatarUrl()));
                user.setPhoneNumber(normalizeOptionalText(item.phoneNumber()));
                user.setSchool(normalizeOptionalText(item.school()));
                user.setSubject(normalizeOptionalText(item.subject()));
                userRepository.save(user);
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
                user.getAvatarUrl(),
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
}
