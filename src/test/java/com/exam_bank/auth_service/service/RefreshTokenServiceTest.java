package com.exam_bank.auth_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService Unit Tests")
class RefreshTokenServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("findByUserId returns fallback token when Redis store failed")
    void findByUserIdReturnsFallbackWhenRedisStoreFails() {
        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        service.store(10L, "refresh-token-1", Duration.ofMinutes(10));

        assertThat(service.findByUserId(10L)).contains("refresh-token-1");
    }

    @Test
    @DisplayName("expired fallback entries are ignored and cleaned")
    void expiredFallbackEntriesAreIgnoredAndCleaned() {
        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        service.store(11L, "expired-token", Duration.ofSeconds(-1));

        assertThat(service.findByUserId(11L)).isEmpty();
        assertThat(service.resolveUserId("expired-token")).isEmpty();
    }

    @Test
    @DisplayName("resolveUserId returns empty for non-numeric Redis value")
    void resolveUserIdReturnsEmptyForNonNumericRedisValue() {
        when(valueOperations.get(anyString())).thenReturn("not-a-number");

        assertThat(service.resolveUserId("bad-token")).isEmpty();
    }

    @Test
    @DisplayName("revokeByToken clears fallback maps and Redis keys")
    void revokeByTokenClearsFallbackAndRedisKeys() {
        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        service.store(42L, "refresh-token-42", Duration.ofMinutes(5));

        service.revokeByToken("refresh-token-42");

        assertThat(service.findByUserId(42L)).isEmpty();
        assertThat(service.resolveUserId("refresh-token-42")).isEmpty();
        verify(stringRedisTemplate).delete("auth:refresh:user:42");
    }
}
