package com.exam_bank.auth_service.audit.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.exam_bank.auth_service.audit.service.AuditPaymentService;
import com.exam_bank.auth_service.audit.service.AuditVipApprovalService;
import com.exam_bank.auth_service.dto.request.AuditVipDecisionRequest;
import com.exam_bank.auth_service.dto.request.PaymentFeeCalculationRequest;
import com.exam_bank.auth_service.dto.response.PaymentFeeCalculationResponse;
import com.exam_bank.auth_service.dto.response.PaymentStatsResponse;
import com.exam_bank.auth_service.dto.response.PaymentTransactionResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditResponse;
import com.exam_bank.auth_service.dto.response.UserSubscriptionQueueItemResponse;
import com.exam_bank.auth_service.entity.SubscriptionStatus;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('AUDIT')")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditVipApprovalService auditVipApprovalService;
    private final AuditPaymentService auditPaymentService;

    @GetMapping("/vip/requests")
    public ResponseEntity<Page<UserSubscriptionQueueItemResponse>> getVipRequests(
            Authentication authentication,
            @RequestParam(defaultValue = "PENDING_REVIEW") SubscriptionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<UserSubscriptionQueueItemResponse> queue = auditVipApprovalService.getVipReviewQueue(
                status,
                PageRequest.of(page, Math.min(size, 100)),
                authentication.getName());
        return ResponseEntity.ok(queue);
    }

    @PatchMapping("/vip/requests/{subscriptionId}/approve")
    public ResponseEntity<UserSubscriptionQueueItemResponse> approveVip(
            @PathVariable Long subscriptionId,
            Authentication authentication,
            @Valid @RequestBody(required = false) AuditVipDecisionRequest request) {
        String reviewNote = request == null ? null : request.reviewNote();
        UserSubscriptionQueueItemResponse response = auditVipApprovalService.approve(
                subscriptionId,
                authentication.getName(),
                reviewNote);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/vip/requests/{subscriptionId}/reject")
    public ResponseEntity<UserSubscriptionQueueItemResponse> rejectVip(
            @PathVariable Long subscriptionId,
            Authentication authentication,
            @Valid @RequestBody(required = false) AuditVipDecisionRequest request) {
        String reviewNote = request == null ? null : request.reviewNote();
        UserSubscriptionQueueItemResponse response = auditVipApprovalService.reject(
                subscriptionId,
                authentication.getName(),
                reviewNote);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/vip/requests/{subscriptionId}/history")
    public ResponseEntity<List<SubscriptionApprovalAuditResponse>> getVipApprovalHistory(
            @PathVariable Long subscriptionId,
            Authentication authentication) {
        return ResponseEntity.ok(auditVipApprovalService.getApprovalHistory(subscriptionId, authentication.getName()));
    }

    @PostMapping("/payments/calculate")
    public ResponseEntity<PaymentFeeCalculationResponse> calculatePayment(
            @Valid @RequestBody PaymentFeeCalculationRequest request) {
        return ResponseEntity.ok(auditPaymentService.calculateFee(request.planId(), request.trial()));
    }

    @GetMapping("/payments/transactions")
        public ResponseEntity<Page<PaymentTransactionResponse>> getPaymentTransactions(
            @RequestParam(required = false) String user,
            @RequestParam(required = false) SubscriptionStatus status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String search = user == null || user.isBlank() ? null : user.trim();
        log.info("getPaymentTransactions: search={}, status={}, from={}, to={}, page={}, size={}", search, status, from, to, page, size);
        Instant fromDate = parseDateTime(from, false);
        Instant toDate = parseDateTime(to, true);
        Page<PaymentTransactionResponse> transactions = auditPaymentService.getPaymentTransactions(
            search,
                status,
                fromDate,
                toDate,
                PageRequest.of(page, Math.min(size, 100)));
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/payments/stats")
    public ResponseEntity<PaymentStatsResponse> getPaymentStats(
            @RequestParam(required = false) String user,
            @RequestParam(defaultValue = "APPROVED") SubscriptionStatus status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String search = user == null || user.isBlank() ? null : user.trim();
        log.info("getPaymentStats: search={}, status={}, from={}, to={}", search, status, from, to);
        Instant fromDate = parseDateTime(from, false);
        Instant toDate = parseDateTime(to, true);
        return ResponseEntity.ok(auditPaymentService.summarizePayments(search, status, fromDate, toDate));
    }

    private Instant parseDateTime(String raw, boolean endOfDayIfDateOnly) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            // fallback to datetime-local format
        }

        try {
            LocalDateTime localDateTime = LocalDateTime.parse(raw);
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // fallback to date-only format
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
