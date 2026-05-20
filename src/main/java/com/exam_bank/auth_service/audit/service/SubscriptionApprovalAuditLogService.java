package com.exam_bank.auth_service.audit.service;

import java.time.Instant;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditLogPageResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditLogResponse;
import com.exam_bank.auth_service.entity.SubscriptionApprovalAudit;
import com.exam_bank.auth_service.entity.SubscriptionReviewDecision;
import com.exam_bank.auth_service.repository.SubscriptionApprovalAuditRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionApprovalAuditLogService {

    private final SubscriptionApprovalAuditRepository subscriptionApprovalAuditRepository;

    public SubscriptionApprovalAuditLogPageResponse getAuditLogs(
            int page,
            int size,
            String action,
            Long userId,
            Instant from,
            Instant to) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Order.desc("reviewedAt")));

        Specification<SubscriptionApprovalAudit> specification =
            (root, query, cb) -> cb.conjunction();

        if (action != null && !action.isBlank()) {
            SubscriptionReviewDecision decision = parseDecision(action);
            specification = specification.and((root, query, cb) -> cb.equal(root.get("decision"), decision));
        }

        if (userId != null) {
            specification = specification.and((root, query, cb) ->
                    cb.equal(root.get("reviewer").get("id"), userId));
        }

        if (from != null) {
            specification = specification.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("reviewedAt"), from));
        }

        if (to != null) {
            specification = specification.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("reviewedAt"), to));
        }

        Page<SubscriptionApprovalAuditLogResponse> result = subscriptionApprovalAuditRepository
                .findAll(specification, pageable)
                .map(this::toResponse);

        return new SubscriptionApprovalAuditLogPageResponse(
                result.getContent(),
                result.getTotalPages(),
                result.getTotalElements());
    }

    private SubscriptionReviewDecision parseDecision(String raw) {
        try {
            return SubscriptionReviewDecision.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            String allowed = String.join(", ",
                    SubscriptionReviewDecision.APPROVED.name(),
                    SubscriptionReviewDecision.REJECTED.name());
            throw new ResponseStatusException(BAD_REQUEST,
                    "Invalid action value. Allowed: " + allowed);
        }
    }

    private SubscriptionApprovalAuditLogResponse toResponse(SubscriptionApprovalAudit audit) {
        var reviewer = audit.getReviewer();
        var subscription = audit.getSubscription();
        var customer = subscription == null ? null : subscription.getUser();
        return new SubscriptionApprovalAuditLogResponse(
            reviewer == null ? null : reviewer.getId(),
            reviewer == null ? null : reviewer.getFullName(),
            reviewer == null ? null : reviewer.getEmail(),
                audit.getDecision() == null ? null : audit.getDecision().name(),
            subscription == null ? null : subscription.getId(),
            customer == null ? null : customer.getFullName(),
            customer == null ? null : customer.getEmail(),
                audit.getPreviousStatus() == null ? null : audit.getPreviousStatus().name(),
                audit.getNewStatus() == null ? null : audit.getNewStatus().name(),
                audit.getReviewedAt());
    }
}
