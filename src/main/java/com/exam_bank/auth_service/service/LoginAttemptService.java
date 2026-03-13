package com.exam_bank.auth_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private static final String LOGIN_ATTEMPT_KEY_PREFIX = "login_attempts:";
    private static final long MAX_FAILED_ATTEMPTS = 5L;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;

    public boolean isBlocked(String email) {
        String key = buildKey(email);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return false;
        }

        long attempts = Long.parseLong(value);
        if (attempts < MAX_FAILED_ATTEMPTS) {
            return false;
        }

        // If old data reached threshold without TTL, enforce the lock window.
        Long ttlSeconds = stringRedisTemplate.getExpire(key);
        if (ttlSeconds == null || ttlSeconds <= 0) {
            stringRedisTemplate.expire(key, LOCK_DURATION);
        }
        return true;
    }

    public void recordFailedAttempt(String email) {
        String key = buildKey(email);
        Long attempts = stringRedisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts >= MAX_FAILED_ATTEMPTS) {
            stringRedisTemplate.expire(key, LOCK_DURATION);
        }
    }

    public void clearAttempts(String email) {
        stringRedisTemplate.delete(buildKey(email));
    }

    private String buildKey(String email) {
        return LOGIN_ATTEMPT_KEY_PREFIX + email;
    }
}