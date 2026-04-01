package com.exam_bank.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginExchangeServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OAuth2LoginExchangeService service;

    @Test
    void issueCode_shouldReturnUuidAndStoreInRedis() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String code = service.issueCode(42L);

        assertThat(code).isNotBlank();
        assertThat(code).doesNotContain("-"); // UUID without dashes

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).startsWith("auth:oauth2:exchange:");
        assertThat(valueCaptor.getValue()).isEqualTo("42");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void issueCode_shouldThrow_whenUserIdIsNull() {
        assertThatThrownBy(() -> service.issueCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id must be a positive number");
    }

    @Test
    void issueCode_shouldThrow_whenUserIdIsZero() {
        assertThatThrownBy(() -> service.issueCode(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id must be a positive number");
    }

    @Test
    void issueCode_shouldThrow_whenUserIdIsNegative() {
        assertThatThrownBy(() -> service.issueCode(-1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id must be a positive number");
    }

    @Test
    void consumeUserId_shouldReturnUserIdAndDeleteKey() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:oauth2:exchange:valid-code")).thenReturn("99");
        when(stringRedisTemplate.delete("auth:oauth2:exchange:valid-code")).thenReturn(true);

        Long userId = service.consumeUserId("valid-code");

        assertThat(userId).isEqualTo(99L);
        verify(stringRedisTemplate).delete(any(String.class));
    }

    @Test
    void consumeUserId_shouldThrowBadCredentials_whenCodeNotFound() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:oauth2:exchange:unknown")).thenReturn(null);

        assertThatThrownBy(() -> service.consumeUserId("unknown"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("OAuth2 exchange code is invalid or expired");

        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    @Test
    void consumeUserId_shouldThrowBadCredentials_whenCodeIsBlank() {
        assertThatThrownBy(() -> service.consumeUserId("   "))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("OAuth2 exchange code is invalid or expired");
    }

    @Test
    void consumeUserId_shouldThrowBadCredentials_whenStoredValueIsNotANumber() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:oauth2:exchange:invalid-value")).thenReturn("not-a-number");

        assertThatThrownBy(() -> service.consumeUserId("invalid-value"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("OAuth2 exchange code is invalid or expired");
    }

    @Test
    void consumeUserId_shouldTrimWhitespace() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("auth:oauth2:exchange:trimmed")).thenReturn("7");
        when(stringRedisTemplate.delete("auth:oauth2:exchange:trimmed")).thenReturn(true);

        Long userId = service.consumeUserId("  trimmed  ");

        assertThat(userId).isEqualTo(7L);
    }
}

