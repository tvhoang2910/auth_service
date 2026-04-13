package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.exception.StorageUnavailableException;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final String USER_KEY_PREFIX = "auth:refresh:user:";
    private static final String TOKEN_KEY_PREFIX = "auth:refresh:value:";

    private final StringRedisTemplate stringRedisTemplate;

    public void store(Long userId, String refreshToken, Duration ttl) {
        String userKey = buildUserKey(userId);
        String tokenKey = buildTokenKey(refreshToken);

        try {
            stringRedisTemplate.opsForValue().set(userKey, refreshToken, ttl);
            stringRedisTemplate.opsForValue().set(tokenKey, String.valueOf(userId), ttl);
        } catch (Exception ex) {
            log.error("Refresh token store failed for userId={} because Redis is unavailable", userId, ex);
            throw new StorageUnavailableException("Refresh token storage is temporarily unavailable", ex);
        }
    }

    public Optional<String> findByUserId(Long userId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(buildUserKey(userId));
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Refresh token lookup failed for userId={} because Redis is unavailable", userId, ex);
            throw new StorageUnavailableException("Refresh token storage is temporarily unavailable", ex);
        }
    }

    public Optional<Long> resolveUserId(String refreshToken) {
        String tokenKey = buildTokenKey(refreshToken);
        try {
            String value = stringRedisTemplate.opsForValue().get(tokenKey);
            if (value != null && !value.isBlank()) {
                try {
                    return Optional.of(Long.parseLong(value));
                } catch (NumberFormatException ex) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.error("Refresh token resolve failed because Redis is unavailable", ex);
            throw new StorageUnavailableException("Refresh token storage is temporarily unavailable", ex);
        }
    }

    public void revokeByUserId(Long userId) {
        String userKey = buildUserKey(userId);
        try {
            String refreshToken = stringRedisTemplate.opsForValue().get(userKey);
            stringRedisTemplate.delete(userKey);
            if (refreshToken != null && !refreshToken.isBlank()) {
                stringRedisTemplate.delete(buildTokenKey(refreshToken));
            }
        } catch (Exception ex) {
            log.error("Refresh token revoke-by-user failed for userId={} because Redis is unavailable", userId, ex);
            throw new StorageUnavailableException("Refresh token storage is temporarily unavailable", ex);
        }
    }

    public void revokeByToken(String refreshToken) {
        String tokenKey = buildTokenKey(refreshToken);
        try {
            Optional<Long> userId = resolveUserId(refreshToken);
            stringRedisTemplate.delete(tokenKey);
            userId.ifPresent(id -> stringRedisTemplate.delete(buildUserKey(id)));
        } catch (Exception ex) {
            log.error("Refresh token revoke-by-token failed because Redis is unavailable", ex);
            throw new StorageUnavailableException("Refresh token storage is temporarily unavailable", ex);
        }
    }

    private String buildUserKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }

    private String buildTokenKey(String refreshToken) {
        return TOKEN_KEY_PREFIX + sha256(refreshToken);
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
