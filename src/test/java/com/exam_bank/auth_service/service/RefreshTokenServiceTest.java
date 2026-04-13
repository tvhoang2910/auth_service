package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.exception.StorageUnavailableException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
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
    @DisplayName("store fails closed when Redis is unavailable")
    void storeFailsClosedWhenRedisUnavailable() {
        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service.store(10L, "refresh-token-1", Duration.ofMinutes(10)))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessage("Refresh token storage is temporarily unavailable");
    }

    @Test
    @DisplayName("findByUserId fails closed when Redis is unavailable")
    void findByUserIdFailsClosedWhenRedisUnavailable() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> service.findByUserId(11L))
                .isInstanceOf(StorageUnavailableException.class)
                .hasMessage("Refresh token storage is temporarily unavailable");
    }

    @Test
    @DisplayName("resolveUserId returns empty for non-numeric Redis value")
    void resolveUserIdReturnsEmptyForNonNumericRedisValue() {
        when(valueOperations.get(anyString())).thenReturn("not-a-number");

        assertThat(service.resolveUserId("bad-token")).isEmpty();
    }

    @Test
    @DisplayName("revokeByToken deletes token and user keys in Redis")
    void revokeByTokenDeletesTokenAndUserKeys() {
        when(valueOperations.get(anyString())).thenReturn("42");

        service.revokeByToken("refresh-token-42");

        verify(stringRedisTemplate).delete(startsWith("auth:refresh:value:"));
        verify(stringRedisTemplate).delete("auth:refresh:user:42");
    }
}
