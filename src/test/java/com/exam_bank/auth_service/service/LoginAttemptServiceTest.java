package com.exam_bank.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LoginAttemptService loginAttemptService;

    @Test
    void isBlocked_shouldReturnFalseWhenRedisUnavailable() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        boolean blocked = loginAttemptService.isBlocked("user@example.com");

        assertFalse(blocked);
    }

    @Test
    void recordFailedAttempt_shouldNotThrowWhenRedisUnavailable() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> loginAttemptService.recordFailedAttempt("user@example.com"));
    }

    @Test
    void clearAttempts_shouldNotThrowWhenRedisUnavailable() {
        doThrow(new RuntimeException("redis down")).when(stringRedisTemplate).delete(anyString());

        assertDoesNotThrow(() -> loginAttemptService.clearAttempts("user@example.com"));
    }
}
