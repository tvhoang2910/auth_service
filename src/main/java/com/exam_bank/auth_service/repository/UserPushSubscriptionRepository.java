package com.exam_bank.auth_service.repository;

import com.exam_bank.auth_service.entity.UserPushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPushSubscriptionRepository extends JpaRepository<UserPushSubscription, Long> {

    List<UserPushSubscription> findByUserIdAndActiveTrue(Long userId);

    Optional<UserPushSubscription> findByEndpoint(String endpoint);

    Optional<UserPushSubscription> findByUserIdAndEndpoint(Long userId, String endpoint);
}
