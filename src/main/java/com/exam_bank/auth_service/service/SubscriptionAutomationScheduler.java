package com.exam_bank.auth_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionAutomationScheduler {

    private final SubscriptionRequestService subscriptionRequestService;

    @Scheduled(cron = "${auth.subscription.automation.expire-cron:0 5 0 * * *}", zone = "UTC")
    public void runDailyAutomation() {
        Instant now = Instant.now();
        SubscriptionRequestService.SubscriptionAutomationResult result = subscriptionRequestService
                .runSubscriptionAutomation(now);

        log.info("Subscription automation run completed at {}: expiredCount={}, reminderCount={}",
                result.executedAt(),
                result.expiredCount(),
                result.reminderCount());
    }
}
