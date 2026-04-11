package com.exam_bank.auth_service.repository;

import com.exam_bank.auth_service.entity.PremiumPlan;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserSubscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    @EntityGraph(attributePaths = { "user", "plan" })
    List<UserSubscription> findByUserOrderByCreatedAtDesc(User user);

    @EntityGraph(attributePaths = { "user", "plan" })
    Page<UserSubscription> findByStatusOrderByCreatedAtAsc(SubscriptionStatus status, Pageable pageable);

    boolean existsByUserAndPlanAndStatusIn(User user, PremiumPlan plan, Collection<SubscriptionStatus> statuses);

    boolean existsByPlanId(Long planId);

    boolean existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
            Long userId,
            SubscriptionStatus status,
            Instant nowForStartDate,
            Instant nowForEndDate);
}