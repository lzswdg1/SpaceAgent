package com.spaceagent.admin.config;

import com.spaceagent.admin.platformclient.AdminPlatformClientProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;

@Component
public class AdminProductionConfigurationValidator {

    private static final List<String> UNSAFE_MARKERS = List.of(
            "local-dev", "change-in-production", "change-me", "replace-with",
            "placeholder", "fixture", "example", "invalid", "spaceagent123");

    private final AdminReleaseProperties release;
    private final AdminSecurityProperties security;
    private final AdminBootstrapProperties bootstrap;
    private final AdminPlatformClientProperties platformClient;
    private final Environment environment;

    public AdminProductionConfigurationValidator(
            AdminReleaseProperties release,
            AdminSecurityProperties security,
            AdminBootstrapProperties bootstrap,
            AdminPlatformClientProperties platformClient,
            Environment environment) {
        this.release = release;
        this.security = security;
        this.bootstrap = bootstrap;
        this.platformClient = platformClient;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (!release.requiresProductionSecurity()) {
            return;
        }

        requireSecret("admin.security.jwt-secret", security.getJwtSecret(), 32);
        requireSecret("admin.platform-client.jwt-secret", platformClient.getJwtSecret(), 32);
        requireSecret("spring.datasource.password",
                environment.getProperty("spring.datasource.password"), 16);
        requireNonBlank("admin.bootstrap.login-name", bootstrap.getLoginName());
        requireAdministratorPassword(bootstrap.getPassword());
        requirePrivatePlatformUrl(platformClient.getBaseUrl());

        if (!security.isCookieSecure()) {
            throw new IllegalStateException(
                    "Administrator release mode requires admin.security.cookie-secure=true");
        }
        if (platformClient.isAllowInsecureLocal()) {
            throw new IllegalStateException(
                    "Administrator release mode forbids insecure local platform transport");
        }
    }

    private static void requireSecret(String property, String value, int minimumLength) {
        String normalized = normalized(value);
        if (normalized.length() < minimumLength || containsUnsafeMarker(normalized)) {
            throw new IllegalStateException(property
                    + " must be an explicit non-development secret of at least "
                    + minimumLength + " characters");
        }
    }

    private static void requireNonBlank(String property, String value) {
        String normalized = normalized(value);
        if (normalized.isBlank() || containsUnsafeMarker(normalized)) {
            throw new IllegalStateException(property
                    + " must be explicitly configured without a development placeholder");
        }
    }

    private static void requireAdministratorPassword(String value) {
        String password = value == null ? "" : value;
        if (password.length() < 14 || password.length() > 512
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*[a-z].*")
                || !password.matches(".*\\d.*")
                || password.matches("[A-Za-z0-9]*")
                || containsUnsafeMarker(password)) {
            throw new IllegalStateException(
                    "admin.bootstrap.password must satisfy the administrator release password policy");
        }
    }

    private static void requirePrivatePlatformUrl(String value) {
        try {
            URI uri = URI.create(normalized(value));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || containsUnsafeMarker(uri.getHost())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "admin.platform-client.base-url must be an explicit private HTTPS service root");
        }
    }

    private static boolean containsUnsafeMarker(String value) {
        String lower = normalized(value).toLowerCase(Locale.ROOT);
        return UNSAFE_MARKERS.stream().anyMatch(lower::contains);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
