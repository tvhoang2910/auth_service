package com.exam_bank.auth_service.dto.message;

import java.math.BigDecimal;

public record SubscriptionReviewRequestedMessage(
                Long subscriptionId,
                Long reviewerUserId,
                String reviewerEmail,
                String reviewerFullName,
                String userEmail,
                String userFullName,
                String planName,
                BigDecimal purchasedPrice,
                String billImageUrl,
                String transactionRef,
                String submittedAt) {
}