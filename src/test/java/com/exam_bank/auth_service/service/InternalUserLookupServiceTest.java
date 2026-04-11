package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.internal.InternalUserDisplayNameResponse;
import com.exam_bank.auth_service.dto.internal.InternalUserPremiumStatusResponse;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalUserLookupService Unit Tests")
class InternalUserLookupServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSubscriptionRepository userSubscriptionRepository;

    @InjectMocks
    private InternalUserLookupService internalUserLookupService;

    @Test
    @DisplayName("findDisplayNameByUserId returns user display-name when user exists")
    void findDisplayNameByUserIdReturnsDisplayName() {
        User user = new User();
        user.setId(7L);
        user.setFullName("Teacher One");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        Optional<InternalUserDisplayNameResponse> result = internalUserLookupService.findDisplayNameByUserId(7L);

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(7L);
        assertThat(result.get().fullName()).isEqualTo("Teacher One");
    }

    @Test
    @DisplayName("findDisplayNamesByUserIds keeps request order, removes invalid ids, and skips missing users")
    void findDisplayNamesByUserIdsReturnsNormalizedResults() {
        User firstUser = new User();
        firstUser.setId(11L);
        firstUser.setFullName("Student Two");

        User secondUser = new User();
        secondUser.setId(5L);
        secondUser.setFullName("Teacher One");

        when(userRepository.findAllById(any())).thenReturn(List.of(firstUser, secondUser));

        List<InternalUserDisplayNameResponse> result = internalUserLookupService.findDisplayNamesByUserIds(
                Arrays.asList(5L, null, 11L, 0L, 11L));

        assertThat(result)
                .extracting(InternalUserDisplayNameResponse::userId)
                .containsExactly(5L, 11L);
        assertThat(result)
                .extracting(InternalUserDisplayNameResponse::fullName)
                .containsExactly("Teacher One", "Student Two");
    }

    @Test
    @DisplayName("findPremiumStatusByUserId returns premium=true when active approved subscription exists")
    void findPremiumStatusByUserIdReturnsTrueWhenPremiumIsActive() {
        when(userRepository.existsById(9L)).thenReturn(true);
        when(userSubscriptionRepository.existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(SubscriptionStatus.APPROVED),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);

        Optional<InternalUserPremiumStatusResponse> result = internalUserLookupService.findPremiumStatusByUserId(9L);

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(9L);
        assertThat(result.get().premium()).isTrue();
    }

    @Test
    @DisplayName("findPremiumStatusByUserId returns empty when user does not exist")
    void findPremiumStatusByUserIdReturnsEmptyForMissingUser() {
        when(userRepository.existsById(999L)).thenReturn(false);

        Optional<InternalUserPremiumStatusResponse> result = internalUserLookupService.findPremiumStatusByUserId(999L);

        assertThat(result).isEmpty();
    }
}
