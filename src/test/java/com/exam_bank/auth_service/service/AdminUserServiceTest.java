package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.request.AdminCreateUserRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserRoleRequest;
import com.exam_bank.auth_service.dto.request.AdminUpdateUserStatusRequest;
import com.exam_bank.auth_service.dto.message.AccountLockedMessage;
import com.exam_bank.auth_service.dto.message.AccountUnlockedMessage;
import com.exam_bank.auth_service.dto.response.AdminUserItemResponse;
import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.exception.ConflictException;
import com.exam_bank.auth_service.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

        @Mock
        private UserRepository userRepository;

        @Mock
        private PasswordEncoder passwordEncoder;

        @Mock
        private RabbitTemplate rabbitTemplate;

        @Mock
        private NotificationRabbitProperties notificationRabbitProperties;

        @InjectMocks
        private AdminUserService adminUserService;

        @Test
        void getUsers_shouldApplySearchAndRoleFilter() {
                User user = new User();
                user.setId(11L);
                user.setEmail("teacher@example.com");
                user.setFullName("Teacher One");
                user.setRole(Role.CONTRIBUTOR);
                user.setStatus(true);
                user.setCreatedAt(Instant.parse("2026-03-15T00:00:00Z"));

                PageRequest pageable = PageRequest.of(0, 10);
                when(userRepository.findAll(org.mockito.ArgumentMatchers.<Specification<User>>any(),
                                org.mockito.ArgumentMatchers.eq(pageable)))
                                .thenReturn(new PageImpl<>(List.of(user), pageable, 1));

                Page<AdminUserItemResponse> result = adminUserService.getUsers(" Teacher ", Role.CONTRIBUTOR, pageable);

                assertThat(result.getTotalElements()).isEqualTo(1);
                assertThat(result.getContent().getFirst().email()).isEqualTo("teacher@example.com");
        }

        @Test
        void createUser_shouldSaveWithEncodedPasswordAndDefaultContributorRole() {
                AdminCreateUserRequest request = new AdminCreateUserRequest(
                                "Teacher@Example.com",
                                "Teacher One",
                                "super-secret-1",
                                null);

                when(userRepository.existsByEmailIgnoreCase("teacher@example.com")).thenReturn(false);
                when(passwordEncoder.encode("super-secret-1")).thenReturn("encoded-password");
                when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class))).thenAnswer(invocation -> {
                        User saved = invocation.getArgument(0);
                        saved.setId(99L);
                        saved.setStatus(true);
                        saved.setCreatedAt(Instant.parse("2026-03-15T00:00:00Z"));
                        return saved;
                });

                AdminUserItemResponse response = adminUserService.createUser(request);

                ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
                verify(userRepository).save(captor.capture());
                assertThat(captor.getValue().getPassword()).isEqualTo("encoded-password");
                assertThat(captor.getValue().getRole()).isEqualTo(Role.CONTRIBUTOR);
                assertThat(captor.getValue().isStatus()).isTrue();

                assertThat(response.id()).isEqualTo(99L);
                assertThat(response.role()).isEqualTo(Role.CONTRIBUTOR);
                assertThat(response.status()).isTrue();
                assertThat(response.statusCode()).isEqualTo(1);
                assertThat(response.statusReason()).isNull();
                assertThat(response.statusChangedBy()).isNull();
        }

        @Test
        void createUser_shouldThrowConflictWhenEmailExists() {
                AdminCreateUserRequest request = new AdminCreateUserRequest(
                                "exists@example.com",
                                "Existing User",
                                "super-secret-1",
                                Role.USER);

                when(userRepository.existsByEmailIgnoreCase("exists@example.com")).thenReturn(true);

                assertThatThrownBy(() -> adminUserService.createUser(request))
                                .isInstanceOf(ConflictException.class)
                                .hasMessage("Email already exists");
        }

        @Test
        void updateUserStatus_shouldBanUser_whenStatusCodeIs0() {
                User user = new User();
                user.setId(5L);
                user.setEmail("student@example.com");
                user.setFullName("Student One");
                user.setRole(Role.USER);
                user.setStatus(true);

                when(userRepository.findById(5L)).thenReturn(java.util.Optional.of(user));
                when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
                when(notificationRabbitProperties.getEmailAccountLockedRoutingKey())
                                .thenReturn("notification.send.email.account-locked");

                AdminUserItemResponse response = adminUserService.updateUserStatus(5L,
                                new AdminUpdateUserStatusRequest(0, null, "Violate policy"),
                                "admin@example.com");

                assertThat(response.status()).isFalse();
                assertThat(response.statusCode()).isZero();
                assertThat(response.statusReason()).isEqualTo("Violate policy");
                assertThat(response.statusChangedBy()).isEqualTo("admin@example.com");
                verify(rabbitTemplate).convertAndSend(eq("notification.events"),
                                eq("notification.send.email.account-locked"), any(AccountLockedMessage.class));
        }

        @Test
        void updateUserStatus_shouldPublishUnlockMessage_whenUserIsUnbanned() {
                User user = new User();
                user.setId(8L);
                user.setEmail("student@example.com");
                user.setFullName("Student One");
                user.setRole(Role.USER);
                user.setStatus(false);

                when(userRepository.findById(8L)).thenReturn(java.util.Optional.of(user));
                when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(notificationRabbitProperties.getExchange()).thenReturn("notification.events");
                when(notificationRabbitProperties.getEmailAccountUnlockedRoutingKey())
                                .thenReturn("notification.send.email.account-unlocked");

                AdminUserItemResponse response = adminUserService.updateUserStatus(8L,
                                new AdminUpdateUserStatusRequest(1, null, "Appeal approved"),
                                "admin@example.com");

                assertThat(response.status()).isTrue();
                assertThat(response.statusCode()).isEqualTo(1);
                verify(rabbitTemplate).convertAndSend(eq("notification.events"),
                                eq("notification.send.email.account-unlocked"), any(AccountUnlockedMessage.class));
                verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(),
                                any(AccountLockedMessage.class));
        }

        @Test
        void updateUserRole_shouldUpdateRole() {
                User user = new User();
                user.setId(6L);
                user.setEmail("teacher@example.com");
                user.setFullName("Teacher Two");
                user.setRole(Role.USER);
                user.setStatus(true);

                when(userRepository.findById(6L)).thenReturn(java.util.Optional.of(user));
                when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                AdminUserItemResponse response = adminUserService.updateUserRole(6L,
                                new AdminUpdateUserRoleRequest(Role.ADMIN));

                assertThat(response.role()).isEqualTo(Role.ADMIN);
        }
}
