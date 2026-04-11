package com.exam_bank.auth_service.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record SubscriptionAnalyticsOverviewResponse(
        BigDecimal monthlyRevenue,
        long activePremiumCount,
        String topPlanName,
        long topPlanSubscriptions,
        Instant generatedAt) {
}
