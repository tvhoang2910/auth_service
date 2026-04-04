package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.internal.UserPresenceEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceService implements MessageListener {

    private static final String PRESENCE_CHANNEL = "presence:all";

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer redisContainer;
    private final ObjectMapper objectMapper;

    private final Map<String, List<SseEmitter>> roleEmitters = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        roleEmitters.put("ADMIN", new ArrayList<>());
        roleEmitters.put("CONTRIBUTOR", new ArrayList<>());
        redisContainer.addMessageListener(this, new ChannelTopic(PRESENCE_CHANNEL));
        log.info("PresenceService initialized, subscribed to Redis channel: {}", PRESENCE_CHANNEL);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody());
        log.debug("Redis presence event received: {}", body);
        try {
            UserPresenceEvent event = objectMapper.readValue(body, UserPresenceEvent.class);
            broadcast(event);
        } catch (Exception e) {
            log.error("Failed to deserialize presence event: {}", e.getMessage());
        }
    }

    private void broadcast(UserPresenceEvent event) {
        String role = event.getRole();
        List<SseEmitter> emitters = roleEmitters.get(role);
        if (emitters == null) {
            return;
        }

        String data;
        try {
            data = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("Failed to serialize presence event: {}", e.getMessage());
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("presence")
                        .data(data));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    public void registerEmitter(String role, SseEmitter emitter) {
        List<SseEmitter> list = roleEmitters.get(role);
        if (list != null) {
            list.add(emitter);
        }
    }

    public void removeEmitter(String role, SseEmitter emitter) {
        List<SseEmitter> list = roleEmitters.get(role);
        if (list != null) {
            list.remove(emitter);
        }
    }

    public void publishPresenceEvent(UserPresenceEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(PRESENCE_CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish presence event: {}", e.getMessage());
        }
    }

    public void onUserLogin(Long userId, String role) {
        String key = "presence:user:" + userId;
        redisTemplate.opsForValue().set(key, role, Duration.ofSeconds(60));
        Long count = redisTemplate.opsForValue().increment("presence:count:" + role);
        int onlineCount = count != null ? count.intValue() : 1;
        publishPresenceEvent(UserPresenceEvent.join(userId, role, onlineCount));
    }

    public void onUserLogout(Long userId, String role) {
        String key = "presence:user:" + userId;
        redisTemplate.delete(key);
        Long count = redisTemplate.opsForValue().decrement("presence:count:" + role);
        int onlineCount = count != null ? Math.max(0, count.intValue()) : 0;
        publishPresenceEvent(UserPresenceEvent.leave(userId, role, onlineCount));
    }

    public void heartbeat(Long userId, String role) {
        String key = "presence:user:" + userId;
        redisTemplate.expire(key, Duration.ofSeconds(60));
    }

    public int getOnlineCount(String role) {
        String val = redisTemplate.opsForValue().get("presence:count:" + role);
        return val == null ? 0 : Integer.parseInt(val);
    }
}
