package com.spaceagent.platform.integration.infrastructure;

import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Fail-fast gate for the active PostgreSQL runtime. Test/in-memory profiles remain explicit.
 */
@Component
public class PlatformProductionConfigurationValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PlatformProductionConfigurationValidator.class);
    private static final List<String> UNSAFE_MARKERS = List.of(
            "local-dev", "change-in-production", "change-me", "replace-with", "spaceagent123");

    private final PlatformSecurityProperties security;
    private final InferenceProperties inference;
    private final Environment environment;
    private final PlatformReleaseProperties release;
    private final McpToolingProperties mcpTooling;
    private final SystemAdminSecurityProperties systemAdminSecurity;

    public PlatformProductionConfigurationValidator(
            PlatformSecurityProperties security,
            InferenceProperties inference,
            Environment environment) {
        this(security, inference, environment,
                new PlatformReleaseProperties(), new McpToolingProperties(),
                new SystemAdminSecurityProperties());
    }

    @Autowired
    public PlatformProductionConfigurationValidator(
            PlatformSecurityProperties security,
            InferenceProperties inference,
            Environment environment,
            PlatformReleaseProperties release,
            McpToolingProperties mcpTooling,
            SystemAdminSecurityProperties systemAdminSecurity) {
        this.security = security;
        this.inference = inference;
        this.environment = environment;
        this.release = release;
        this.mcpTooling = mcpTooling;
        this.systemAdminSecurity = systemAdminSecurity;
    }

    @PostConstruct
    public void validate() {
        if (release.isPublicUntrustedCodeEnabled()) {
            throw new IllegalStateException(
                    "Public untrusted-code execution is unavailable without a hardened sandbox");
        }
        boolean trustedBeta = release.getMode() == PlatformReleaseProperties.Mode.TRUSTED_BETA;
        if (!"postgres".equalsIgnoreCase(environment.getProperty("platform.persistence", "memory"))) {
            if (trustedBeta) {
                throw new IllegalStateException("TRUSTED_BETA requires PostgreSQL persistence");
            }
            return;
        }
        if (security.isAllowInsecureLocal()) {
            if (trustedBeta) {
                throw new IllegalStateException(
                        "TRUSTED_BETA forbids platform.security.allow-insecure-local");
            }
            LOGGER.warn("Insecure local platform configuration override is enabled; never use it in production");
            return;
        }

        requireSecret("platform.security.jwt-secret", security.getJwtSecret(), 32);
        requireSecret("platform.security.internal-token", security.getInternalToken(), 32);
        requireSecret("platform.inference.model-provider-encryption-key",
                inference.getModelProviderEncryptionKey(), 32);
        requireSecret("platform.tooling.mcp.encryption-key",
                mcpTooling.getEncryptionKey(), 32);
        requireSecret("platform.identity.activity-hash-key",
                environment.getProperty("platform.identity.activity-hash-key"), 32);
        requireSecret("platform.system-admin.security.jwt-secret",
                systemAdminSecurity.getJwtSecret(), 32);
        requireSecret("spring.datasource.password",
                environment.getProperty("spring.datasource.password"), 16);

        rejectMode("platform.inference.execution-mode", "noop");
        rejectMode("platform.knowledge.embedding-mode", "deterministic");
        String reasoningMode = environment.getProperty(
                "platform.multi-agent-orchestrator.reasoning-mode", "deterministic");
        String orchestratorMode = environment.getProperty(
                "platform.multi-agent-orchestrator.mode", "none");
        if (!List.of("deterministic", "provider").contains(
                reasoningMode.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "platform.multi-agent-orchestrator.reasoning-mode must be deterministic or provider");
        }
        if ("provider".equalsIgnoreCase(reasoningMode)
                && !"http".equalsIgnoreCase(orchestratorMode)) {
            throw new IllegalStateException(
                    "Provider-backed Multi-Agent reasoning requires the HTTP TypeScript orchestrator");
        }
        if (environment.getProperty(
                "platform.chat.automatic-planning-enabled", Boolean.class, false)
                && !"http".equalsIgnoreCase(orchestratorMode)) {
            throw new IllegalStateException(
                    "Automatic Chat planning requires the HTTP TypeScript orchestrator");
        }
        String sandboxMode = environment.getProperty(
                "platform.sandbox.mode", "in-process").toLowerCase(Locale.ROOT);
        if (!List.of("in-process", "http").contains(sandboxMode)) {
            throw new IllegalStateException(
                    "platform.sandbox.mode must be in-process or http");
        }
        if ("http".equals(sandboxMode)) {
            requireSecret("platform.sandbox.internal-token",
                    environment.getProperty("platform.sandbox.internal-token"), 32);
            requireSandboxUrl(environment.getProperty("platform.sandbox.base-url"));
        }
        if (trustedBeta) {
            if (systemAdminSecurity.isAllowInsecureLocal()) {
                throw new IllegalStateException(
                        "TRUSTED_BETA forbids insecure local System Admin transport");
            }
            validateTrustedBeta();
        }
    }

    private void validateTrustedBeta() {
        if (!release.isTrustedCodeOnly()) {
            throw new IllegalStateException("TRUSTED_BETA requires trusted-code-only=true");
        }
        String version = release.getVersion() == null ? "" : release.getVersion().trim();
        if (version.isBlank() || "dev".equalsIgnoreCase(version)
                || UNSAFE_MARKERS.stream().anyMatch(
                marker -> version.toLowerCase(Locale.ROOT).contains(marker))) {
            throw new IllegalStateException("TRUSTED_BETA requires an explicit release version");
        }
        requireMode("platform.inference.execution-mode", "http");
        if(!"disabled".equals(environment.getProperty("platform.knowledge.legacy-mode","compatibility"))){
            requireMode("platform.knowledge.embedding-mode", "http");
            requireSecret("platform.knowledge.embedding-api-key",
                    environment.getProperty("platform.knowledge.embedding-api-key"), 16);
        }
        requireMode("server.forward-headers-strategy", "framework");
        requireMode("management.endpoint.health.probes.enabled", "true");
        String workspace = environment.getProperty("platform.workspace.managed-root");
        requireNonBlank("platform.workspace.managed-root", workspace);
        java.nio.file.Path path;
        try {
            path = java.nio.file.Path.of(workspace).toAbsolutePath().normalize();
        } catch (Exception error) {
            throw new IllegalStateException("platform.workspace.managed-root must be an absolute path");
        }
        java.nio.file.Path temporary = java.nio.file.Path.of(
                environment.getProperty("java.io.tmpdir", System.getProperty("java.io.tmpdir")))
                .toAbsolutePath().normalize();
        if (!java.nio.file.Path.of(workspace).isAbsolute() || path.startsWith(temporary)) {
            throw new IllegalStateException(
                    "TRUSTED_BETA requires a persistent managed Workspace outside the temp directory");
        }
        if (release.getExpectedSchemaVersion() != 1098) {
            throw new IllegalStateException(
                    "TRUSTED_BETA expected schema version must match the release binary (1098)");
        }
    }

    private void rejectMode(String property, String forbidden) {
        if (forbidden.equalsIgnoreCase(environment.getProperty(property, ""))) {
            throw new IllegalStateException(property + "=" + forbidden
                    + " is forbidden for the PostgreSQL production runtime");
        }
    }

    private void requireMode(String property, String required) {
        if (!required.equalsIgnoreCase(environment.getProperty(property, ""))) {
            throw new IllegalStateException(property + " must be " + required + " for TRUSTED_BETA");
        }
    }

    private void requireSecret(String property, String value, int minimumLength) {
        String normalized = value == null ? "" : value.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.length() < minimumLength
                || UNSAFE_MARKERS.stream().anyMatch(lower::contains)) {
            throw new IllegalStateException(property
                    + " must be set to a non-development secret of at least "
                    + minimumLength + " characters");
        }
    }

    private static void requireNonBlank(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be configured");
        }
    }

    private static void requireSandboxUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value == null ? "" : value.trim());
            boolean scheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            boolean path = uri.getPath() == null || uri.getPath().isBlank()
                    || "/".equals(uri.getPath());
            if (!scheme || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !path) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "platform.sandbox.base-url must be an HTTP service root");
        }
    }
}
