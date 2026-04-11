package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.internal.PushSubscriptionDto;
import com.exam_bank.auth_service.dto.request.PushSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.PushSubscriptionResponse;
import com.exam_bank.auth_service.entity.User;
import com.exam_bank.auth_service.entity.UserPushSubscription;
import com.exam_bank.auth_service.repository.UserRepository;
import com.exam_bank.auth_service.repository.UserPushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushSubscriptionService {

    private final UserPushSubscriptionRepository repository;
    private final UserRepository userRepository;
    private final AuthPushSubscriptionEventPublisher authPushSubscriptionEventPublisher;

    /**
     * Upsert by endpoint: endpoint is globally unique.
     * If endpoint already exists, it is rebound to the current user.
     */
    @Transactional
    public PushSubscriptionResponse subscribe(Long userId, PushSubscriptionRequest request) {
        UserPushSubscription sub = repository.findByEndpoint(request.endpoint())
                .orElseGet(() -> {
                    UserPushSubscription n = new UserPushSubscription();
                    n.setEndpoint(request.endpoint());
                    return n;
                });
        // Endpoint is globally unique in DB. Rebind it to current user if needed.
        sub.setUserId(userId);
        sub.setActive(true);
        sub.setP256dh(request.p256dh());
        sub.setAuth(request.auth());
        UserPushSubscription saved = repository.save(sub);
        userRepository.findById(userId)
                .ifPresentOrElse(
                        user -> authPushSubscriptionEventPublisher.publish(saved, user.getRole(), user.isStatus()),
                        () -> {
                            log.warn("User not found while publishing push-subscription sync userId={}", userId);
                            authPushSubscriptionEventPublisher.publish(saved, null, null);
                        });
        log.info("push-subscription saved: userId={}, endpointHash={}", userId,
                request.endpoint().substring(0, Math.min(20, request.endpoint().length())));
        return new PushSubscriptionResponse(saved.getId(), saved.getEndpoint(), saved.getCreatedAt());
    }

    /**
     * Soft-delete: mark subscription as inactive.
     */
    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        repository.findByUserIdAndEndpoint(userId, endpoint)
                .ifPresent(sub -> {
                    sub.setActive(false);
                    UserPushSubscription saved = repository.save(sub);
                    User user = userRepository.findById(userId).orElse(null);
                    authPushSubscriptionEventPublisher.publish(
                            saved,
                            user != null ? user.getRole() : null,
                            user != null ? user.isStatus() : null);
                    log.info("push-subscription unsubscribed: userId={}", userId);
                });
    }

    /**
     * For internal use (notification_service via REST).
     * Returns list of active subscriptions for a userId.
     * Does NOT return userId in response for security.
     */
    @Transactional(readOnly = true)
    public List<PushSubscriptionDto> getSubscriptionsByUserId(Long userId) {
        return repository.findByUserIdAndActiveTrue(userId).stream()
                .map(sub -> new PushSubscriptionDto(sub.getEndpoint(), sub.getP256dh(), sub.getAuth()))
                .toList();
    }
}
