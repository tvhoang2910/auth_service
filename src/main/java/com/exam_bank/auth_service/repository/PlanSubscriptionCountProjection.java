package com.exam_bank.auth_service.repository;

public interface PlanSubscriptionCountProjection {

    String getPlanName();

    long getSubscriptionCount();
}