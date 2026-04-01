package com.exam_bank.auth_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.util.StringUtils.hasText;

@Service
@RequiredArgsConstructor
public class OAuth2LoginExchangeService {

    private static final Duration EXCHANGE_CODE_TTL = Duration.ofMinutes(2);
    private static final String EXCHANGE_CODE_KEY_PREFIX = "auth:oauth2:exchange:";
    private static final String INVALID_CODE_MESSAGE = "OAuth2 exchange code is invalid or expired";

    private final StringRedisTemplate stringRedisTemplate;

    public String issueCode(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User id must be a positive number");
        }

        String code = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(buildKey(code), String.valueOf(userId), EXCHANGE_CODE_TTL);
        return code;
    }

    public Long consumeUserId(String code) {
        String normalizedCode = normalizeCode(code);
        String key = buildKey(normalizedCode);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (!hasText(value)) {
            throw new BadCredentialsException(INVALID_CODE_MESSAGE);
        }

        stringRedisTemplate.delete(key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BadCredentialsException(INVALID_CODE_MESSAGE, exception);
        }
    }

    private String normalizeCode(String code) {
        if (!hasText(code)) {
            throw new BadCredentialsException(INVALID_CODE_MESSAGE);
        }
        return code.trim();
    }

    private String buildKey(String code) {
        return EXCHANGE_CODE_KEY_PREFIX + code;
    }
}