package com.spaceagent.admin.config;

import com.spaceagent.admin.identity.domain.AdminIdentityRepository;
import com.spaceagent.admin.security.AdminTokenService;
import com.spaceagent.admin.security.AdminSessionAuthorizationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties({AdminSecurityProperties.class, AdminBootstrapProperties.class})
public class AdminSecurityConfiguration {

    @Bean
    Clock adminClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder adminPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AdminTokenService adminTokenService(AdminSecurityProperties properties, Clock adminClock) {
        return new AdminTokenService(properties, adminClock);
    }

    @Bean
    AdminSessionAuthorizationFilter adminSessionAuthorizationFilter(
            AdminIdentityRepository repository,
            Clock adminClock) {
        return new AdminSessionAuthorizationFilter(repository, adminClock);
    }

    @Bean
    JwtDecoder adminJwtDecoder(AdminSecurityProperties properties) {
        requireSecureProperties(properties);
        SecretKey key = new SecretKeySpec(
                properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.getAudience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                        "invalid_token", "Administrator token audience is invalid", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
        return decoder;
    }

    @Bean
    SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder adminJwtDecoder,
            AdminSessionAuthorizationFilter adminSessionAuthorizationFilter)
            throws Exception {
        Converter<Jwt, AbstractAuthenticationToken> converter = jwt -> {
            List<SimpleGrantedAuthority> authorities = jwt.getClaimAsStringList("roles").stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/admin/v1/auth/login",
                                "/admin/v1/auth/mfa/verify", "/admin/v1/auth/mfa/recovery",
                                "/admin/v1/auth/refresh",
                                "/admin/v1/auth/logout").permitAll()
                        .anyRequest().hasRole("PLATFORM_SUPER_ADMIN"))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.decoder(adminJwtDecoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, error) ->
                                writeError(response, 401, "ADMIN_UNAUTHORIZED", "Administrator authentication is required")))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, error) ->
                        writeError(response, 403, "ADMIN_FORBIDDEN", "Administrator access is denied")))
                .addFilterAfter(adminSessionAuthorizationFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean("adminSecurity")
    HealthIndicator adminSecurityHealth(AdminSecurityProperties properties, AdminIdentityRepository repository) {
        return () -> {
            try {
                requireSecureProperties(properties);
                if (!repository.hasAnyPrincipal()) {
                    return Health.down().withDetail("status", "BOOTSTRAP_REQUIRED").build();
                }
                return Health.up().withDetail("status", "READY").build();
            } catch (RuntimeException error) {
                return Health.down().withDetail("status", "INVALID_SECURITY_CONFIGURATION").build();
            }
        };
    }

    private static void requireSecureProperties(AdminSecurityProperties properties) {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().length() < 32) {
            throw new IllegalStateException("admin.security.jwt-secret must contain at least 32 characters");
        }
        if (properties.getAccessTokenMinutes() < 1 || properties.getRefreshTokenDays() < 1
                || properties.getMaxLoginAttempts() < 1) {
            throw new IllegalStateException("Administrator security durations and limits must be positive");
        }
    }

    private static void writeError(
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            String code,
            String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}");
    }
}
