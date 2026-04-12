package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.message.SubscriptionExpiryReminderMessage;
import com.exam_bank.auth_service.dto.message.SubscriptionReviewRequestedMessage;
import com.exam_bank.auth_service.dto.message.SubscriptionReviewedMessage;
import com.exam_bank.auth_service.dto.request.CancelSubscriptionRequest;
import com.exam_bank.auth_service.dto.request.CreatePremiumPlanRequest;
import com.exam_bank.auth_service.dto.request.ReviewSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.CancelSubscriptionResponse;
import com.exam_bank.auth_service.dto.response.PremiumPlanSummaryResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionAnalyticsOverviewResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionHistoryItemResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionHistoryPageResponse;
import com.exam_bank.auth_service.dto.response.UserSubscriptionQueueItemResponse;
import com.exam_bank.auth_service.entity.PremiumPlan;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.SubscriptionApprovalAudit;
import com.exam_bank.auth_service.entity.SubscriptionReviewDecision;
import com.exam_bank.auth_service.entity.SubscriptionStatus;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserSubscription;
import com.exam_bank.auth_service.repository.PremiumPlanRepository;
import com.exam_bank.auth_service.repository.SubscriptionApprovalAuditRepository;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.repository.UserSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.util.StringUtils.hasText;

