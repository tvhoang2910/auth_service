package com.exam_bank.auth_service.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final String USER_KEY_PREFIX = "auth:refresh:user:";
    private static final String TOKEN_KEY_PREFIX = "auth:refresh:value:";

    private final StringRedisTemplate stringRedisTemplate;
    private final Map<Long, FallbackTokenEntry> fallbackByUserId = new ConcurrentHashMap<>();
    private final Map<String, FallbackTokenEntry> fallbackByTokenKey = new ConcurrentHashMap<>();

    private record FallbackTokenEntry(Long userId, String refreshToken, Instant expiresAt) {
    }

    public void store(Long userId, String refreshToken, Duration ttl) {
        String userKey = buildUserKey(userId);
        String tokenKey = buildTokenKey(refreshToken);
        Instant expiresAt = Instant.now().plus(ttl);

        try {
            stringRedisTemplate.opsForValue().set(userKey, refreshToken, ttl);
            stringRedisTemplate.opsForValue().set(tokenKey, String.valueOf(userId), ttl);
            fallbackByUserId.remove(userId);
            fallbackByTokenKey.remove(tokenKey);
        } catch (Exception ex) {
            log.warn("Refresh token store fallback for userId={} because Redis is unavailable: {}", userId,
                    ex.getMessage());
            FallbackTokenEntry entry = new FallbackTokenEntry(userId, refreshToken, expiresAt);
            fallbackByUserId.put(userId, entry);
            fallbackByTokenKey.put(tokenKey, entry);
        }
    }

    public Optional<String> findByUserId(Long userId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(buildUserKey(userId));
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        } catch (Exception ex) {
            log.warn("Refresh token lookup fallback for userId={} because Redis is unavailable: {}", userId,
                    ex.getMessage());
        }

        FallbackTokenEntry fallback = fallbackByUserId.get(userId);
        if (fallback == null || isExpired(fallback)) {
            clearFallbackByUserId(userId);
            return Optional.empty();
        }
        return Optional.of(fallback.refreshToken());
    }

    public Optional<Long> resolveUserId(String refreshToken) {
        String tokenKey = buildTokenKey(refreshToken);
        try {
            String value = stringRedisTemplate.opsForValue().get(tokenKey);
            if (value != null && !value.isBlank()) {
                return Optional.of(Long.parseLong(value));
            }
        } catch (Exception ex) {
            log.warn("Refresh token resolve fallback because Redis is unavailable: {}", ex.getMessage());
        }

        FallbackTokenEntry fallback = fallbackByTokenKey.get(tokenKey);
        if (fallback == null || isExpired(fallback)) {
            clearFallbackByTokenKey(tokenKey);
            return Optional.empty();
        }

        return Optional.of(fallback.userId());
    }

    public void revokeByUserId(Long userId) {
        String userKey = buildUserKey(userId);
        String refreshToken = null;

        try {
            refreshToken = stringRedisTemplate.opsForValue().get(userKey);
            stringRedisTemplate.delete(userKey);
            if (refreshToken != null && !refreshToken.isBlank()) {
                stringRedisTemplate.delete(buildTokenKey(refreshToken));
            }
        } catch (Exception ex) {
            log.warn("Refresh token revoke-by-user fallback for userId={} because Redis is unavailable: {}", userId,
                    ex.getMessage());
        }

        FallbackTokenEntry fallback = fallbackByUserId.remove(userId);
        if (fallback != null) {
            fallbackByTokenKey.remove(buildTokenKey(fallback.refreshToken()));
            return;
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            fallbackByTokenKey.remove(buildTokenKey(refreshToken));
        }
    }

    public void revokeByToken(String refreshToken) {
        String tokenKey = buildTokenKey(refreshToken);
        try {
            Optional<Long> userId = resolveUserId(refreshToken);
            stringRedisTemplate.delete(tokenKey);
            userId.ifPresent(id -> stringRedisTemplate.delete(buildUserKey(id)));
        } catch (Exception ex) {
            log.warn("Refresh token revoke-by-token fallback because Redis is unavailable: {}", ex.getMessage());
        }

        FallbackTokenEntry fallback = fallbackByTokenKey.remove(tokenKey);
        if (fallback != null) {
            fallbackByUserId.remove(fallback.userId());
        }
    }

    private String buildUserKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }

    private String buildTokenKey(String refreshToken) {
        return TOKEN_KEY_PREFIX + sha256(refreshToken);
    }

    private boolean isExpired(FallbackTokenEntry entry) {
        return entry.expiresAt().isBefore(Instant.now());
    }

    private void clearFallbackByUserId(Long userId) {
        FallbackTokenEntry removed = fallbackByUserId.remove(userId);
        if (removed != null) {
            fallbackByTokenKey.remove(buildTokenKey(removed.refreshToken()));
        }
    }

    private void clearFallbackByTokenKey(String tokenKey) {
        FallbackTokenEntry removed = fallbackByTokenKey.remove(tokenKey);
        if (removed != null) {
            fallbackByUserId.remove(removed.userId());
        }
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
