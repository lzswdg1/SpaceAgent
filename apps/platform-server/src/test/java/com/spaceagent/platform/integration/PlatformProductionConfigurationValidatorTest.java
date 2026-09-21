package com.spaceagent.platform.integration;

import com.spaceagent.platform.inference.infrastructure.InferenceProperties;
import com.spaceagent.platform.integration.infrastructure.PlatformProductionConfigurationValidator;
import com.spaceagent.platform.integration.infrastructure.PlatformSecurityProperties;
import com.spaceagent.platform.integration.infrastructure.PlatformReleaseProperties;
import com.spaceagent.platform.integration.infrastructure.SystemAdminSecurityProperties;
import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformProductionConfigurationValidatorTest {

    @Test
    void rejectsKnownDevelopmentSecretsForPostgresRuntime() {
        PlatformSecurityProperties security = secureSecurity();
        security.setJwtSecret("local-dev-jwt-secret-should-be-overridden-12345");

        assertThrows(IllegalStateException.class,
                () -> validator(security, secureInference(), secureEnvironment()).validate());
    }

    @Test
    void rejectsNoopAndDeterministicProductionModes() {
        MockEnvironment noop = secureEnvironment()
                .withProperty("platform.inference.execution-mode", "noop");
        assertThrows(IllegalStateException.class,
                () -> validator(secureSecurity(), secureInference(), noop).validate());

        MockEnvironment deterministic = secureEnvironment()
                .withProperty("platform.knowledge.embedding-mode", "deterministic");
        assertThrows(IllegalStateException.class,
                () -> validator(secureSecurity(), secureInference(), deterministic).validate());
    }

    @Test
    void providerReasoningRequiresHttpTypeScriptOrchestrator() {
        MockEnvironment missingOrchestrator = secureEnvironment()
                .withProperty("platform.multi-agent-orchestrator.reasoning-mode", "provider")
                .withProperty("platform.multi-agent-orchestrator.mode", "none");
        assertThrows(IllegalStateException.class,
                () -> validator(
                        secureSecurity(), secureInference(), missingOrchestrator).validate());

        MockEnvironment configured = secureEnvironment()
                .withProperty("platform.multi-agent-orchestrator.reasoning-mode", "provider")
                .withProperty("platform.multi-agent-orchestrator.mode", "http");
        assertDoesNotThrow(() -> validator(
                secureSecurity(), secureInference(), configured).validate());
    }

    @Test
    void automaticChatPlanningRequiresHttpTypeScriptOrchestrator() {
        MockEnvironment missing = secureEnvironment()
                .withProperty("platform.chat.automatic-planning-enabled", "true")
                .withProperty("platform.multi-agent-orchestrator.mode", "none");
        assertThrows(IllegalStateException.class,
                () -> validator(secureSecurity(), secureInference(), missing).validate());

        MockEnvironment configured = secureEnvironment()
                .withProperty("platform.chat.automatic-planning-enabled", "true")
                .withProperty("platform.multi-agent-orchestrator.mode", "http");
        assertDoesNotThrow(() -> validator(
                secureSecurity(), secureInference(), configured).validate());
    }

    @Test
    void httpSandboxRequiresPrivateAuthenticatedWorkerConfiguration() {
        MockEnvironment missingToken = secureEnvironment()
                .withProperty("platform.sandbox.mode", "http")
                .withProperty("platform.sandbox.base-url", "http://sandbox-worker:9200");
        assertThrows(IllegalStateException.class, () -> validator(
                secureSecurity(), secureInference(), missingToken).validate());

        MockEnvironment configured = missingToken
                .withProperty("platform.sandbox.internal-token",
                        "sandbox-worker-token-0123456789-abcdef");
        assertDoesNotThrow(() -> validator(
                secureSecurity(), secureInference(), configured).validate());

        MockEnvironment credentialUrl = configured.withProperty(
                "platform.sandbox.base-url", "http://user:secret@sandbox-worker:9200");
        assertThrows(IllegalStateException.class, () -> validator(
                secureSecurity(), secureInference(), credentialUrl).validate());
    }

    @Test
    void acceptsStrongPostgresConfigurationAndExplicitLocalOverride() {
        assertDoesNotThrow(() -> validator(
                secureSecurity(), secureInference(), secureEnvironment()).validate());

        PlatformSecurityProperties local = new PlatformSecurityProperties();
        local.setAllowInsecureLocal(true);
        assertDoesNotThrow(() -> validator(
                local, new InferenceProperties(), new MockEnvironment()
                        .withProperty("platform.persistence", "postgres")).validate());
    }

    @Test
    void trustedBetaRequiresSafeAudienceVersionAdaptersAndPersistentWorkspace() {
        PlatformReleaseProperties release = new PlatformReleaseProperties();
        release.setMode(PlatformReleaseProperties.Mode.TRUSTED_BETA);
        release.setVersion("1.0.0-rc1");
        MockEnvironment environment = secureEnvironment()
                .withProperty("platform.knowledge.embedding-api-key",
                        "release-embedding-key-0123456789")
                .withProperty("platform.workspace.managed-root", "/var/lib/spaceagent/workspaces")
                .withProperty("server.forward-headers-strategy", "framework")
                .withProperty("management.endpoint.health.probes.enabled", "true");
        assertDoesNotThrow(() -> new PlatformProductionConfigurationValidator(
                secureSecurity(), secureInference(), environment,
                release, secureMcp(), secureSystemAdmin()).validate());

        MockEnvironment completedPlanning = environment
                .withProperty("platform.chat.automatic-planning-enabled", "true")
                .withProperty("platform.multi-agent-orchestrator.mode", "http");
        assertDoesNotThrow(
                () -> new PlatformProductionConfigurationValidator(
                        secureSecurity(), secureInference(), completedPlanning,
                        release, secureMcp(), secureSystemAdmin()).validate());

        MockEnvironment temporary = environment.withProperty(
                "platform.workspace.managed-root", System.getProperty("java.io.tmpdir") + "/workspace");
        assertThrows(IllegalStateException.class,
                () -> new PlatformProductionConfigurationValidator(
                        secureSecurity(), secureInference(), temporary,
                        release, secureMcp(), secureSystemAdmin()).validate());

        PlatformReleaseProperties unsafeAudience = new PlatformReleaseProperties();
        unsafeAudience.setPublicUntrustedCodeEnabled(true);
        assertThrows(IllegalStateException.class,
                () -> new PlatformProductionConfigurationValidator(
                        secureSecurity(), secureInference(), new MockEnvironment(),
                        unsafeAudience, secureMcp(), secureSystemAdmin()).validate());
    }

    @Test void migratedKnowledgeDoesNotRequireTheRetiredGlobalEmbeddingKey(){
        var release=new PlatformReleaseProperties();release.setMode(PlatformReleaseProperties.Mode.TRUSTED_BETA);release.setVersion("1.0.0-rag");
        var environment=secureEnvironment().withProperty("platform.knowledge.legacy-mode","disabled")
            .withProperty("platform.workspace.managed-root","/var/lib/spaceagent/workspaces")
            .withProperty("server.forward-headers-strategy","framework").withProperty("management.endpoint.health.probes.enabled","true");
        assertDoesNotThrow(()->new PlatformProductionConfigurationValidator(secureSecurity(),secureInference(),environment,release,secureMcp(),secureSystemAdmin()).validate());
    }
    private static PlatformProductionConfigurationValidator validator(
            PlatformSecurityProperties security,
            InferenceProperties inference,
            MockEnvironment environment) {
        return new PlatformProductionConfigurationValidator(
                security, inference, environment, developmentRelease(), secureMcp(),
                secureSystemAdmin());
    }

    private static PlatformSecurityProperties secureSecurity() {
        PlatformSecurityProperties properties = new PlatformSecurityProperties();
        properties.setJwtSecret("m9-secure-jwt-secret-0123456789-abcdef");
        properties.setInternalToken("m9-secure-internal-token-0123456789-abcdef");
        return properties;
    }

    private static InferenceProperties secureInference() {
        InferenceProperties properties = new InferenceProperties();
        properties.setModelProviderEncryptionKey("m9-secure-provider-key-0123456789-abcdef");
        return properties;
    }

    private static MockEnvironment secureEnvironment() {
        return new MockEnvironment()
                .withProperty("platform.persistence", "postgres")
                .withProperty("spring.datasource.password", "m9-secure-database-password")
                .withProperty("platform.identity.activity-hash-key",
                        "identity-activity-test-key-0123456789-abcdef")
                .withProperty("platform.inference.execution-mode", "http")
                .withProperty("platform.knowledge.embedding-mode", "http");
    }

    private static PlatformReleaseProperties developmentRelease() {
        return new PlatformReleaseProperties();
    }

    private static McpToolingProperties secureMcp() {
        McpToolingProperties properties = new McpToolingProperties();
        properties.setEncryptionKey("m30-secure-mcp-key-0123456789-abcdef");
        return properties;
    }

    private static SystemAdminSecurityProperties secureSystemAdmin() {
        SystemAdminSecurityProperties properties = new SystemAdminSecurityProperties();
        properties.setJwtSecret("system-admin-test-key-0123456789-abcdef");
        return properties;
    }
}
