package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.AuthJwtProperties;
import com.exam_bank.auth_service.config.properties.AuthRefreshTokenProperties;
import com.exam_bank.auth_service.entity.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final AuthJwtProperties authJwtProperties;
    private final AuthRefreshTokenProperties authRefreshTokenProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken(Long userId, String subject, Role role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(authJwtProperties.getIssuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(authJwtProperties.getExpirationSeconds()))
                .claim("userId", userId)
                .claim("role", role.name())
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public long getExpirationSeconds() {
        return authJwtProperties.getExpirationSeconds();
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public long getRefreshTokenExpirationSeconds() {
        return authRefreshTokenProperties.getExpirationSeconds();
    }

    public Instant extractExpiration(String token) {
        Jwt jwt = jwtDecoder.decode(token);
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) {
            throw new IllegalArgumentException("JWT expiration is missing");
        }
        return expiresAt;
    }

    public String extractSubject(String token) {
        Jwt jwt = jwtDecoder.decode(token);
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT subject is missing");
        }
        return subject;
    }
}
