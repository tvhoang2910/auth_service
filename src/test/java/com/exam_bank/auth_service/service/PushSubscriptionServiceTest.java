package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.dto.internal.PushSubscriptionDto;
import com.exam_bank.auth_service.dto.request.PushSubscriptionRequest;
import com.exam_bank.auth_service.dto.response.PushSubscriptionResponse;
import com.exam_bank.auth_service.entity.UserPushSubscription;
import com.exam_bank.auth_service.repository.UserPushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    @Mock
    private UserPushSubscriptionRepository repository;

    @InjectMocks
    private PushSubscriptionService service;

    // ── subscribe() ───────────────────────────────────────────────

    @Test
    void subscribe_shouldCreateNewSubscription_whenEndpointNotExists() {
        // given
        PushSubscriptionRequest request = new PushSubscriptionRequest(
                "https://push.example.com/abc123",
                "BNc...xyz",
                "authSecret456");
        when(repository.findByUserIdAndEndpoint(1L, request.endpoint()))
                .thenReturn(Optional.empty());
        when(repository.save(any(UserPushSubscription.class)))
                .thenAnswer(inv -> {
                    UserPushSubscription s = inv.getArgument(0);
                    s.setId(10L);
                    s.setCreatedAt(Instant.now());
                    return s;
                });

        // when
        PushSubscriptionResponse result = service.subscribe(1L, request);

        // then
        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.endpoint()).isEqualTo(request.endpoint());

        ArgumentCaptor<UserPushSubscription> captor = ArgumentCaptor.forClass(UserPushSubscription.class);
        verify(repository).save(captor.capture());
        UserPushSubscription saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getEndpoint()).isEqualTo(request.endpoint());
        assertThat(saved.getP256dh()).isEqualTo(request.p256dh());
        assertThat(saved.getAuth()).isEqualTo(request.auth());
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    void subscribe_shouldUpdateExistingSubscription_whenEndpointExists() {
        // given
        PushSubscriptionRequest request = new PushSubscriptionRequest(
                "https://push.example.com/abc123",
                "newP256dh",
                "newAuth");
        UserPushSubscription existing = new UserPushSubscription();
        existing.setId(5L);
        existing.setUserId(1L);
        existing.setEndpoint(request.endpoint());
        existing.setP256dh("oldP256dh");
        existing.setAuth("oldAuth");
        existing.setActive(true);
        existing.setCreatedAt(Instant.now().minusSeconds(3600));

        when(repository.findByUserIdAndEndpoint(1L, request.endpoint()))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(UserPushSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // when
        PushSubscriptionResponse result = service.subscribe(1L, request);

        // then
        assertThat(result.id()).isEqualTo(5L);
        ArgumentCaptor<UserPushSubscription> captor = ArgumentCaptor.forClass(UserPushSubscription.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getP256dh()).isEqualTo("newP256dh");
        assertThat(captor.getValue().getAuth()).isEqualTo("newAuth");
    }

    // ── unsubscribe() ─────────────────────────────────────────────

    @Test
    void unsubscribe_shouldSoftDelete_whenSubscriptionExists() {
        // given
        UserPushSubscription sub = new UserPushSubscription();
        sub.setId(5L);
        sub.setUserId(1L);
        sub.setActive(true);
        when(repository.findByUserIdAndEndpoint(1L, "https://push.example.com/abc"))
                .thenReturn(Optional.of(sub));
        when(repository.save(any(UserPushSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // when
        service.unsubscribe(1L, "https://push.example.com/abc");

        // then
        ArgumentCaptor<UserPushSubscription> captor = ArgumentCaptor.forClass(UserPushSubscription.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActive()).isFalse();
    }

    @Test
    void unsubscribe_shouldDoNothing_whenSubscriptionNotFound() {
        // given
        when(repository.findByUserIdAndEndpoint(1L, "https://unknown"))
                .thenReturn(Optional.empty());

        // when
        service.unsubscribe(1L, "https://unknown");

        // then
        verify(repository, never()).save(any());
    }

    // ── getSubscriptionsByUserId() ─────────────────────────────────

    @Test
    void getSubscriptionsByUserId_shouldReturnActiveSubscriptions() {
        // given
        UserPushSubscription sub1 = new UserPushSubscription();
        sub1.setEndpoint("https://push.example.com/1");
        sub1.setP256dh("p1");
        sub1.setAuth("a1");
        UserPushSubscription sub2 = new UserPushSubscription();
        sub2.setEndpoint("https://push.example.com/2");
        sub2.setP256dh("p2");
        sub2.setAuth("a2");
        when(repository.findByUserIdAndActiveTrue(1L))
                .thenReturn(List.of(sub1, sub2));

        // when
        List<PushSubscriptionDto> result = service.getSubscriptionsByUserId(1L);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).endpoint()).isEqualTo("https://push.example.com/1");
        assertThat(result.get(0).p256dh()).isEqualTo("p1");
        assertThat(result.get(0).auth()).isEqualTo("a1");
        assertThat(result.get(1).endpoint()).isEqualTo("https://push.example.com/2");
    }

    @Test
    void getSubscriptionsByUserId_shouldReturnEmptyList_whenNoSubscriptions() {
        // given
        when(repository.findByUserIdAndActiveTrue(99L)).thenReturn(List.of());

        // when
        List<PushSubscriptionDto> result = service.getSubscriptionsByUserId(99L);

        // then
        assertThat(result).isEmpty();
    }
}
