package com.spaceagent.platform.integration.infrastructure;

import com.spaceagent.shared.auth.InternalServiceAuthenticationFilter;
import com.spaceagent.shared.auth.JwtAuthenticationFilter;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.api.IdentitySessionApplicationApi;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.env.Environment;
import com.spaceagent.platform.integration.infrastructure.http.PlatformAutomationWebhookHttpController;

/**
 * Stateless HTTP security for the platform-server public edge.
 *
 * <p>Bearer JWTs are validated with the shared filter. Internal-token mechanics are
 * retained only for transitional service adapters, not as a general public boundary.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({PlatformSecurityProperties.class, SystemAdminSecurityProperties.class})
public class PlatformHttpSecurityConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="platform.runtime.role",havingValue="knowledge-worker")
    public org.springframework.boot.web.servlet.FilterRegistrationBean<PlatformIdentityAuthorizationFilter> knowledgeWorkerIdentityFilterRegistration(PlatformIdentityAuthorizationFilter filter){
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }

    @Bean
    public JwtAuthenticationFilter platformJwtAuthenticationFilter(PlatformSecurityProperties properties) {
        return new JwtAuthenticationFilter(properties.getJwtSecret());
    }

    @Bean
    public InternalServiceAuthenticationFilter platformInternalServiceAuthenticationFilter(
            PlatformSecurityProperties properties) {
        return new InternalServiceAuthenticationFilter(properties.getInternalToken());
    }

    @Bean
    public SystemAdminInternalAuthenticationFilter systemAdminInternalAuthenticationFilter(
            SystemAdminSecurityProperties properties) {
        return new SystemAdminInternalAuthenticationFilter(properties);
    }

    @Bean
    public PlatformTokenIssuer platformTokenIssuer(
            PlatformSecurityProperties properties,
            IdentitySessionApplicationApi sessionApi) {
        return new PlatformTokenIssuer(properties, sessionApi);
    }

    @Bean
    public PlatformIdentityAuthorizationFilter platformIdentityAuthorizationFilter(
            PlatformTokenIssuer tokenIssuer,
            IdentityApplicationApi identityApi,
            IdentityActivityApplicationApi activityApi) {
        return new PlatformIdentityAuthorizationFilter(tokenIssuer, identityApi, activityApi);
    }

    @Bean
    public PasswordEncoder platformPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public PlatformAutomationWebhookHttpController.SigningKeyResolver automationWebhookSigningKeyResolver(
            Environment environment) {
        return keyReference -> {
            if (keyReference == null || !keyReference.startsWith("webhook-key:")) return Optional.empty();
            String name = keyReference.substring("webhook-key:".length());
            String value = environment.getProperty("platform.automation.webhook-signing-keys." + name);
            if (value == null || value.length() < 32 || value.length() > 512) return Optional.empty();
            return Optional.of(value.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Bean
    public SecurityFilterChain platformSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            InternalServiceAuthenticationFilter internalServiceAuthenticationFilter,
            SystemAdminInternalAuthenticationFilter systemAdminInternalAuthenticationFilter,
            PlatformIdentityAuthorizationFilter identityAuthorizationFilter,Environment environment) throws Exception {
        if("knowledge-worker".equals(environment.getProperty("platform.runtime.role","api"))) {
            return http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/actuator/health","/actuator/health/**","/actuator/prometheus").permitAll().anyRequest().denyAll())
                .exceptionHandling(e->e.authenticationEntryPoint((request,response,error)->writeSecurityError(response,403,"WORKER_API_DISABLED","Worker has no public business API")))
                .build();
        }
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/error", "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/public/organization-invitations/preview",
                                "/api/v1/public/automation/webhooks/*/*").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/activate",
                                "/api/v1/auth/password-reset",
                                "/api/v1/auth/refresh",
                                "/api/v1/web/auth/register",
                                "/api/v1/web/auth/login",
                                "/api/v1/web/auth/refresh",
                                "/auth/register",
                                "/auth/login",
                                "/auth/refresh").permitAll()
                        .requestMatchers("/internal/system-admin/v1/**")
                                .hasRole("SYSTEM_ADMIN_INTERNAL")
                        .requestMatchers("/internal/**", "/api/v1/internal/**").hasRole("INTERNAL")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeSecurityError(response, 401, "UNAUTHORIZED", "Authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeSecurityError(response, 403, "FORBIDDEN", "Access is denied")))
                .addFilterBefore(internalServiceAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(systemAdminInternalAuthenticationFilter,
                        InternalServiceAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(identityAuthorizationFilter, JwtAuthenticationFilter.class)
                .build();
    }

    private static void writeSecurityError(
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
