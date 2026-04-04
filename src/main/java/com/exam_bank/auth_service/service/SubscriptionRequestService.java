package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.NotificationRabbitProperties;
import com.exam_bank.auth_service.dto.message.SubscriptionReviewRequestedMessage;
import com.exam_bank.auth_service.dto.message.SubscriptionReviewedMessage;
import com.exam_bank.auth_service.dto.request.CreatePremiumPlanRequest;
import com.exam_bank.auth_service.dto.request.ReviewSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.PremiumPlanSummaryResponse;
import com.exam_bank.auth_service.dto.response.SubscriptionApprovalAuditResponse;
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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private static final Set<SubscriptionStatus> BLOCKING_DUPLICATE_STATUSES = EnumSet.of(
            SubscriptionStatus.PENDING_REVIEW,
            SubscriptionStatus.APPROVED);

    private final UserRepository userRepository;
    private final PremiumPlanRepository premiumPlanRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final SubscriptionApprovalAuditRepository subscriptionApprovalAuditRepository;
    private final PaymentBillStorageService paymentBillStorageService;
    private final RabbitTemplate rabbitTemplate;
    private final NotificationRabbitProperties notificationRabbitProperties;

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
        User actor = validatePlanManagerRole(actorEmail);
        List<PremiumPlanSummaryResponse> plans = premiumPlanRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::mapPlan)
                .toList();
        log.info("Actor {} loaded {} plans for management", actor.getEmail(), plans.size());
        return plans;
    }

    @Transactional
    public PremiumPlanSummaryResponse createPremiumPlan(String actorEmail, CreatePremiumPlanRequest request) {
        User actor = validatePlanManagerRole(actorEmail);
        log.info("Actor {} creating premium plan name={} lifetime={} active={}",
                actor.getEmail(),
                request.name(),
                Boolean.TRUE.equals(request.lifetime()),
                request.active() == null || request.active());

        PremiumPlan plan = new PremiumPlan();
        plan.setName(request.name().trim());
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
                approved);
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

    private User validatePlanManagerRole(String actorEmail) {
        User actor = getUserByEmail(actorEmail);
        if (actor.getRole() != Role.ADMIN && actor.getRole() != Role.CONTRIBUTOR) {
            log.warn("Forbidden plan-management access for user {} with role {}", actor.getEmail(), actor.getRole());
            throw new IllegalArgumentException("Only ADMIN or CONTRIBUTOR can create premium plans");
        }
        return actor;
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

    private void publishReviewRequestedMessage(UserSubscription subscription) {
        List<User> reviewers = userRepository.findByRoleInAndStatusTrue(List.of(Role.ADMIN, Role.CONTRIBUTOR));
        if (reviewers.isEmpty()) {
            log.warn("No active ADMIN/CONTRIBUTOR reviewers found for subscription {}", subscription.getId());
            return;
        }

        try {
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

                rabbitTemplate.convertAndSend(
                        notificationRabbitProperties.getExchange(),
                        notificationRabbitProperties.getEmailSubscriptionReviewRoutingKey(),
                        message);
                rabbitTemplate.convertAndSend(
                        notificationRabbitProperties.getExchange(),
                        notificationRabbitProperties.getWebPushSubscriptionReviewRoutingKey(),
                        message);
            }
            log.info("Published review requested notifications for subscriptionId={}", subscription.getId());
        } catch (AmqpException exception) {
            log.error("Failed to publish subscription review requested event for subscription {}", subscription.getId(),
                    exception);
        }
    }

    private boolean publishReviewedMessage(
            UserSubscription subscription,
            User reviewer,
            String reviewNote,
            Instant reviewedAt,
            boolean approved) {
        SubscriptionReviewedMessage message = new SubscriptionReviewedMessage(
                subscription.getId(),
                subscription.getUser().getId(),
                subscription.getUser().getEmail(),
                subscription.getUser().getFullName(),
                subscription.getPlan().getName(),
                subscription.getPurchasedPrice(),
                approved ? "APPROVED" : "REJECTED",
                reviewer.getEmail(),
                reviewNote,
                reviewedAt.toString());

        try {
            rabbitTemplate.convertAndSend(
                    notificationRabbitProperties.getExchange(),
                    notificationRabbitProperties.getEmailSubscriptionReviewedRoutingKey(),
                    message);
            rabbitTemplate.convertAndSend(
                    notificationRabbitProperties.getExchange(),
                    notificationRabbitProperties.getWebPushSubscriptionReviewedRoutingKey(),
                    message);
            log.info("Published subscription reviewed notifications for subscriptionId={} reviewedBy={} decision={}",
                    subscription.getId(),
                    reviewer.getEmail(),
                    approved ? "APPROVED" : "REJECTED");
            return true;
        } catch (AmqpException exception) {
            log.error("Failed to publish subscription reviewed event for subscription {}", subscription.getId(),
                    exception);
            return false;
        }
    }
}