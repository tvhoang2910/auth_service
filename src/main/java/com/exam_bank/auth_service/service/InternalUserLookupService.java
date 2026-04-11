package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.internal.InternalUserDisplayNameResponse;
import com.exam_bank.auth_service.dto.internal.InternalUserPremiumStatusResponse;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.hasText;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalUserLookupService {

    private final UserRepository userRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;

    public Optional<InternalUserDisplayNameResponse> findDisplayNameByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return Optional.empty();
        }

        return userRepository.findById(userId)
                .map(user -> new InternalUserDisplayNameResponse(user.getId(), user.getFullName()))
                .filter(response -> hasText(response.fullName()));
    }

    public List<InternalUserDisplayNameResponse> findDisplayNamesByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        Set<Long> normalizedIds = userIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, String> fullNameByUserId = userRepository.findAllById(normalizedIds).stream()
                .filter(user -> hasText(user.getFullName()))
                .collect(Collectors.toMap(User::getId, User::getFullName, (left, right) -> left));

        List<InternalUserDisplayNameResponse> responses = normalizedIds.stream()
                .map(userId -> new InternalUserDisplayNameResponse(userId, fullNameByUserId.get(userId)))
                .filter(response -> hasText(response.fullName()))
                .toList();

        log.debug("Resolved {} user display names out of {} requested ids", responses.size(), normalizedIds.size());
        return responses;
    }

    public Optional<InternalUserPremiumStatusResponse> findPremiumStatusByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return Optional.empty();
        }

        if (!userRepository.existsById(userId)) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        boolean premium = userSubscriptionRepository.existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
                userId,
                SubscriptionStatus.APPROVED,
                now,
                now);

        return Optional.of(new InternalUserPremiumStatusResponse(userId, premium));
    }
}