@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionRequestService {

    private static final long LIFETIME_DAYS = 36500L;
    private static final String SECURITY_AUDIT_CANCEL_ACTION = "CANCEL_SUBSCRIPTION";
    private static final String NOTIFICATION_TYPE_SUBSCRIPTION_REVIEW_REQUESTED = "SUBSCRIPTION_REVIEW_REQUESTED";
    private static final String NOTIFICATION_TYPE_SUBSCRIPTION_REVIEWED = "SUBSCRIPTION_REVIEWED";
    private static final String NOTIFICATION_TYPE_SUBSCRIPTION_EXPIRY_REMINDER = "SUBSCRIPTION_EXPIRY_REMINDER";
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Set<SubscriptionStatus> BLOCKING_DUPLICATE_STATUSES = EnumSet.of(
            SubscriptionStatus.PENDING_REVIEW,
            SubscriptionStatus.APPROVED);

    private final UserRepository userRepository;
    private final PremiumPlanRepository premiumPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionApprovalAuditRepository subscriptionApprovalAuditRepository;
    private final PaymentBillStorageService paymentBillStorageService;
    private final UserProfileCacheService userProfileCacheService;
    private final SecurityAuditService securityAuditService;
    private final RabbitTemplate rabbitTemplate;
    private final NotificationRabbitProperties notificationRabbitProperties;
    private final AuthUserProfileEventPublisher authUserProfileEventPublisher;
    private final NotificationCenterService notificationCenterService;

    @Transactional(readOnly = true)
    public List<PremiumPlanSummaryResponse> getActivePlans() {
        List<PremiumPlanSummaryResponse> plans = premiumPlanRepository.findByActiveTrueOrderByPriceAsc()
                .stream()
                .map(this::mapPlan)
                .toList();
        log.info("Loaded {} active premium plans", plans.size());
        return plans;
    }

    @Transactional(readOnly = true)
    public List<PremiumPlanSummaryResponse> getPlansForManagement(String actorEmail) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        List<PremiumPlanSummaryResponse> plans = premiumPlanRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::mapPlan)
                .toList();
        log.info("Actor {} loaded {} plans for management", actor.getEmail(), plans.size());
        return plans;
    }

    @Transactional(readOnly = true)
    public List<PremiumPlanSummaryResponse> getPlansForManagement(String actorEmail, String search, Boolean active) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        String normalizedSearch = normalizeOptionalText(search);

        List<PremiumPlanSummaryResponse> plans = premiumPlanRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .filter(plan -> active == null || plan.isActive() == active)
                .filter(plan -> matchesSearchKeyword(plan, normalizedSearch))
                .map(this::mapPlan)
                .toList();

        log.info("Actor {} loaded {} plans for management with search={} active={}",
                actor.getEmail(),
                plans.size(),
                normalizedSearch,
                active);
        return plans;
    }

    @Transactional
    public PremiumPlanSummaryResponse createPremiumPlan(String actorEmail, CreatePremiumPlanRequest request) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        String normalizedName = request.name().trim();

        if (premiumPlanRepository.existsByNameIgnoreCase(normalizedName)) {
            throw new IllegalArgumentException("Premium plan name already exists");
        }

        log.info("Actor {} creating premium plan name={} lifetime={} active={}",
                actor.getEmail(),
                normalizedName,
                Boolean.TRUE.equals(request.lifetime()),
                request.active() == null || request.active());

        PremiumPlan plan = new PremiumPlan();
        plan.setName(normalizedName);
        plan.setPrice(request.price());
        plan.setDurationDays(resolveDurationDays(request.durationDays(), request.lifetime()));
        plan.setLifetime(Boolean.TRUE.equals(request.lifetime()));
        plan.setDescription(normalizeOptionalText(request.description()));
        plan.setActive(request.active() == null || request.active());

        PremiumPlan saved = premiumPlanRepository.save(plan);
        log.info("Actor {} created premium plan id={} name={}", actor.getEmail(), saved.getId(), saved.getName());
        return mapPlan(saved);
    }

    @Transactional
    public PremiumPlanSummaryResponse updatePremiumPlan(Long planId, String actorEmail,
            CreatePremiumPlanRequest request) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        PremiumPlan plan = premiumPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Premium plan not found"));

        String normalizedName = request.name().trim();
        if (premiumPlanRepository.existsByNameIgnoreCaseAndIdNot(normalizedName, planId)) {
            throw new IllegalArgumentException("Premium plan name already exists");
        }

        plan.setName(normalizedName);
        plan.setPrice(request.price());
        plan.setDurationDays(resolveDurationDays(request.durationDays(), request.lifetime()));
        plan.setLifetime(Boolean.TRUE.equals(request.lifetime()));
        plan.setDescription(normalizeOptionalText(request.description()));
        plan.setActive(request.active() == null || request.active());

        PremiumPlan saved = premiumPlanRepository.save(plan);
        log.info("Actor {} updated premium plan id={} name={}", actor.getEmail(), saved.getId(), saved.getName());
        return mapPlan(saved);
    }

    @Transactional
    public void deletePremiumPlan(Long planId, String actorEmail) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        PremiumPlan plan = premiumPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Premium plan not found"));

        if (userSubscriptionRepository.existsByPlanId(planId)) {
            log.warn("Actor {} attempted to delete premium plan id={} that already has subscriptions",
                    actor.getEmail(),
                    planId);
            throw new IllegalArgumentException(
                    "Cannot delete plan that already has subscriptions; set it inactive instead");
        }

        premiumPlanRepository.delete(plan);
        log.info("Actor {} deleted premium plan id={} name={}", actor.getEmail(), planId, plan.getName());
    }

    @Transactional
    public UserSubscriptionQueueItemResponse createPurchaseRequest(
            String userEmail,
            Long planId,
            String paymentMethod,
            String transactionRef,
            String promoCode,
            Boolean trial,
            MultipartFile billFile) {
        User user = getUserByEmail(userEmail);
        log.info("User {} creating purchase request for planId={} paymentMethod={} trial={}",
                user.getEmail(),
                planId,
                normalizeOptionalText(paymentMethod),
                Boolean.TRUE.equals(trial));
        PremiumPlan plan = premiumPlanRepository.findById(planId)
                .filter(PremiumPlan::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Premium plan not found or inactive"));
        ensureNoBlockingDuplicateRequest(user, plan);

        String billImageUrl = paymentBillStorageService.uploadBill(user.getId(), billFile);

        Instant now = Instant.now();
        UserSubscription subscription = new UserSubscription();
        subscription.setUser(user);
        subscription.setPlan(plan);
        subscription.setPurchasedPrice(resolvePurchasedPrice(plan));
        subscription.setStartDate(now);
        subscription.setEndDate(resolveEndDate(plan, now));
        subscription.setStatus(SubscriptionStatus.PENDING_REVIEW);
        subscription.setBillImageUrl(billImageUrl);
        subscription.setPaymentMethod(normalizeOptionalText(paymentMethod));
        subscription.setTransactionRef(normalizeOptionalText(transactionRef));
        subscription.setPromoCode(normalizeOptionalText(promoCode));
        subscription.setTrial(Boolean.TRUE.equals(trial));

        UserSubscription saved = userSubscriptionRepository.save(subscription);
        log.info("Created subscription request id={} user={} plan={} status={}",
                saved.getId(),
                user.getEmail(),
                plan.getName(),
                saved.getStatus());
        publishReviewRequestedMessage(saved);
        return mapSubscription(saved);
    }

    @Transactional(readOnly = true)
    public List<UserSubscriptionQueueItemResponse> getMyRequests(String userEmail) {
        User user = getUserByEmail(userEmail);
        List<UserSubscriptionQueueItemResponse> requests = userSubscriptionRepository
                .findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(this::mapSubscription)
                .toList();
        log.info("Loaded {} subscription requests for user {}", requests.size(), user.getEmail());
        return requests;
    }

    @Transactional(readOnly = true)
    public Page<UserSubscriptionQueueItemResponse> getReviewQueue(
            String reviewerEmail,
            SubscriptionStatus status,
            Pageable pageable) {
        User reviewer = validateReviewerRole(reviewerEmail);
        Page<UserSubscriptionQueueItemResponse> queue = userSubscriptionRepository
                .findByStatusOrderByCreatedAtAsc(status, pageable)
                .map(this::mapSubscription);
        log.info("Reviewer {} loaded review queue status={} page={} size={} totalElements={}",
                reviewer.getEmail(),
                status,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                queue.getTotalElements());
        return queue;
    }

    @Transactional
    public UserSubscriptionQueueItemResponse reviewRequest(
            Long subscriptionId,
            String reviewerEmail,
            ReviewSubscriptionRequest request) {
        User reviewer = validateReviewerRole(reviewerEmail);
        log.info("Reviewer {} reviewing subscriptionId={} approved={}",
                reviewer.getEmail(),
                subscriptionId,
                Boolean.TRUE.equals(request.approved()));
        UserSubscription subscription = userSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription request not found"));

        if (subscription.getStatus() != SubscriptionStatus.PENDING_REVIEW) {
            log.warn("Reviewer {} attempted review on subscriptionId={} with non-pending status={}",
                    reviewer.getEmail(),
                    subscriptionId,
                    subscription.getStatus());
            throw new IllegalArgumentException("Only pending requests can be reviewed");
        }

        if (subscription.getUser().getId().equals(reviewer.getId())) {
            log.warn("Reviewer {} attempted to self-review subscriptionId={}", reviewer.getEmail(), subscriptionId);
            throw new IllegalArgumentException("You cannot review your own request");
        }

        Instant reviewedAt = Instant.now();
        SubscriptionStatus previousStatus = subscription.getStatus();
        boolean approved = Boolean.TRUE.equals(request.approved());
        SubscriptionStatus newStatus = approved ? SubscriptionStatus.APPROVED : SubscriptionStatus.REJECTED;

        subscription.setStatus(newStatus);
        subscription.setStartDate(reviewedAt);
        subscription.setEndDate(resolveEndDate(subscription.getPlan(), reviewedAt));
        UserSubscription savedSubscription = userSubscriptionRepository.save(subscription);
        userProfileCacheService.evict(savedSubscription.getUser().getId(), savedSubscription.getUser().getEmail());
        authUserProfileEventPublisher.publish(
                savedSubscription.getUser(),
                isUserPremiumActive(savedSubscription.getUser().getId()));

        SubscriptionApprovalAudit audit = new SubscriptionApprovalAudit();
        audit.setSubscription(savedSubscription);
        audit.setReviewer(reviewer);
        audit.setReviewerRole(reviewer.getRole());
        audit.setDecision(approved ? SubscriptionReviewDecision.APPROVED : SubscriptionReviewDecision.REJECTED);
        audit.setPreviousStatus(previousStatus);
        audit.setNewStatus(newStatus);
        audit.setReviewNote(normalizeOptionalText(request.reviewNote()));
        audit.setReviewedAt(reviewedAt);
        boolean dispatched = publishReviewedMessage(savedSubscription, reviewer, audit.getReviewNote(), reviewedAt,
                approved ? "APPROVED" : "REJECTED");
        audit.setNotificationDispatched(dispatched);
        subscriptionApprovalAuditRepository.save(audit);
        log.info("Review completed subscriptionId={} newStatus={} reviewer={} notificationDispatched={}",
                savedSubscription.getId(),
                newStatus,
                reviewer.getEmail(),
                dispatched);

        return mapSubscription(savedSubscription);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionApprovalAuditResponse> getApprovalAudits(Long subscriptionId, String reviewerEmail) {
        User reviewer = validateReviewerRole(reviewerEmail);
        UserSubscription subscription = userSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription request not found"));

        List<SubscriptionApprovalAuditResponse> audits = subscriptionApprovalAuditRepository
                .findBySubscriptionOrderByReviewedAtDesc(subscription)
                .stream()
                .map(this::mapAudit)
                .toList();
        log.info("Reviewer {} loaded {} approval audit entries for subscriptionId={}",
                reviewer.getEmail(),
                audits.size(),
                subscriptionId);
        return audits;
    }

    @Transactional(readOnly = true)
    public SubscriptionHistoryPageResponse getSubscriptionHistory(
            String actorEmail,
            String search,
            SubscriptionStatus status,
            Instant fromDate,
            Instant toDate,
            Pageable pageable) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        String normalizedSearch = normalizeOptionalText(search);

        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }

        boolean useSearch = hasText(normalizedSearch);
        boolean useStatus = status != null;
        boolean useFromDate = fromDate != null;
        boolean useToDate = toDate != null;

        Page<UserSubscription> page = userSubscriptionRepository.searchHistory(
                useSearch,
                useSearch ? normalizedSearch : "",
                useStatus,
                useStatus ? status : SubscriptionStatus.PENDING_REVIEW,
                useFromDate,
                useFromDate ? fromDate : Instant.EPOCH,
                useToDate,
                useToDate ? toDate : Instant.EPOCH,
                pageable);

        List<SubscriptionHistoryItemResponse> content = page.getContent().stream()
                .map(this::mapSubscriptionHistory)
                .toList();

        log.info(
                "Actor {} loaded subscription history totalElements={} page={} size={} search={} status={} from={} to={}",
                actor.getEmail(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                normalizedSearch,
                status,
                fromDate,
                toDate);

        return new SubscriptionHistoryPageResponse(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    @Transactional
    public CancelSubscriptionResponse cancelSubscription(
            Long subscriptionId,
            String actorEmail,
            CancelSubscriptionRequest request) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        String reason = normalizeOptionalText(request.reason());

        if (!hasText(reason)) {
            securityAuditService.failure(
                    SECURITY_AUDIT_CANCEL_ACTION,
                    actor.getEmail(),
                    buildCancellationFailureDetails(subscriptionId, null, "Missing cancellation reason"));
            throw new IllegalArgumentException("Cancellation reason is required");
        }

        UserSubscription subscription = userSubscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> {
                    securityAuditService.failure(
                            SECURITY_AUDIT_CANCEL_ACTION,
                            actor.getEmail(),
                            buildCancellationFailureDetails(subscriptionId, null, "Subscription not found"));
                    return new IllegalArgumentException("Subscription not found");
                });

        SubscriptionStatus previousStatus = subscription.getStatus();
        if (previousStatus != SubscriptionStatus.APPROVED) {
            securityAuditService.failure(
                    SECURITY_AUDIT_CANCEL_ACTION,
                    actor.getEmail(),
                    buildCancellationFailureDetails(
                            subscriptionId,
                            previousStatus,
                            "Only APPROVED subscriptions can be cancelled"));
            throw new IllegalArgumentException("Only approved subscriptions can be cancelled");
        }

        Instant cancelledAt = Instant.now();
        if (!subscription.getEndDate().isAfter(cancelledAt)) {
            securityAuditService.failure(
                    SECURITY_AUDIT_CANCEL_ACTION,
                    actor.getEmail(),
                    buildCancellationFailureDetails(subscriptionId, previousStatus, "Subscription already expired"));
            throw new IllegalArgumentException("Only active subscriptions can be cancelled");
        }

        RefundOutcome refundOutcome = calculateRefundOutcome(subscription, cancelledAt);

        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscription.setCancellationReason(reason);
        subscription.setCancelledByEmail(actor.getEmail());
        subscription.setCancelledAt(cancelledAt);
        subscription.setRefundedAmount(refundOutcome.refundAmount());
        subscription.setEndDate(cancelledAt);

        UserSubscription savedSubscription = userSubscriptionRepository.save(subscription);
        userProfileCacheService.evict(savedSubscription.getUser().getId(), savedSubscription.getUser().getEmail());
        authUserProfileEventPublisher.publish(
                savedSubscription.getUser(),
                isUserPremiumActive(savedSubscription.getUser().getId()));

        securityAuditService.success(
                SECURITY_AUDIT_CANCEL_ACTION,
                actor.getEmail(),
                buildCancellationSuccessDetails(savedSubscription, refundOutcome));

        boolean notificationDispatched = publishReviewedMessage(
                savedSubscription,
                actor,
                reason,
                cancelledAt,
                "CANCELLED");

        log.info(
                "Actor {} cancelled subscriptionId={} previousStatus={} refundPolicy={} refundRate={} refundAmount={} notificationDispatched={}",
                actor.getEmail(),
                savedSubscription.getId(),
                previousStatus,
                refundOutcome.policy(),
                refundOutcome.refundRate(),
                refundOutcome.refundAmount(),
                notificationDispatched);

        return new CancelSubscriptionResponse(
                savedSubscription.getId(),
                previousStatus,
                savedSubscription.getStatus(),
                reason,
                refundOutcome.policy(),
                refundOutcome.refundRate(),
                refundOutcome.refundAmount(),
                cancelledAt);
    }

    @Transactional
    public SubscriptionAutomationResult runSubscriptionAutomation(Instant referenceTime) {
        Instant now = referenceTime == null ? Instant.now() : referenceTime;

        int expiredCount = expireApprovedSubscriptions(now);
        int reminderCount = publishExpiryReminders(now);

        return new SubscriptionAutomationResult(now, expiredCount, reminderCount);
    }

    @Transactional(readOnly = true)
    public SubscriptionAnalyticsOverviewResponse getSubscriptionAnalyticsOverview(String actorEmail) {
        User actor = validateAdminPlanManagerRole(actorEmail);
        Instant now = Instant.now();

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        Instant fromDate = currentMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toDate = currentMonth.plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .minusNanos(1);

        BigDecimal monthlyRevenue = userSubscriptionRepository.sumPurchasedPriceByStatusAndStartDateBetween(
                SubscriptionStatus.APPROVED,
                fromDate,
                toDate);
        long activePremiumCount = userSubscriptionRepository.countActiveByStatus(SubscriptionStatus.APPROVED, now);

        var topPlans = userSubscriptionRepository.findPlanSubscriptionStatsByStatus(
                SubscriptionStatus.APPROVED,
                PageRequest.of(0, 1));
        String topPlanName = topPlans.isEmpty() ? null : topPlans.getFirst().getPlanName();
        long topPlanSubscriptions = topPlans.isEmpty() ? 0L : topPlans.getFirst().getSubscriptionCount();

        BigDecimal normalizedMonthlyRevenue = (monthlyRevenue == null ? ZERO_MONEY : monthlyRevenue)
                .setScale(2, RoundingMode.HALF_UP);

        log.info(
                "Actor {} loaded subscription analytics monthlyRevenue={} activePremiumCount={} topPlanName={} topPlanSubscriptions={}",
                actor.getEmail(),
                normalizedMonthlyRevenue,
                activePremiumCount,
                topPlanName,
                topPlanSubscriptions);

        return new SubscriptionAnalyticsOverviewResponse(
                normalizedMonthlyRevenue,
                activePremiumCount,
                topPlanName,
                topPlanSubscriptions,
                now);
    }

    private PremiumPlanSummaryResponse mapPlan(PremiumPlan plan) {
        return new PremiumPlanSummaryResponse(
                plan.getId(),
                plan.getName(),
                plan.getPrice(),
                plan.getDurationDays(),
                plan.isLifetime(),
                plan.getDescription(),
                plan.isActive());
    }

    private UserSubscriptionQueueItemResponse mapSubscription(UserSubscription subscription) {
        return new UserSubscriptionQueueItemResponse(
                subscription.getId(),
                subscription.getUser().getId(),
                subscription.getUser().getEmail(),
                subscription.getUser().getFullName(),
                subscription.getPlan().getId(),
                subscription.getPlan().getName(),
                subscription.getPurchasedPrice(),
                subscription.getStatus(),
                subscription.getBillImageUrl(),
                subscription.getPaymentMethod(),
                subscription.getTransactionRef(),
                subscription.getPromoCode(),
                subscription.isTrial(),
                subscription.getStartDate(),
                subscription.getEndDate(),
                subscription.getCreatedAt());
    }

    private SubscriptionHistoryItemResponse mapSubscriptionHistory(UserSubscription subscription) {
        return new SubscriptionHistoryItemResponse(
                subscription.getId(),
                subscription.getUser().getId(),
                subscription.getUser().getEmail(),
                subscription.getUser().getFullName(),
                subscription.getPlan().getId(),
                subscription.getPlan().getName(),
                subscription.getPurchasedPrice(),
                subscription.getStatus(),
                subscription.getBillImageUrl(),
                subscription.getPaymentMethod(),
                subscription.getTransactionRef(),
                subscription.getPromoCode(),
                subscription.isTrial(),
                subscription.getStartDate(),
                subscription.getEndDate(),
                subscription.getCreatedAt(),
                subscription.getCancellationReason(),
                subscription.getCancelledByEmail(),
                subscription.getCancelledAt(),
                subscription.getRefundedAmount());
    }

    private SubscriptionApprovalAuditResponse mapAudit(SubscriptionApprovalAudit audit) {
        return new SubscriptionApprovalAuditResponse(
                audit.getId(),
                audit.getSubscription().getId(),
                audit.getReviewer().getId(),
                audit.getReviewer().getEmail(),
                audit.getReviewerRole(),
                audit.getDecision(),
                audit.getPreviousStatus(),
                audit.getNewStatus(),
                audit.getReviewNote(),
                audit.getReviewedAt(),
                audit.isNotificationDispatched(),
                audit.getSourceChannel());
    }

    private User getUserByEmail(String email) {
        String normalizedEmail = normalizeRequiredEmail(email);
        return userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private User validateReviewerRole(String reviewerEmail) {
        User reviewer = getUserByEmail(reviewerEmail);
        if (reviewer.getRole() != Role.ADMIN && reviewer.getRole() != Role.CONTRIBUTOR) {
            log.warn("Forbidden review access for user {} with role {}", reviewer.getEmail(), reviewer.getRole());
            throw new IllegalArgumentException("Only ADMIN or CONTRIBUTOR can review payment requests");
        }
        return reviewer;
    }

    private User validateAdminPlanManagerRole(String actorEmail) {
        User actor = getUserByEmail(actorEmail);
        if (actor.getRole() != Role.ADMIN) {
            log.warn("Forbidden plan-management access for user {} with role {}", actor.getEmail(), actor.getRole());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only ADMIN can manage premium plans");
        }
        return actor;
    }

    private boolean matchesSearchKeyword(PremiumPlan plan, String normalizedSearch) {
        if (!hasText(normalizedSearch)) {
            return true;
        }
        String keyword = normalizedSearch.toLowerCase(Locale.ROOT);
        String description = plan.getDescription() == null ? "" : plan.getDescription();
        return plan.getName().toLowerCase(Locale.ROOT).contains(keyword)
                || description.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private BigDecimal resolvePurchasedPrice(PremiumPlan plan) {
        if (plan.getPrice() == null) {
            throw new IllegalArgumentException("Plan price is invalid");
        }
        return plan.getPrice();
    }

    private Instant resolveEndDate(PremiumPlan plan, Instant startDate) {
        if (plan.isLifetime()) {
            return startDate.plus(LIFETIME_DAYS, ChronoUnit.DAYS);
        }
        Integer durationDays = plan.getDurationDays();
        if (durationDays == null || durationDays <= 0) {
            durationDays = 30;
        }
        return startDate.plus(durationDays.longValue(), ChronoUnit.DAYS);
    }

    private String normalizeRequiredEmail(String email) {
        if (!hasText(email)) {
            throw new IllegalArgumentException("Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeOptionalText(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Integer resolveDurationDays(Integer requestedDurationDays, Boolean lifetime) {
        if (Boolean.TRUE.equals(lifetime)) {
            return 0;
        }
        if (requestedDurationDays == null || requestedDurationDays <= 0) {
            return 30;
        }
        return requestedDurationDays;
    }

    private RefundOutcome calculateRefundOutcome(UserSubscription subscription, Instant cancelledAt) {
        if (subscription.getPlan().isLifetime()) {
            return new RefundOutcome("NO_REFUND_LIFETIME", BigDecimal.ZERO, ZERO_MONEY);
        }

        Instant startDate = subscription.getStartDate();
        Instant endDate = subscription.getEndDate();
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            return new RefundOutcome("NO_REFUND", BigDecimal.ZERO, ZERO_MONEY);
        }
        long totalSeconds = Math.max(1L, Duration.between(startDate, endDate).getSeconds());
        long remainingSeconds = Math.max(0L, Duration.between(cancelledAt, endDate).getSeconds());

        BigDecimal refundRate = BigDecimal.valueOf(remainingSeconds)
                .divide(BigDecimal.valueOf(totalSeconds), 4, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE);

        BigDecimal refundAmount = subscription.getPurchasedPrice()
                .multiply(refundRate)
                .setScale(2, RoundingMode.HALF_UP);

        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new RefundOutcome("NO_REFUND", BigDecimal.ZERO, ZERO_MONEY);
        }

        return new RefundOutcome("PRORATED_BY_REMAINING_TIME", refundRate, refundAmount);
    }

    private String buildCancellationFailureDetails(
            Long subscriptionId,
            SubscriptionStatus currentStatus,
            String reason) {
        return "subscriptionId=" + subscriptionId
                + ", currentStatus=" + (currentStatus == null ? "null" : currentStatus)
                + ", reason=" + reason;
    }

    private String buildCancellationSuccessDetails(UserSubscription subscription, RefundOutcome refundOutcome) {
        return "subscriptionId=" + subscription.getId()
                + ", userEmail=" + subscription.getUser().getEmail()
                + ", previousStatus=APPROVED"
                + ", newStatus=" + subscription.getStatus()
                + ", refundPolicy=" + refundOutcome.policy()
                + ", refundRate=" + refundOutcome.refundRate()
                + ", refundAmount=" + refundOutcome.refundAmount();
    }

    private int expireApprovedSubscriptions(Instant now) {
        List<UserSubscription> subscriptions = userSubscriptionRepository
                .findByStatusAndEndDateBefore(SubscriptionStatus.APPROVED, now);
        if (subscriptions.isEmpty()) {
            log.info("Subscription automation expired 0 records at {}", now);
            return 0;
        }

        for (UserSubscription subscription : subscriptions) {
            subscription.setStatus(SubscriptionStatus.EXPIRED);
            subscription.setExpiryReminderSentAt(now);
        }

        List<UserSubscription> saved = userSubscriptionRepository.saveAll(subscriptions);
        for (UserSubscription subscription : saved) {
            Long userId = subscription.getUser().getId();
            String email = subscription.getUser().getEmail();
            userProfileCacheService.evict(userId, email);
            authUserProfileEventPublisher.publish(subscription.getUser(), isUserPremiumActive(userId));
        }

        log.info("Subscription automation expired {} records at {}", saved.size(), now);
        return saved.size();
    }

    private int publishExpiryReminders(Instant now) {
        Instant reminderDeadline = now.plus(3, ChronoUnit.DAYS);

        List<UserSubscription> dueReminders = userSubscriptionRepository.findForExpiryReminder(
                SubscriptionStatus.APPROVED,
                now,
                reminderDeadline);
        if (dueReminders.isEmpty()) {
            log.info("Subscription automation sent 0 expiry reminders at {}", now);
            return 0;
        }

        List<UserSubscription> remindedSubscriptions = new ArrayList<>();
        for (UserSubscription subscription : dueReminders) {
            if (publishExpiryReminderMessage(subscription, now)) {
                subscription.setExpiryReminderSentAt(now);
                remindedSubscriptions.add(subscription);
            }
        }

        if (!remindedSubscriptions.isEmpty()) {
            userSubscriptionRepository.saveAll(remindedSubscriptions);
        }

        log.info("Subscription automation sent {} expiry reminders at {}", remindedSubscriptions.size(), now);
        return remindedSubscriptions.size();
    }

    private void ensureNoBlockingDuplicateRequest(User user, PremiumPlan plan) {
        boolean exists = userSubscriptionRepository.existsByUserAndPlanAndStatusIn(
                user,
                plan,
                BLOCKING_DUPLICATE_STATUSES);
        if (exists) {
            log.warn("Duplicate subscription request blocked for user={} planId={} statuses={}",
                    user.getEmail(),
                    plan.getId(),
                    BLOCKING_DUPLICATE_STATUSES);
            throw new IllegalArgumentException("You already have a pending or approved request for this plan");
        }
    }

    private boolean isUserPremiumActive(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }

        Instant now = Instant.now();
        return userSubscriptionRepository.existsByUserIdAndStatusAndStartDateLessThanEqualAndEndDateAfter(
                userId,
                SubscriptionStatus.APPROVED,
                now,
                now);
    }

    private void publishReviewRequestedMessage(UserSubscription subscription) {
        List<User> reviewers = userRepository.findByRoleInAndStatusTrue(List.of(Role.ADMIN, Role.CONTRIBUTOR));
        if (reviewers.isEmpty()) {
            log.warn("No active ADMIN/CONTRIBUTOR reviewers found for subscription {}", subscription.getId());
            return;
        }

        log.info("Publishing review requested notifications for subscriptionId={} to {} reviewers",
                subscription.getId(),
                reviewers.size());
        for (User reviewer : reviewers) {
            SubscriptionReviewRequestedMessage message = new SubscriptionReviewRequestedMessage(
                    subscription.getId(),
                    reviewer.getId(),
                    reviewer.getEmail(),
                    reviewer.getFullName(),
                    subscription.getUser().getEmail(),
                    subscription.getUser().getFullName(),
                    subscription.getPlan().getName(),
                    subscription.getPurchasedPrice(),
                    subscription.getBillImageUrl(),
                    subscription.getTransactionRef(),
                    subscription.getCreatedAt() == null ? Instant.now().toString()
                            : subscription.getCreatedAt().toString());

            boolean emailDispatched = reviewer.isEmailNotificationsEnabled()
                    && publishToRoutingKey(
                            notificationRabbitProperties.getEmailSubscriptionReviewRoutingKey(),
                            message,
                            "subscription-review-requested email",
                            subscription.getId());
            boolean webPushDispatched = reviewer.isWebPushNotificationsEnabled()
                    && publishToRoutingKey(
                            notificationRabbitProperties.getWebPushSubscriptionReviewRoutingKey(),
                            message,
                            "subscription-review-requested web-push",
                            subscription.getId());

            notificationCenterService.createNotification(
                    reviewer,
                    NOTIFICATION_TYPE_SUBSCRIPTION_REVIEW_REQUESTED,
                    "Yeu cau duyet Premium moi",
                    String.format("%s vua gui request %s", subscription.getUser().getFullName(),
                            subscription.getPlan().getName()),
                    "/contributor/subscription-reviews");

            log.info(
                    "Published review requested notifications for reviewerId={} subscriptionId={} emailDispatched={} webPushDispatched={}",
                    reviewer.getId(),
                    subscription.getId(),
                    emailDispatched,
                    webPushDispatched);
        }
    }

    private boolean publishReviewedMessage(
            UserSubscription subscription,
            User reviewer,
            String reviewNote,
            Instant reviewedAt,
            String decision) {
        User subscriber = subscription.getUser();
        SubscriptionReviewedMessage message = new SubscriptionReviewedMessage(
                subscription.getId(),
                subscriber.getId(),
                subscriber.getEmail(),
                subscriber.getFullName(),
                subscription.getPlan().getName(),
                subscription.getPurchasedPrice(),
                decision,
                reviewer.getEmail(),
                reviewNote,
                reviewedAt.toString());

        boolean emailDispatched = subscriber.isEmailNotificationsEnabled()
                && publishToRoutingKey(
                        notificationRabbitProperties.getEmailSubscriptionReviewedRoutingKey(),
                        message,
                        "subscription-reviewed email",
                        subscription.getId());
        boolean webPushDispatched = subscriber.isWebPushNotificationsEnabled()
                && publishToRoutingKey(
                        notificationRabbitProperties.getWebPushSubscriptionReviewedRoutingKey(),
                        message,
                        "subscription-reviewed web-push",
                        subscription.getId());

        notificationCenterService.createNotification(
                subscriber,
                NOTIFICATION_TYPE_SUBSCRIPTION_REVIEWED,
                buildReviewedNotificationTitle(decision),
                String.format("Goi %s: %s", subscription.getPlan().getName(), normalizeDecisionLabel(decision)),
                "/dashboard/subscription-payments");

        log.info(
                "Published subscription reviewed notifications for subscriptionId={} reviewedBy={} decision={} emailDispatched={} webPushDispatched={}",
                subscription.getId(),
                reviewer.getEmail(),
                decision,
                emailDispatched,
                webPushDispatched);
        return emailDispatched || webPushDispatched;
    }

    private boolean publishExpiryReminderMessage(UserSubscription subscription, Instant remindedAt) {
        User subscriber = subscription.getUser();
        SubscriptionExpiryReminderMessage message = new SubscriptionExpiryReminderMessage(
                subscription.getId(),
                subscriber.getId(),
                subscriber.getEmail(),
                subscriber.getFullName(),
                subscription.getPlan().getName(),
                subscription.getPurchasedPrice(),
                subscription.getEndDate().toString(),
                remindedAt.toString());

        boolean emailDispatched = subscriber.isEmailNotificationsEnabled()
                && publishToRoutingKey(
                        notificationRabbitProperties.getEmailSubscriptionExpiryReminderRoutingKey(),
                        message,
                        "subscription-expiry-reminder email",
                        subscription.getId());
        boolean webPushDispatched = subscriber.isWebPushNotificationsEnabled()
                && publishToRoutingKey(
                        notificationRabbitProperties.getWebPushSubscriptionExpiryReminderRoutingKey(),
                        message,
                        "subscription-expiry-reminder web-push",
                        subscription.getId());

        notificationCenterService.createNotification(
                subscriber,
                NOTIFICATION_TYPE_SUBSCRIPTION_EXPIRY_REMINDER,
                "Goi Premium sap het han",
                String.format("%s se het han vao %s", subscription.getPlan().getName(), subscription.getEndDate()),
                "/dashboard/subscription-payments");

        log.info(
                "Published expiry reminder notifications for subscriptionId={} expiresAt={} emailDispatched={} webPushDispatched={}",
                subscription.getId(),
                subscription.getEndDate(),
                emailDispatched,
                webPushDispatched);
        return emailDispatched || webPushDispatched;
    }

    private boolean publishToRoutingKey(String routingKey, Object payload, String channel, Long subscriptionId) {
        try {
            rabbitTemplate.convertAndSend(
                    notificationRabbitProperties.getExchange(),
                    routingKey,
                    payload);
            return true;
        } catch (AmqpException exception) {
            log.error("Failed to publish {} for subscriptionId={}", channel, subscriptionId, exception);
            return false;
        }
    }

    private String normalizeDecisionLabel(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "APPROVED" -> "Da duoc duyet";
            case "CANCELLED" -> "Da bi huy";
            case "REJECTED" -> "Da bi tu choi";
            default -> normalized;
        };
    }

    private String buildReviewedNotificationTitle(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "APPROVED" -> "Yeu cau Premium da duoc duyet";
            case "CANCELLED" -> "Goi Premium da bi huy";
            default -> "Yeu cau Premium da bi tu choi";
        };
    }

    public record SubscriptionAutomationResult(Instant executedAt, int expiredCount, int reminderCount) {
    }

    private record RefundOutcome(String policy, BigDecimal refundRate, BigDecimal refundAmount) {
    }
}