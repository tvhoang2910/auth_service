package com.exam_bank.auth_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.exam_bank.auth_service.entity.SubscriptionApprovalAudit;
import com.exam_bank.auth_service.entity.UserSubscription;

public interface SubscriptionApprovalAuditRepository extends JpaRepository<SubscriptionApprovalAudit, Long>,
    JpaSpecificationExecutor<SubscriptionApprovalAudit> {

    @EntityGraph(attributePaths = { "reviewer", "subscription" })
    List<SubscriptionApprovalAudit> findBySubscriptionOrderByReviewedAtDesc(UserSubscription subscription);
}