package com.exam_bank.auth_service.service;

import com.exam_bank.auth_service.config.properties.ForgotPasswordRateLimitProperties;
import com.exam_bank.auth_service.exception.BruteForceBlockedException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class OtpRateLimitService {

    private static final String FORGOT_LIMIT_KEY_PREFIX = "rate_limit:forgot_password:forgot:";
    private static final String VERIFY_LIMIT_KEY_PREFIX = "rate_limit:forgot_password:verify:";
    private static final String RESEND_LIMIT_KEY_PREFIX = "rate_limit:forgot_password:resend:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ForgotPasswordRateLimitProperties properties;

    public void checkForgotAllowed(String normalizedEmail) {
        enforceLimit(
                FORGOT_LIMIT_KEY_PREFIX + normalizedEmail,
                properties.getForgotMaxAttempts(),
                properties.getForgotWindowSeconds(),
                "Too many forgot-password requests");
    }

    public void checkVerifyAllowed(String normalizedEmail) {
        enforceLimit(
                VERIFY_LIMIT_KEY_PREFIX + normalizedEmail,
                properties.getVerifyMaxAttempts(),
                properties.getVerifyWindowSeconds(),
                "Too many OTP verification attempts");
    }

    public void checkResendAllowed(String normalizedEmail) {
        enforceLimit(
                RESEND_LIMIT_KEY_PREFIX + normalizedEmail,
                properties.getResendMaxAttempts(),
                properties.getResendWindowSeconds(),
                "Too many OTP resend requests");
    }

    private void enforceLimit(String key, long maxAttempts, long windowSeconds, String errorPrefix) {
        Long attempts = stringRedisTemplate.opsForValue().increment(key);
        if (attempts == null) {
            return;
        }

        if (attempts == 1) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }

        if (attempts > maxAttempts) {
            Long retryAfterSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            long retryAfter = retryAfterSeconds != null && retryAfterSeconds > 0
                    ? retryAfterSeconds
                    : windowSeconds;
            throw new BruteForceBlockedException(errorPrefix + ". Try again in " + retryAfter + " seconds.");
        }
    }
}
