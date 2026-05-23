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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @DisplayName("run seeds five default role accounts when bootstrap is enabled")
    void runSeedsFiveDefaultRoleAccountsWhenEnabled() throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getEmail()).thenReturn("Admin@Exam-Bank.local");
        when(properties.getPassword()).thenReturn("Admin@123456");
        when(properties.getFullName()).thenReturn("Exam Bank Admin");
        when(properties.isUpdatePassword()).thenReturn(false);

        Map<String, Optional<User>> usersByEmail = new HashMap<>();
        usersByEmail.put("admin@exam-bank.local", Optional.empty());
        usersByEmail.put("contributor@exam-bank.local", Optional.empty());
        usersByEmail.put("audit@exam-bank.local", Optional.empty());
        usersByEmail.put("system-admin@exam-bank.local", Optional.empty());
        usersByEmail.put("user@exam-bank.local", Optional.empty());

        when(userRepository.findByEmailIgnoreCase(anyString()))
                .thenAnswer(invocation -> usersByEmail.getOrDefault(invocation.getArgument(0), Optional.empty()));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0));

        initializer.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(5)).save(captor.capture());

        List<User> savedUsers = captor.getAllValues();
        assertThat(savedUsers).hasSize(5);
        assertThat(savedUsers).extracting(User::getRole)
                .containsExactlyInAnyOrder(Role.ADMIN, Role.CONTRIBUTOR, Role.AUDIT, Role.SYSTEM_ADMIN, Role.USER);
        assertThat(savedUsers).extracting(User::getEmail)
                .containsExactlyInAnyOrder(
                        "admin@exam-bank.local",
                        "contributor@exam-bank.local",
                        "audit@exam-bank.local",
                        "system-admin@exam-bank.local",
                        "user@exam-bank.local");

        assertThat(savedUsers).anySatisfy(user -> {
            assertThat(user.getEmail()).isEqualTo("admin@exam-bank.local");
            assertThat(user.getFullName()).isEqualTo("Exam Bank Admin");
            assertThat(user.getPassword()).isEqualTo("encoded:Admin@123456");
            assertThat(user.getRole()).isEqualTo(Role.ADMIN);
            assertThat(user.isStatus()).isTrue();
            assertThat(user.isEmailVerified()).isTrue();
        });
    }

    @Test
    @DisplayName("run upgrades existing admin and updates password when configured")
    void runUpgradesExistingAdminAndUpdatesPasswordWhenConfigured() throws Exception {
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

        Map<String, Optional<User>> usersByEmail = new HashMap<>();
        usersByEmail.put("existing@example.com", Optional.of(existing));
        usersByEmail.put("contributor@exam-bank.local", Optional.empty());
        usersByEmail.put("audit@exam-bank.local", Optional.empty());
        usersByEmail.put("system-admin@exam-bank.local", Optional.empty());
        usersByEmail.put("user@exam-bank.local", Optional.empty());

        when(userRepository.findByEmailIgnoreCase(anyString()))
                .thenAnswer(invocation -> usersByEmail.getOrDefault(invocation.getArgument(0), Optional.empty()));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0));

        initializer.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(5)).save(captor.capture());
        User saved = captor.getAllValues().stream()
                .filter(user -> "existing@example.com".equals(user.getEmail()))
                .findFirst()
                .orElseThrow();

        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.isStatus()).isTrue();
        assertThat(saved.isEmailVerified()).isTrue();
        assertThat(saved.getPassword()).isEqualTo("encoded:new-password");
    }
}
