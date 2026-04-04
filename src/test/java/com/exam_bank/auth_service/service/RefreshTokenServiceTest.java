package com.exam_bank.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @Test
    void shouldUseFallbackWhenRedisUnavailable() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        Long userId = 7L;
        String refreshToken = "token-abc";
        refreshTokenService.store(userId, refreshToken, Duration.ofMinutes(5));

        assertEquals(refreshToken, refreshTokenService.findByUserId(userId).orElse(null));
        assertEquals(userId, refreshTokenService.resolveUserId(refreshToken).orElse(null));
    }

    @Test
    void revokeByUserId_shouldClearFallbackDataWhenRedisUnavailable() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        Long userId = 9L;
        String refreshToken = "token-xyz";
        refreshTokenService.store(userId, refreshToken, Duration.ofMinutes(5));

        refreshTokenService.revokeByUserId(userId);

        assertTrue(refreshTokenService.findByUserId(userId).isEmpty());
        assertTrue(refreshTokenService.resolveUserId(refreshToken).isEmpty());
    }
}
