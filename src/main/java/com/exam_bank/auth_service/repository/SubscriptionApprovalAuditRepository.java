package com.exam_bank.auth_service.repository;

import com.exam_bank.auth_service.entity.SubscriptionApprovalAudit;
import com.exam_bank.auth_service.entity.UserSubscription;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionApprovalAuditRepository extends JpaRepository<SubscriptionApprovalAudit, Long> {

    @EntityGraph(attributePaths = { "reviewer", "subscription" })
    List<SubscriptionApprovalAudit> findBySubscriptionOrderByReviewedAtDesc(UserSubscription subscription);
}