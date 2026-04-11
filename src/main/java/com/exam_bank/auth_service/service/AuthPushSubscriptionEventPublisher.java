package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.message.PushSubscriptionSyncMessage;
import com.exam_bank.auth_service.entity.Role;
import com.exam_bank.auth_service.entity.UserPushSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import static org.springframework.util.StringUtils.hasText;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthPushSubscriptionEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${auth.events.exchange:auth.events}")
    private String authEventsExchange;

    @Value("${auth.events.push-subscription-sync-routing-key:auth.push-subscription.sync}")
    private String pushSubscriptionSyncRoutingKey;

    public void publish(UserPushSubscription subscription, Role role, Boolean userActive) {
        if (subscription == null || subscription.getUserId() == null || subscription.getUserId() <= 0
                || !hasText(subscription.getEndpoint())) {
            return;
        }

        PushSubscriptionSyncMessage message = new PushSubscriptionSyncMessage(
                subscription.getUserId(),
                role != null ? role.name() : null,
                userActive,
                subscription.getEndpoint(),
                subscription.getP256dh(),
                subscription.getAuth(),
                Boolean.TRUE.equals(subscription.getActive()));

        try {
            rabbitTemplate.convertAndSend(authEventsExchange, pushSubscriptionSyncRoutingKey, message);
            log.info(
                    "Published push-subscription sync event userId={} active={} endpointHash={}",
                    subscription.getUserId(),
                    subscription.getActive(),
                    maskEndpoint(subscription.getEndpoint()));
        } catch (AmqpException exception) {
            log.warn(
                    "Failed to publish push-subscription sync event userId={} active={}: {}",
                    subscription.getUserId(),
                    subscription.getActive(),
                    exception.getMessage());
        }
    }

    private String maskEndpoint(String endpoint) {
        if (!hasText(endpoint)) {
            return "***";
        }
        return endpoint.substring(0, Math.min(18, endpoint.length()));
    }
}
