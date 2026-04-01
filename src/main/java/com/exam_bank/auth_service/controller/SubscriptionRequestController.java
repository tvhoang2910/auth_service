package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.request.CreatePremiumPlanRequest;
import com.exam_bank.auth_service.dto.request.ReviewSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.PremiumPlanSummaryResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionQueuePageResponse;
import com.exam_bank.auth_service.dto.response.UserSubscriptionQueueItemResponse;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.service.SubscriptionRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionRequestController {

    private final SubscriptionRequestService subscriptionRequestService;

    @GetMapping("/plans")
    public ResponseEntity<List<PremiumPlanSummaryResponse>> getActivePlans() {
        List<PremiumPlanSummaryResponse> plans = subscriptionRequestService.getActivePlans();
        log.info("getActivePlans: count={}", plans.size());
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/plans/manage")
    public ResponseEntity<List<PremiumPlanSummaryResponse>> getPlansForManagement(Authentication authentication) {
        List<PremiumPlanSummaryResponse> plans = subscriptionRequestService.getPlansForManagement(authentication.getName());
        log.info("getPlansForManagement: actor={}, count={}", authentication.getName(), plans.size());
        return ResponseEntity.ok(plans);
    }

    @PostMapping("/plans")
    public ResponseEntity<PremiumPlanSummaryResponse> createPremiumPlan(
            Authentication authentication,
            @Valid @org.springframework.web.bind.annotation.RequestBody CreatePremiumPlanRequest request) {
        log.info("createPremiumPlan: actor={}, name={}, lifetime={}, durationDays={}",
            authentication.getName(),
            request.name(),
            request.lifetime(),
            request.durationDays());
        PremiumPlanSummaryResponse response = subscriptionRequestService.createPremiumPlan(authentication.getName(),
                request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/purchase-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserSubscriptionQueueItemResponse> createPurchaseRequest(
            Authentication authentication,
            @RequestParam Long planId,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String transactionRef,
            @RequestParam(required = false) String promoCode,
            @RequestParam(required = false) Boolean trial,
            @RequestPart("bill") MultipartFile bill) {
            log.info("createPurchaseRequest: actor={}, planId={}, paymentMethod={}, trial={}, billSize={}",
                authentication.getName(),
                planId,
                paymentMethod,
                trial,
                bill.getSize());
        UserSubscriptionQueueItemResponse response = subscriptionRequestService.createPurchaseRequest(
                authentication.getName(),
                planId,
                paymentMethod,
                transactionRef,
                promoCode,
                trial,
                bill);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/my-requests")
    public ResponseEntity<List<UserSubscriptionQueueItemResponse>> getMyRequests(Authentication authentication) {
        List<UserSubscriptionQueueItemResponse> requests = subscriptionRequestService.getMyRequests(authentication.getName());
        log.info("getMyRequests: actor={}, count={}", authentication.getName(), requests.size());
        return ResponseEntity.ok(requests);
    }

    @GetMapping("/review-queue")
    public ResponseEntity<SubscriptionQueuePageResponse> getReviewQueue(
            Authentication authentication,
            @RequestParam(defaultValue = "PENDING_REVIEW") SubscriptionStatus status,
            Pageable pageable) {
        log.info("getReviewQueue: actor={}, status={}, page={}, size={}",
            authentication.getName(),
            status,
            pageable.getPageNumber(),
            pageable.getPageSize());
        Page<UserSubscriptionQueueItemResponse> response = subscriptionRequestService.getReviewQueue(
                authentication.getName(),
                status,
                pageable);

        SubscriptionQueuePageResponse body = new SubscriptionQueuePageResponse(
                response.getContent(),
                response.getNumber(),
                response.getSize(),
                response.getTotalElements(),
                response.getTotalPages(),
                response.isFirst(),
                response.isLast());
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/purchase-requests/{subscriptionId}/review")
    public ResponseEntity<UserSubscriptionQueueItemResponse> reviewRequest(
            @PathVariable Long subscriptionId,
            Authentication authentication,
            @Valid @org.springframework.web.bind.annotation.RequestBody ReviewSubscriptionRequest request) {
        log.info("reviewRequest: actor={}, subscriptionId={}, approved={}",
            authentication.getName(),
            subscriptionId,
            request.approved());
        UserSubscriptionQueueItemResponse response = subscriptionRequestService.reviewRequest(
                subscriptionId,
                authentication.getName(),
                request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/purchase-requests/{subscriptionId}/approvals")
    public ResponseEntity<List<SubscriptionApprovalAuditResponse>> getApprovals(
            @PathVariable Long subscriptionId,
            Authentication authentication) {
        List<SubscriptionApprovalAuditResponse> approvals = subscriptionRequestService
            .getApprovalAudits(subscriptionId, authentication.getName());
        log.info("getApprovals: actor={}, subscriptionId={}, count={}",
            authentication.getName(),
            subscriptionId,
            approvals.size());
        return ResponseEntity.ok(approvals);
    }
}