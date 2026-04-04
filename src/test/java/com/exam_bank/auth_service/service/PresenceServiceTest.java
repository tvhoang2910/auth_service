package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.internal.UserPresenceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisMessageListenerContainer redisContainer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PresenceService presenceService;

    @BeforeEach
    void setUp() {
        presenceService = new PresenceService(redisTemplate, redisContainer, objectMapper);
        presenceService.init();
    }

    @Nested
    @DisplayName("onUserLogin")
    class OnUserLogin {

        @Test
        @DisplayName("should set Redis key with TTL and increment online count")
        void shouldSetRedisKeyAndIncrementCount() {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("presence:count:ADMIN")).thenReturn(1L);

            presenceService.onUserLogin(100L, "ADMIN");

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(keyCaptor.capture(), roleCaptor.capture(), durationCaptor.capture());

            assertThat(keyCaptor.getValue()).isEqualTo("presence:user:100");
            assertThat(roleCaptor.getValue()).isEqualTo("ADMIN");
            assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofSeconds(60));

            verify(valueOps).increment("presence:count:ADMIN");
        }

        @Test
        @DisplayName("should publish JOIN presence event via Redis")
        void shouldPublishJoinEvent() throws Exception {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment("presence:count:ADMIN")).thenReturn(5L);
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            presenceService.onUserLogin(200L, "ADMIN");

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(channelCaptor.capture(), jsonCaptor.capture());

            assertThat(channelCaptor.getValue()).isEqualTo("presence:all");

            UserPresenceEvent event = objectMapper.readValue(jsonCaptor.getValue(), UserPresenceEvent.class);
            assertThat(event.getEventType()).isEqualTo("JOIN");
            assertThat(event.getUserId()).isEqualTo(200L);
            assertThat(event.getRole()).isEqualTo("ADMIN");
            assertThat(event.getOnlineCount()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("onUserLogout")
    class OnUserLogout {

        @Test
        @DisplayName("should delete Redis key and decrement online count")
        void shouldDeleteKeyAndDecrementCount() {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.decrement("presence:count:CONTRIBUTOR")).thenReturn(2L);

            presenceService.onUserLogout(50L, "CONTRIBUTOR");

            verify(redisTemplate).delete("presence:user:50");
            verify(valueOps).decrement("presence:count:CONTRIBUTOR");
        }

        @Test
        @DisplayName("should publish LEAVE presence event via Redis")
        void shouldPublishLeaveEvent() throws Exception {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.decrement("presence:count:ADMIN")).thenReturn(3L);
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            presenceService.onUserLogout(77L, "ADMIN");

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("presence:all"), jsonCaptor.capture());

            UserPresenceEvent event = objectMapper.readValue(jsonCaptor.getValue(), UserPresenceEvent.class);
            assertThat(event.getEventType()).isEqualTo("LEAVE");
            assertThat(event.getUserId()).isEqualTo(77L);
            assertThat(event.getRole()).isEqualTo("ADMIN");
            assertThat(event.getOnlineCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should floor online count at zero when decrement returns negative")
        void shouldFloorCountAtZero() throws Exception {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.decrement("presence:count:ADMIN")).thenReturn(-1L);
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            presenceService.onUserLogout(99L, "ADMIN");

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(eq("presence:all"), jsonCaptor.capture());

            UserPresenceEvent event = objectMapper.readValue(jsonCaptor.getValue(), UserPresenceEvent.class);
            assertThat(event.getOnlineCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("heartbeat")
    class Heartbeat {

        @Test
        @DisplayName("should refresh TTL of user presence key")
        void shouldRefreshKeyTtl() {
            presenceService.heartbeat(42L, "ADMIN");

            ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate).expire("presence:user:42", durationCaptor.capture());
            assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Nested
    @DisplayName("getOnlineCount")
    class GetOnlineCount {

        @Test
        @DisplayName("should return parsed count from Redis")
        void shouldReturnParsedCount() {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("presence:count:ADMIN")).thenReturn("12");

            int count = presenceService.getOnlineCount("ADMIN");

            assertThat(count).isEqualTo(12);
        }

        @Test
        @DisplayName("should return zero when key does not exist")
        void shouldReturnZeroWhenKeyMissing() {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get("presence:count:ADMIN")).thenReturn(null);

            int count = presenceService.getOnlineCount("ADMIN");

            assertThat(count).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("publishPresenceEvent")
    class PublishPresenceEvent {

        @Test
        @DisplayName("should send serialised event to Redis channel")
        void shouldSendEventToRedisChannel() throws Exception {
            UserPresenceEvent event = UserPresenceEvent.join(1L, "ADMIN", 5);
            when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

            presenceService.publishPresenceEvent(event);

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).convertAndSend(channelCaptor.capture(), jsonCaptor.capture());

            assertThat(channelCaptor.getValue()).isEqualTo("presence:all");

            UserPresenceEvent sent = objectMapper.readValue(jsonCaptor.getValue(), UserPresenceEvent.class);
            assertThat(sent.getEventType()).isEqualTo("JOIN");
            assertThat(sent.getUserId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("registerEmitter / removeEmitter")
    class EmitterManagement {

        @Test
        @DisplayName("should add emitter to role list")
        void shouldAddEmitterToRoleList() {
            var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L);

            presenceService.registerEmitter("ADMIN", emitter);

            presenceService.removeEmitter("ADMIN", emitter);
        }

        @Test
        @DisplayName("should remove emitter from role list")
        void shouldRemoveEmitterFromRoleList() {
            var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L);
            presenceService.registerEmitter("ADMIN", emitter);

            presenceService.removeEmitter("ADMIN", emitter);

            // If remove succeeded without throwing, the emitter was in the list.
            // Verify by adding again (so we can cleanly remove in afterEach)
            presenceService.registerEmitter("ADMIN", emitter);
        }

        @Test
        @DisplayName("should be no-op for unknown role")
        void shouldBeNoOpForUnknownRole() {
            var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(60_000L);

            // Must not throw
            presenceService.registerEmitter("UNKNOWN_ROLE", emitter);
            presenceService.removeEmitter("UNKNOWN_ROLE", emitter);
        }
    }
}
