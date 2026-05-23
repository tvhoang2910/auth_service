package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.message.UserProfileSyncMessage;
import com.exam_bank.auth_service.entity.User;
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
public class AuthUserProfileEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AvatarStorageService avatarStorageService;

    @Value("${auth.events.exchange:auth.events}")
    private String authEventsExchange;

    @Value("${auth.events.user-profile-sync-routing-key:auth.user.profile.sync}")
    private String userProfileSyncRoutingKey;

    public void publish(User user, Boolean premium) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            return;
        }

        UserProfileSyncMessage message = new UserProfileSyncMessage(
                user.getId(),
                hasText(user.getFullName()) ? user.getFullName().trim() : null,
                avatarStorageService.toPublicAvatarUrl(user.getId(), user.getAvatarUrl()),
                premium,
                user.getRole() != null ? user.getRole().name() : null,
                user.isStatus());

        try {
            rabbitTemplate.convertAndSend(authEventsExchange, userProfileSyncRoutingKey, message);
            log.info("Published user profile sync event userId={} premium={}", user.getId(), premium);
        } catch (AmqpException exception) {
            log.warn(
                    "Failed to publish user profile sync event userId={} premium={}: {}",
                    user.getId(),
                    premium,
                    exception.getMessage());
        }
    }
}
