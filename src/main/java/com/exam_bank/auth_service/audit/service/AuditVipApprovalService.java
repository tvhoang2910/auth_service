package com.exam_bank.auth_service.audit.service;

import com.exam_bank.auth_service.dto.request.ReviewSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditResponse;
import com.exam_bank.auth_service.dto.response.UserSubscriptionQueueItemResponse;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.service.SubscriptionRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AuditVipApprovalService {

    private final SubscriptionRequestService subscriptionRequestService;

    @Transactional(readOnly = true)
    public Page<UserSubscriptionQueueItemResponse> getVipReviewQueue(SubscriptionStatus status, Pageable pageable, String reviewerEmail) {
        SubscriptionStatus safeStatus = status == null ? SubscriptionStatus.PENDING_REVIEW : status;
        return subscriptionRequestService.getReviewQueue(reviewerEmail, safeStatus, pageable);
    }

    public UserSubscriptionQueueItemResponse approve(Long subscriptionId, String reviewerEmail, String reviewNote) {
        return subscriptionRequestService.reviewRequest(
                subscriptionId,
                reviewerEmail,
                new ReviewSubscriptionRequest(true, reviewNote));
    }

    public UserSubscriptionQueueItemResponse reject(Long subscriptionId, String reviewerEmail, String reviewNote) {
        return subscriptionRequestService.reviewRequest(
                subscriptionId,
                reviewerEmail,
                new ReviewSubscriptionRequest(false, reviewNote));
    }

    @Transactional(readOnly = true)
    public List<SubscriptionApprovalAuditResponse> getApprovalHistory(Long subscriptionId, String reviewerEmail) {
        return subscriptionRequestService.getApprovalAudits(subscriptionId, reviewerEmail);
    }
}
