package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.AuthUserProfileCacheProperties;
import com.exam_bank.auth_service.dto.response.UserProfileResponse;
import com.exam_bank.auth_service.entity.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserProfileCacheService {

    private static final String ID_KEY_PREFIX = "user_profile:v2:id:";
    private static final String EMAIL_KEY_PREFIX = "user_profile:v2:email:";

    private final StringRedisTemplate stringRedisTemplate;
    private final AuthUserProfileCacheProperties cacheProperties;

    public Optional<UserProfileResponse> find(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(buildIdKey(userId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        UserProfileResponse profile = deserialize(value);
        if (profile == null) {
            stringRedisTemplate.delete(buildIdKey(userId));
            return Optional.empty();
        }

        return Optional.of(profile);
    }

    public Optional<UserProfileResponse> findByEmail(String email) {
        String value = stringRedisTemplate.opsForValue().get(buildEmailKey(email));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        UserProfileResponse profile = deserialize(value);
        if (profile == null) {
            stringRedisTemplate.delete(buildEmailKey(email));
            return Optional.empty();
        }

        return Optional.of(profile);
    }

    public void store(Long userId, UserProfileResponse profile) {
        String value = serialize(profile);
        Duration ttl = Duration.ofSeconds(cacheProperties.getTtlSeconds());
        stringRedisTemplate.opsForValue().set(buildIdKey(userId), value, ttl);
        if (profile.email() != null && !profile.email().isBlank()) {
            stringRedisTemplate.opsForValue().set(buildEmailKey(profile.email()), value, ttl);
        }
    }

    public void evict(Long userId) {
        stringRedisTemplate.delete(buildIdKey(userId));
    }

    public void evict(Long userId, String email) {
        stringRedisTemplate.delete(buildIdKey(userId));
        if (email != null && !email.isBlank()) {
            stringRedisTemplate.delete(buildEmailKey(email));
        }
    }

    private String buildIdKey(Long userId) {
        return ID_KEY_PREFIX + userId;
    }

    private String buildEmailKey(String email) {
        return EMAIL_KEY_PREFIX + email.trim().toLowerCase();
    }

    private String serialize(UserProfileResponse profile) {
        String role = profile.role() == null ? Role.USER.name() : profile.role().name();

        return "{" +
                "\"id\":" + profile.id() +
                ",\"email\":" + quoteNullable(profile.email()) +
                ",\"fullName\":" + quoteNullable(profile.fullName()) +
                ",\"avatarUrl\":" + quoteNullable(profile.avatarUrl()) +
                ",\"phoneNumber\":" + quoteNullable(profile.phoneNumber()) +
                ",\"school\":" + quoteNullable(profile.school()) +
                ",\"subject\":" + quoteNullable(profile.subject()) +
                ",\"role\":" + quoteNullable(role) +
                ",\"premium\":" + profile.premium() +
                "}";
    }

    private UserProfileResponse deserialize(String value) {
        try {
            Long id = Long.parseLong(extractRaw(value, "id"));
            String email = extractNullableString(value, "email");
            String fullName = extractNullableString(value, "fullName");
            String avatarUrl = extractNullableString(value, "avatarUrl");
            String phoneNumber = extractNullableString(value, "phoneNumber");
            String school = extractNullableString(value, "school");
            String subject = extractNullableString(value, "subject");
            String roleValue = extractNullableString(value, "role");
            boolean premium = Boolean.parseBoolean(extractRaw(value, "premium"));

            return UserProfileResponse.builder()
                    .id(id)
                    .email(email)
                    .fullName(fullName)
                    .avatarUrl(avatarUrl)
                    .phoneNumber(phoneNumber)
                    .school(school)
                    .subject(subject)
                    .role(hasText(roleValue) ? Role.valueOf(roleValue) : Role.USER)
                    .premium(premium)
                    .build();
        } catch (Exception exception) {
            return null;
        }
    }

    private String quoteNullable(String value) {
        if (value == null) {
            return "null";
        }

        return "\"" + escape(value) + "\"";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String extractRaw(String json, String key) {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing key: " + key);
        }
        start += marker.length();

        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }

        return json.substring(start, end).trim();
    }

    private String extractNullableString(String json, String key) {
        String raw = extractRaw(json, key);
        if ("null".equals(raw)) {
            return null;
        }
        if (!raw.startsWith("\"") || !raw.endsWith("\"")) {
            throw new IllegalArgumentException("Invalid string value for key: " + key);
        }

        return raw.substring(1, raw.length() - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
