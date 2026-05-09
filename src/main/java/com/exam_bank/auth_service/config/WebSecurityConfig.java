package com.exam_bank.auth_service.config;

import java.util.Collection;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import static org.springframework.util.StringUtils.hasText;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.exam_bank.auth_service.config.properties.AuthJwtProperties;
import com.exam_bank.auth_service.config.properties.CorsProperties;
import com.exam_bank.auth_service.security.JwtBlacklistFilter;
import com.exam_bank.auth_service.security.OAuth2AuthenticationSuccessHandler;
import com.exam_bank.auth_service.security.OAuth2RedirectUriCaptureFilter;
import com.exam_bank.auth_service.service.AppUserDetailsService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;

@Configuration
@EnableMethodSecurity
public class WebSecurityConfig {

    private final Environment environment;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final AppUserDetailsService appUserDetailsService;
    private final AuthJwtProperties authJwtProperties;
    private final JwtBlacklistFilter jwtBlacklistFilter;
    private final OAuth2RedirectUriCaptureFilter oAuth2RedirectUriCaptureFilter;
    private final CorsProperties corsProperties;

    public WebSecurityConfig(Environment environment,
            @Lazy OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler,
            AppUserDetailsService appUserDetailsService,
            AuthJwtProperties authJwtProperties,
            JwtBlacklistFilter jwtBlacklistFilter,
            OAuth2RedirectUriCaptureFilter oAuth2RedirectUriCaptureFilter,
            CorsProperties corsProperties) {
        this.environment = environment;
        this.oAuth2AuthenticationSuccessHandler = oAuth2AuthenticationSuccessHandler;
        this.appUserDetailsService = appUserDetailsService;
        this.authJwtProperties = authJwtProperties;
        this.jwtBlacklistFilter = jwtBlacklistFilter;
        this.oAuth2RedirectUriCaptureFilter = oAuth2RedirectUriCaptureFilter;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/register",
                                "/register/resend-verification",
                                "/register/verify-email",
                                "/login",
                                "/refresh",
                                "/forgot-password",
                                "/forgot-password/resend",
                                "/forgot-password/verify-otp",
                                "/reset-password",
                                "/push-subscription/vapid-public-key",
                                "/push-subscription/user/**",
                                "/push-subscription/role/**",
                                "/internal/users/**",
                                "/users/*/avatar",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/oauth2/**",
                                "/sse/**",
                                "/error")
                        .permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/system-admin/**").hasRole("SYSTEM_ADMIN")
                        .requestMatchers("/audit/**").hasRole("AUDIT")
                        .anyRequest().authenticated())
                .authenticationProvider(daoAuthenticationProvider())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterBefore(oAuth2RedirectUriCaptureFilter, OAuth2AuthorizationRequestRedirectFilter.class)
                .addFilterBefore(jwtBlacklistFilter, BearerTokenAuthenticationFilter.class);

        if (isGoogleOauthConfigured()) {
            http.oauth2Login(oauth2 -> oauth2.successHandler(oAuth2AuthenticationSuccessHandler));
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(corsProperties.getAllowedOrigins().split(","))
                .stream()
                .map(String::trim)
                .toList());
        configuration.setAllowedMethods(corsProperties.getAllowedMethods());
        configuration.setAllowedHeaders(corsProperties.getAllowedHeaders());
        configuration.setExposedHeaders(corsProperties.getExposedHeaders());
        configuration.setAllowCredentials(corsProperties.isAllowCredentials());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private boolean isGoogleOauthConfigured() {
        return hasText(environment.getProperty("spring.security.oauth2.client.registration.google.client-id"))
                && hasText(environment.getProperty("spring.security.oauth2.client.registration.google.client-secret"));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(appUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        SecretKey secretKey = getJwtSecretKey();
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKey secretKey = getJwtSecretKey();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(authJwtProperties.getIssuer());
        OAuth2TokenValidator<Jwt> hasRoleClaim = new JwtClaimValidator<>("role",
                role -> role instanceof String value && hasText(value));
        OAuth2TokenValidator<Jwt> hasUserIdClaim = new JwtClaimValidator<>("userId", claim -> {
            if (claim instanceof Number number) {
                return number.longValue() > 0;
            }
            if (claim instanceof String value) {
                try {
                    return Long.parseLong(value.trim()) > 0;
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            return false;
        });
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, hasRoleClaim, hasUserIdClaim));
        return decoder;
    }

    private SecretKey getJwtSecretKey() {
        String secretBase64 = authJwtProperties.getSecret();
        if (!hasText(secretBase64)) {
            throw new IllegalStateException("auth.jwt.secret must not be empty");
        }
        byte[] keyBytes = Base64.from(secretBase64).decode();
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public Converter<Jwt, JwtAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            String role = jwt.getClaimAsString("role");
            Collection<SimpleGrantedAuthority> authorities = hasText(role)
                    ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    : List.of();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }
}
