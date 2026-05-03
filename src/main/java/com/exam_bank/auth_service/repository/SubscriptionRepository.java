package com.exam_bank.auth_service.repository;

import com.exam_bank.auth_service.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findTopByUserIdOrderByStartDateDesc(Long userId);

    List<Subscription> findByStatusAndEndDateBefore(String status, Instant endDate);
}
