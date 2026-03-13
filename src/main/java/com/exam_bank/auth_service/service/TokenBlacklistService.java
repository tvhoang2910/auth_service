package com.exam_bank.auth_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String BLACKLIST_KEY_PREFIX = "auth:blacklist:";
    private static final Duration MIN_TTL = Duration.ofSeconds(1);

    private final StringRedisTemplate stringRedisTemplate;

    public void blacklist(String token, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = MIN_TTL;
        }

        StringRedisTemplate redis = stringRedisTemplate;
        redis.opsForValue().set(buildKey(token), "1", ttl);
    }

    public boolean isBlacklisted(String token) {
        Boolean exists = stringRedisTemplate.hasKey(buildKey(token));
        return Boolean.TRUE.equals(exists);
    }

    private String buildKey(String token) {
        return BLACKLIST_KEY_PREFIX + sha256(token);
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
