package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.AuthBootstrapAdminProperties;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.List;

import static org.springframework.util.StringUtils.hasText;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthBootstrapAdminInitializer implements ApplicationRunner {

    private static final String DEFAULT_ADMIN_EMAIL = "admin@exam-bank.local";
    private static final String DEFAULT_ADMIN_PASSWORD = "Admin@123456";
    private static final String DEFAULT_ADMIN_FULL_NAME = "System Administrator";

    private static final String DEFAULT_CONTRIBUTOR_EMAIL = "contributor@exam-bank.local";
    private static final String DEFAULT_CONTRIBUTOR_PASSWORD = "Contributor@123456";
    private static final String DEFAULT_CONTRIBUTOR_FULL_NAME = "Exam Bank Contributor";

    private static final String DEFAULT_AUDIT_EMAIL = "audit@exam-bank.local";
    private static final String DEFAULT_AUDIT_PASSWORD = "Audit@123456";
    private static final String DEFAULT_AUDIT_FULL_NAME = "Exam Bank Auditor";

    private static final String DEFAULT_SYSTEM_ADMIN_EMAIL = "system-admin@exam-bank.local";
    private static final String DEFAULT_SYSTEM_ADMIN_PASSWORD = "SystemAdmin@123456";
    private static final String DEFAULT_SYSTEM_ADMIN_FULL_NAME = "System Administrator";

    private static final String DEFAULT_USER_EMAIL = "user@exam-bank.local";
    private static final String DEFAULT_USER_PASSWORD = "User@123456";
    private static final String DEFAULT_USER_FULL_NAME = "Exam Bank User";

    private final AuthBootstrapAdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        List<SeedAccount> seedAccounts = List.of(
                new SeedAccount(Role.ADMIN,
                        normalizeEmail(defaultIfBlank(properties.getEmail(), DEFAULT_ADMIN_EMAIL)),
                        defaultIfBlank(properties.getPassword(), DEFAULT_ADMIN_PASSWORD),
                        defaultIfBlank(properties.getFullName(), DEFAULT_ADMIN_FULL_NAME),
                        properties.isUpdatePassword()),
                new SeedAccount(Role.CONTRIBUTOR, DEFAULT_CONTRIBUTOR_EMAIL, DEFAULT_CONTRIBUTOR_PASSWORD,
                        DEFAULT_CONTRIBUTOR_FULL_NAME, false),
                new SeedAccount(Role.AUDIT, DEFAULT_AUDIT_EMAIL, DEFAULT_AUDIT_PASSWORD,
                        DEFAULT_AUDIT_FULL_NAME, false),
                new SeedAccount(Role.SYSTEM_ADMIN, DEFAULT_SYSTEM_ADMIN_EMAIL, DEFAULT_SYSTEM_ADMIN_PASSWORD,
                        DEFAULT_SYSTEM_ADMIN_FULL_NAME, false),
                new SeedAccount(Role.USER, DEFAULT_USER_EMAIL, DEFAULT_USER_PASSWORD,
                        DEFAULT_USER_FULL_NAME, false));

        for (SeedAccount seedAccount : seedAccounts) {
            upsertAccount(seedAccount);
        }
    }

    private void upsertAccount(SeedAccount seedAccount) {
        String email = normalizeEmail(seedAccount.email());
        String rawPassword = trimToNull(seedAccount.password());
        String fullName = trimToNull(seedAccount.fullName());

        if (!hasText(email) || !hasText(rawPassword)) {
            log.warn("Skipping bootstrap account for role={} because email/password is missing", seedAccount.role());
            return;
        }

        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(User::new);
        boolean created = user.getId() == null;
        boolean changed = false;

        if (created) {
            user.setEmail(email);
            user.setFullName(hasText(fullName) ? fullName : seedAccount.role().name());
            user.setEmailNotificationsEnabled(true);
            user.setWebPushNotificationsEnabled(true);
            changed = true;
        } else if (!hasText(user.getFullName()) && hasText(fullName)) {
            user.setFullName(fullName);
            changed = true;
        }

        if (created || !hasText(user.getPassword()) || seedAccount.updatePassword()) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            changed = true;
        }

        if (user.getRole() != seedAccount.role()) {
            user.setRole(seedAccount.role());
            changed = true;
        }

        if (!user.isStatus()) {
            user.setStatus(true);
            changed = true;
        }

        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            changed = true;
        }

        if (!changed) {
            log.info("Bootstrap account already up to date for role={} email={}", seedAccount.role(), email);
            return;
        }

        userRepository.save(user);
        log.info("Bootstrap account {} for role={} email={}", created ? "created" : "updated", seedAccount.role(),
                email);
    }

    private String normalizeEmail(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        String trimmed = trimToNull(value);
        return hasText(trimmed) ? trimmed : fallback;
    }

    private record SeedAccount(Role role, String email, String password, String fullName, boolean updatePassword) {
    }
}
