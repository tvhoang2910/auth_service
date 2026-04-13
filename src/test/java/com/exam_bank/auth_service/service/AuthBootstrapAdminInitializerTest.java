package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.AuthBootstrapAdminProperties;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthBootstrapAdminInitializer Unit Tests")
class AuthBootstrapAdminInitializerTest {

    @Mock
    private AuthBootstrapAdminProperties properties;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthBootstrapAdminInitializer initializer;

    @Test
    @DisplayName("run does nothing when bootstrap is disabled")
    void runDoesNothingWhenBootstrapIsDisabled() throws Exception {
        when(properties.isEnabled()).thenReturn(false);

        initializer.run(null);

        verify(userRepository, never()).findByEmailIgnoreCase(org.mockito.ArgumentMatchers.anyString());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    @DisplayName("run creates ADMIN user when bootstrap is enabled and user does not exist")
    void runCreatesAdminUserWhenEnabledAndUserDoesNotExist() throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getEmail()).thenReturn("Admin@Exam-Bank.local");
        when(properties.getPassword()).thenReturn("Admin@123456");
        when(properties.getFullName()).thenReturn("Exam Bank Admin");

        when(userRepository.findByEmailIgnoreCase("admin@exam-bank.local")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Admin@123456")).thenReturn("encoded-password");

        initializer.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getEmail()).isEqualTo("admin@exam-bank.local");
        assertThat(saved.getFullName()).isEqualTo("Exam Bank Admin");
        assertThat(saved.getPassword()).isEqualTo("encoded-password");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.isStatus()).isTrue();
        assertThat(saved.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("run upgrades existing user to ADMIN and updates password when configured")
    void runUpgradesExistingUserToAdminAndUpdatesPasswordWhenConfigured() throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getEmail()).thenReturn("existing@example.com");
        when(properties.getPassword()).thenReturn("new-password");
        when(properties.getFullName()).thenReturn("  ");
        when(properties.isUpdatePassword()).thenReturn(true);

        User existing = new User();
        existing.setId(10L);
        existing.setEmail("existing@example.com");
        existing.setFullName("Existing User");
        existing.setRole(Role.USER);
        existing.setStatus(false);
        existing.setEmailVerified(false);
        existing.setPassword("old-password");

        when(userRepository.findByEmailIgnoreCase("existing@example.com")).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new-password");

        initializer.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.isStatus()).isTrue();
        assertThat(saved.isEmailVerified()).isTrue();
        assertThat(saved.getPassword()).isEqualTo("encoded-new-password");
    }
}
