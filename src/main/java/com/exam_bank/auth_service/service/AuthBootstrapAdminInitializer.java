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

import static org.springframework.util.StringUtils.hasText;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthBootstrapAdminInitializer implements ApplicationRunner {

    private final AuthBootstrapAdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        String email = normalizeEmail(properties.getEmail());
        String rawPassword = trimToNull(properties.getPassword());
        String fullName = trimToNull(properties.getFullName());

        if (!hasText(email) || !hasText(rawPassword)) {
            log.warn(
                    "Admin bootstrap is enabled but email/password is missing. Set auth.bootstrap-admin.email and auth.bootstrap-admin.password");
            return;
        }

        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(User::new);
        boolean created = user.getId() == null;
        boolean changed = false;

        if (created) {
            user.setEmail(email);
            user.setFullName(hasText(fullName) ? fullName : "System Administrator");
            user.setEmailNotificationsEnabled(true);
            user.setWebPushNotificationsEnabled(true);
            changed = true;
        } else if (!hasText(user.getFullName()) && hasText(fullName)) {
            user.setFullName(fullName);
            changed = true;
        }

        if (created || !hasText(user.getPassword()) || properties.isUpdatePassword()) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            changed = true;
        }

        if (user.getRole() != Role.ADMIN) {
            user.setRole(Role.ADMIN);
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
            log.info("Bootstrap admin already up to date for email={}", email);
            return;
        }

        userRepository.save(user);
        log.info("Bootstrap admin {} for email={}", created ? "created" : "updated", email);
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
}
