package com.exam_bank.auth_service.repository;

import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserSubscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    @EntityGraph(attributePaths = { "user", "plan" })
    List<UserSubscription> findByUserOrderByCreatedAtDesc(User user);

    @EntityGraph(attributePaths = { "user", "plan" })
    Page<UserSubscription> findByStatusOrderByCreatedAtAsc(SubscriptionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = { "user", "plan" })
    @Query("""
            select us from UserSubscription us
            where (:useSearch = false
            or lower(us.user.fullName) like lower(concat('%', :search, '%'))
            or lower(us.user.email) like lower(concat('%', :search, '%'))
            or lower(us.plan.name) like lower(concat('%', :search, '%'))
            or lower(coalesce(us.transactionRef, '')) like lower(concat('%', :search, '%')))
              and (:useStatus = false or us.status = :status)
              and (:useFromDate = false or us.createdAt >= :fromDate)
              and (:useToDate = false or us.createdAt <= :toDate)
            """)
    Page<UserSubscription> searchHistory(
            @Param("useSearch") boolean useSearch,
            @Param("search") String search,
            @Param("useStatus") boolean useStatus,
            @Param("status") SubscriptionStatus status,
            @Param("useFromDate") boolean useFromDate,
            @Param("fromDate") Instant fromDate,
            @Param("useToDate") boolean useToDate,
            @Param("toDate") Instant toDate,
            Pageable pageable);

    boolean existsByUserIdAndStatus(Long userId, SubscriptionStatus status);

    boolean existsByPlanId(Long planId);

    @Query("""
            select coalesce(sum(us.purchasedPrice), 0)
            from UserSubscription us
            where us.status = :status
              and us.startDate >= :fromDate
              and us.startDate <= :toDate
            """)
    BigDecimal sumPurchasedPriceByStatusAndStartDateBetween(
            @Param("status") SubscriptionStatus status,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate);

    @Query("""
            select count(us)
            from UserSubscription us
            where us.status = :status
              and us.startDate <= :referenceTime
              and us.endDate > :referenceTime
            """)
    long countActiveByStatus(
            @Param("status") SubscriptionStatus status,
            @Param("referenceTime") Instant referenceTime);

    @Query("""
            select us.plan.name as planName,
               count(us.id) as subscriptionCount
            from UserSubscription us
            where us.status = :status
            group by us.plan.id, us.plan.name
            order by count(us.id) desc, us.plan.name asc
            """)
    List<PlanSubscriptionCountProjection> findPlanSubscriptionStatsByStatus(
            @Param("status") SubscriptionStatus status,
            Pageable pageable);

    @EntityGraph(attributePaths = { "user", "plan" })
    List<UserSubscription> findByStatusAndEndDateBefore(SubscriptionStatus status, Instant now);

    @EntityGraph(attributePaths = { "user", "plan" })
    @Query("""
            select us from UserSubscription us
            where us.status = :status
              and us.endDate > :windowStart
              and us.endDate <= :windowEnd
              and us.expiryReminderSentAt is null
            """)
    List<UserSubscription> findForExpiryReminder(
            @Param("status") SubscriptionStatus status,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    boolean existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
            Long userId,
            SubscriptionStatus status,
            Instant nowForStartDate,
            Instant nowForEndDate);
}