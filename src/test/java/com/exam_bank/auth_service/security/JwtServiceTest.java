package com.exam_bank.auth_service.security;

import com.exam_bank.auth_service.config.properties.AuthJwtProperties;
import com.exam_bank.auth_service.config.properties.AuthRefreshTokenProperties;
import com.exam_bank.auth_service.entity.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private AuthJwtProperties authJwtProperties;

    @Mock
    private AuthRefreshTokenProperties authRefreshTokenProperties;

    @InjectMocks
    private JwtService jwtService;

    @Test
    void generateToken_shouldIncludeUserIdRoleAndSubjectClaims() {
        when(authJwtProperties.getIssuer()).thenReturn("exam-bank-auth");
        when(authJwtProperties.getExpirationSeconds()).thenReturn(3600L);
        when(jwtEncoder.encode(org.mockito.ArgumentMatchers.any(JwtEncoderParameters.class)))
                .thenReturn(Jwt.withTokenValue("encoded-token")
                        .header("alg", "HS256")
                        .subject("john@example.com")
                        .claim("userId", 7L)
                        .claim("role", "USER")
                        .build());

        String token = jwtService.generateToken(7L, "john@example.com", Role.USER);

        assertThat(token).isEqualTo("encoded-token");
        ArgumentCaptor<JwtEncoderParameters> parametersCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(parametersCaptor.capture());

        JwtClaimsSet claims = parametersCaptor.getValue().getClaims();
        assertThat(claims.getSubject()).isEqualTo("john@example.com");
        assertThat(claims.getClaims()).containsEntry("userId", 7L);
        assertThat(claims.getClaims()).containsEntry("role", "USER");
        assertThat(claims.getClaims()).containsEntry("iss", "exam-bank-auth");
    }
}
