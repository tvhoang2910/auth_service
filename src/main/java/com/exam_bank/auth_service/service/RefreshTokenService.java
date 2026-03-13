package com.exam_bank.auth_service.service;

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
public class RefreshTokenService {

    private static final String USER_KEY_PREFIX = "auth:refresh:user:";
    private static final String TOKEN_KEY_PREFIX = "auth:refresh:value:";

    private final StringRedisTemplate stringRedisTemplate;

    public void store(Long userId, String refreshToken, Duration ttl) {
        String userKey = buildUserKey(userId);
        String tokenKey = buildTokenKey(refreshToken);

        stringRedisTemplate.opsForValue().set(userKey, refreshToken, ttl);
        stringRedisTemplate.opsForValue().set(tokenKey, String.valueOf(userId), ttl);
    }

    public Optional<String> findByUserId(Long userId) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(buildUserKey(userId)));
    }

    public Optional<Long> resolveUserId(String refreshToken) {
        String value = stringRedisTemplate.opsForValue().get(buildTokenKey(refreshToken));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value));
    }

    public void revokeByUserId(Long userId) {
        String userKey = buildUserKey(userId);
        String refreshToken = stringRedisTemplate.opsForValue().get(userKey);
        stringRedisTemplate.delete(userKey);
        if (refreshToken != null && !refreshToken.isBlank()) {
            stringRedisTemplate.delete(buildTokenKey(refreshToken));
        }
    }

    public void revokeByToken(String refreshToken) {
        Optional<Long> userId = resolveUserId(refreshToken);
        stringRedisTemplate.delete(buildTokenKey(refreshToken));
        userId.ifPresent(id -> stringRedisTemplate.delete(buildUserKey(id)));
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
