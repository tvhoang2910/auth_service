package com.exam_bank.auth_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2LoginExchangeService Unit Tests")
class OAuth2LoginExchangeServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OAuth2LoginExchangeService service;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("issueCode stores exchange code with 2-minute TTL")
    void issueCodeStoresExchangeCodeWithTtl() {
        String code = service.issueCode(123L);

        assertThat(code).isNotBlank();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).startsWith("auth:oauth2:exchange:");
        assertThat(valueCaptor.getValue()).isEqualTo("123");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("issueCode rejects non-positive user id")
    void issueCodeRejectsNonPositiveUserId() {
        assertThatThrownBy(() -> service.issueCode(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id must be a positive number");

        assertThatThrownBy(() -> service.issueCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User id must be a positive number");
    }

    @Test
    @DisplayName("consumeUserId trims code, returns user id, and deletes exchange key")
    void consumeUserIdTrimsAndDeletesKey() {
        when(valueOperations.get("auth:oauth2:exchange:abc123")).thenReturn("42");

        Long userId = service.consumeUserId("  abc123  ");

        assertThat(userId).isEqualTo(42L);
        verify(stringRedisTemplate).delete("auth:oauth2:exchange:abc123");
    }

    @Test
    @DisplayName("consumeUserId throws when code is missing or not found")
    void consumeUserIdThrowsForMissingOrUnknownCode() {
        assertThatThrownBy(() -> service.consumeUserId("   "))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("OAuth2 exchange code is invalid or expired");

        when(valueOperations.get(anyString())).thenReturn(null);
        assertThatThrownBy(() -> service.consumeUserId("missing"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("OAuth2 exchange code is invalid or expired");

        verify(stringRedisTemplate, never()).delete("auth:oauth2:exchange:missing");
    }

    @Test
    @DisplayName("consumeUserId throws BadCredentialsException for malformed stored value")
    void consumeUserIdThrowsForMalformedStoredValue() {
        when(valueOperations.get("auth:oauth2:exchange:bad")).thenReturn("not-a-number");

        assertThatThrownBy(() -> service.consumeUserId("bad"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("OAuth2 exchange code is invalid or expired")
                .hasCauseInstanceOf(NumberFormatException.class);

        verify(stringRedisTemplate).delete("auth:oauth2:exchange:bad");
    }
}
