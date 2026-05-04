package com.exam_bank.auth_service.controller;

import com.exam_bank.auth_service.dto.request.CancelSubscriptionRequest;
import com.exam_bank.auth_service.dto.request.CreatePremiumPlanRequest;
import com.exam_bank.auth_service.dto.request.ReviewSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.CancelSubscriptionResponse;
import com.exam_bank.auth_service.dto.response.PremiumPlanSummaryResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionAnalyticsOverviewResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionHistoryPageResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionQueuePageResponse;
import com.exam_bank.auth_service.dto.response.UserSubscriptionQueueItemResponse;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.service.SubscriptionRequestService;
import com.exam_bank.auth_service.service.PaymentBillStorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionRequestController {

    private static final Set<String> ALLOWED_HISTORY_SORT_FIELDS = Set.of(
            "createdAt",
            "startDate",
            "endDate",
            "status",
            "purchasedPrice",
            "modifiedAt");

    private final SubscriptionRequestService subscriptionRequestService;

    @GetMapping("/plans")
    public ResponseEntity<List<PremiumPlanSummaryResponse>> getActivePlans() {
        List<PremiumPlanSummaryResponse> plans = subscriptionRequestService.getActivePlans();
        log.info("getActivePlans: count={}", plans.size());
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/plans/manage")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PremiumPlanSummaryResponse>> getPlansForManagement(
            Authentication authentication,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active) {
        List<PremiumPlanSummaryResponse> plans = subscriptionRequestService
                .getPlansForManagement(authentication.getName(), search, active);
        log.info("getPlansForManagement: actor={}, count={}, search={}, active={}",
                authentication.getName(),
                plans.size(),
                search,
                active);
        return ResponseEntity.ok(plans);
    }

    @PostMapping("/plans")
    @PreAuthorize("hasRole('ADMIN')")
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

    @PutMapping("/plans/{planId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PremiumPlanSummaryResponse> updatePremiumPlan(
            @PathVariable Long planId,
            Authentication authentication,
            @Valid @org.springframework.web.bind.annotation.RequestBody CreatePremiumPlanRequest request) {
        log.info("updatePremiumPlan: actor={}, planId={}, name={}, lifetime={}, durationDays={}",
                authentication.getName(),
                planId,
                request.name(),
                request.lifetime(),
                request.durationDays());
        PremiumPlanSummaryResponse response = subscriptionRequestService.updatePremiumPlan(planId,
                authentication.getName(),
                request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/plans/{planId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePremiumPlan(@PathVariable Long planId, Authentication authentication) {
        log.info("deletePremiumPlan: actor={}, planId={}", authentication.getName(), planId);
        subscriptionRequestService.deletePremiumPlan(planId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/purchase-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @PreAuthorize("hasAnyRole('USER','CONTRIBUTOR','ADMIN','AUDIT')")
    public ResponseEntity<UserSubscriptionQueueItemResponse> createPurchaseRequest(
            Authentication authentication,
            @RequestParam Long planId,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String transactionRef,
            @RequestParam(required = false) String promoCode,
            @RequestParam(required = false) Boolean trial,
                        @RequestPart(value = "bill", required = false) MultipartFile bill) {
        log.info("createPurchaseRequest: actor={}, planId={}, paymentMethod={}, trial={}, billSize={}",
                authentication.getName(),
                planId,
                paymentMethod,
                trial,
                                bill == null ? 0 : bill.getSize());
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
        List<UserSubscriptionQueueItemResponse> requests = subscriptionRequestService
                .getMyRequests(authentication.getName());
        log.info("getMyRequests: actor={}, count={}", authentication.getName(), requests.size());
        return ResponseEntity.ok(requests);
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionHistoryPageResponse> getSubscriptionHistory(
            Authentication authentication,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        Instant fromDate = parseDateTime(from, false);
        Instant toDate = parseDateTime(to, true);
        Pageable pageable = buildHistoryPageable(page, size, sort);

        SubscriptionHistoryPageResponse response = subscriptionRequestService.getSubscriptionHistory(
                authentication.getName(),
                search,
                status,
                fromDate,
                toDate,
                pageable);
        log.info("getSubscriptionHistory: actor={}, page={}, size={}, search={}, status={}, from={}, to={}, sort={}",
                authentication.getName(),
                pageable.getPageNumber(),
                pageable.getPageSize(),
                search,
                status,
                fromDate,
                toDate,
                sort);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{subscriptionId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CancelSubscriptionResponse> cancelSubscription(
            @PathVariable Long subscriptionId,
            Authentication authentication,
            @Valid @org.springframework.web.bind.annotation.RequestBody CancelSubscriptionRequest request) {
        log.info("cancelSubscription: actor={}, subscriptionId={}", authentication.getName(), subscriptionId);
        CancelSubscriptionResponse response = subscriptionRequestService.cancelSubscription(
                subscriptionId,
                authentication.getName(),
                request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/analytics/overview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionAnalyticsOverviewResponse> getSubscriptionAnalyticsOverview(
            Authentication authentication) {
        SubscriptionAnalyticsOverviewResponse response = subscriptionRequestService
                .getSubscriptionAnalyticsOverview(authentication.getName());
        log.info("getSubscriptionAnalyticsOverview: actor={}, generatedAt={}",
                authentication.getName(),
                response.generatedAt());
        return ResponseEntity.ok(response);
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

    @GetMapping("/purchase-requests/{subscriptionId}/bill")
    public ResponseEntity<byte[]> viewBillImage(
            @PathVariable Long subscriptionId,
            Authentication authentication) {
        PaymentBillStorageService.BillFileContent bill = subscriptionRequestService
                .getBillFileForReview(subscriptionId, authentication.getName());
        MediaType mediaType = MediaType.parseMediaType(bill.contentType());

        log.info("viewBillImage: actor={}, subscriptionId={}, objectKey={}",
                authentication.getName(),
                subscriptionId,
                bill.objectKey());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"bill-" + subscriptionId + "\"")
                .body(bill.content());
    }

    private Pageable buildHistoryPageable(int page, int size, String rawSort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Sort sort = parseSort(rawSort);
        return PageRequest.of(safePage, safeSize, sort);
    }

    private Sort parseSort(String rawSort) {
        if (rawSort == null || rawSort.isBlank()) {
            return Sort.by(Sort.Order.desc("createdAt"));
        }

        String[] parts = rawSort.split(",");
        String property = parts[0].trim();
        if (!ALLOWED_HISTORY_SORT_FIELDS.contains(property)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Invalid sort field. Allowed: " + String.join(",", ALLOWED_HISTORY_SORT_FIELDS));
        }

        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length > 1 && !parts[1].isBlank()) {
            try {
                direction = Sort.Direction.fromString(parts[1].trim());
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid sort direction. Use asc or desc");
            }
        }

        return Sort.by(new Sort.Order(direction, property));
    }

    private Instant parseDateTime(String raw, boolean endOfDayIfDateOnly) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            // Fallback to datetime-local format.
        }

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(raw);
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // Fallback to date-only format.
        }

        try {
            LocalDate localDate = LocalDate.parse(raw);
            if (endOfDayIfDateOnly) {
                return localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusNanos(1);
            }
            return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Invalid date format. Use ISO-8601 (e.g. 2026-04-04T10:30:00Z)");
        }
    }
}