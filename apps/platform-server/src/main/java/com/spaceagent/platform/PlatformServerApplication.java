package com.spaceagent.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot entry point for the SpaceAgent V2 modular-monolith control plane.
 *
 * <p>This application intentionally contains no business behavior yet. It establishes
 * the deployable shell and package boundaries used by the strangler migration.
 */
@SpringBootApplication(
        scanBasePackages = "com.spaceagent.platform",
        exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan(basePackages = "com.spaceagent.platform")
public class PlatformServerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PlatformServerApplication.class);
        application.setDefaultProperties(runtimeDefaults());
        application.run(args);
    }

    /**
     * Keeps runtime configuration in code instead of a shared {@code application.yml}.
     * This prevents the platform-server jar, when consumed by the legacy service
     * adapters during migration, from shadowing the service's own configuration file.
     */
    static Map<String, Object> runtimeDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.application.name", "platform-server");
        defaults.put("server.port", "${PLATFORM_SERVER_PORT:9000}");
        defaults.put("spring.datasource.url",
                "${SPRING_DATASOURCE_URL:jdbc:postgresql://127.0.0.1:5436/spaceagent_platform}");
        defaults.put("spring.datasource.username", "${SPRING_DATASOURCE_USERNAME:spaceagent}");
        defaults.put("spring.datasource.password", "${SPRING_DATASOURCE_PASSWORD:spaceagent123}");
        defaults.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        defaults.put("spring.flyway.enabled", "${SPRING_FLYWAY_ENABLED:true}");
        defaults.put("spring.flyway.locations",
                "classpath:db/platform-server,classpath:db/platform-runtime");
        defaults.put("management.health.redis.enabled", "false");
        defaults.put("management.endpoint.health.probes.enabled", "true");
        defaults.put("management.endpoint.health.show-details", "never");
        defaults.put("management.endpoint.health.group.liveness.include", "livenessState");
        defaults.put("management.endpoint.health.group.readiness.include",
                "readinessState,db,releaseReadiness");
        defaults.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        defaults.put("management.info.env.enabled", "true");
        defaults.put("management.prometheus.metrics.export.enabled", "true");
        defaults.put("management.metrics.distribution.percentiles-histogram.http.server.requests",
                "true");
        defaults.put("management.tracing.enabled",
                "${PLATFORM_OBSERVABILITY_TRACING_ENABLED:true}");
        defaults.put("management.tracing.sampling.probability",
                "${PLATFORM_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY:0.10}");
        defaults.put("management.otlp.tracing.export.enabled",
                "${PLATFORM_OBSERVABILITY_OTLP_ENABLED:false}");
        defaults.put("management.otlp.tracing.endpoint",
                "${PLATFORM_OBSERVABILITY_OTLP_ENDPOINT:http://127.0.0.1:4318/v1/traces}");
        defaults.put("management.otlp.tracing.connect-timeout",
                "${PLATFORM_OBSERVABILITY_OTLP_CONNECT_TIMEOUT:2s}");
        defaults.put("management.otlp.tracing.timeout",
                "${PLATFORM_OBSERVABILITY_OTLP_TIMEOUT:5s}");
        defaults.put("management.opentelemetry.resource-attributes.service.name",
                "platform-server");
        defaults.put("management.opentelemetry.resource-attributes.deployment.environment",
                "${PLATFORM_RELEASE_MODE:development}");
        defaults.put("platform.observability.operational-metrics.enabled",
                "${PLATFORM_OBSERVABILITY_OPERATIONAL_METRICS_ENABLED:true}");
        defaults.put("platform.observability.operational-metrics.window",
                "${PLATFORM_OBSERVABILITY_OPERATIONAL_METRICS_WINDOW:5m}");
        defaults.put("platform.observability.operational-metrics.refresh-delay-ms",
                "${PLATFORM_OBSERVABILITY_OPERATIONAL_METRICS_REFRESH_DELAY_MS:15000}");
        defaults.put("server.shutdown", "graceful");
        defaults.put("spring.lifecycle.timeout-per-shutdown-phase", "30s");
        defaults.put("server.forward-headers-strategy", "framework");
        defaults.put("platform.persistence", "${PLATFORM_PERSISTENCE:postgres}");
        defaults.put("platform.runtime.role", "${PLATFORM_RUNTIME_ROLE:api}");
        defaults.put("platform.inference.reranker.mode", "${PLATFORM_RERANKER_MODE:none}");
        defaults.put("platform.inference.reranker.uri", "${PLATFORM_RERANKER_URI:}");
        defaults.put("platform.inference.reranker.token", "${PLATFORM_RERANKER_TOKEN:}");
        defaults.put("platform.inference.reranker.model", "${PLATFORM_RERANKER_MODEL:}");
        defaults.put("platform.inference.reranker.revision", "${PLATFORM_RERANKER_REVISION:}");
        defaults.put("platform.inference.reranker.allow-private-http", "${PLATFORM_RERANKER_ALLOW_PRIVATE_HTTP:false}");
        defaults.put("platform.inference.model-provider-encryption-key",
                "${PLATFORM_INFERENCE_MODEL_PROVIDER_ENCRYPTION_KEY:local-dev-model-provider-encryption-key-change-me}");
        defaults.put("platform.inference.model-provider-previous-encryption-keys",
                "${PLATFORM_INFERENCE_MODEL_PROVIDER_PREVIOUS_ENCRYPTION_KEYS:}");
        defaults.put("platform.inference.allowed-provider-hosts",
                "${PLATFORM_INFERENCE_ALLOWED_PROVIDER_HOSTS:dashscope.aliyuncs.com,*.maas.aliyuncs.com,api.deepseek.com,api.openai.com}");
        defaults.put("platform.inference.allow-local-provider-hosts",
                "${PLATFORM_INFERENCE_ALLOW_LOCAL_PROVIDER_HOSTS:false}");
        defaults.put("platform.inference.execution-mode",
                "${PLATFORM_INFERENCE_EXECUTION_MODE:http}");
        defaults.put("platform.tooling.mcp.encryption-key",
                "${PLATFORM_TOOLING_MCP_ENCRYPTION_KEY:local-dev-mcp-connection-encryption-key-change-me}");
        defaults.put("platform.tooling.mcp.previous-encryption-keys",
                "${PLATFORM_TOOLING_MCP_PREVIOUS_ENCRYPTION_KEYS:}");
        defaults.put("platform.tooling.mcp.allowed-hosts",
                "${PLATFORM_TOOLING_MCP_ALLOWED_HOSTS:api.githubcopilot.com,github.com}");
        defaults.put("platform.tooling.mcp.registry.base-url",
                "${PLATFORM_MCP_REGISTRY_BASE_URL:https://registry.modelcontextprotocol.io}");
        defaults.put("platform.tooling.mcp.registry.worker-enabled",
                "${PLATFORM_MCP_REGISTRY_WORKER_ENABLED:true}");
        defaults.put("platform.tooling.mcp.registry.worker-id",
                "${PLATFORM_MCP_REGISTRY_WORKER_ID:}");
        defaults.put("platform.tooling.mcp.registry.poll-delay-ms",
                "${PLATFORM_MCP_REGISTRY_POLL_DELAY_MS:1000}");
        defaults.put("platform.tooling.web-search.mode",
                "${PLATFORM_TOOLING_WEB_SEARCH_MODE:none}");
        defaults.put("platform.tooling.web-search.base-url",
                "${PLATFORM_TOOLING_WEB_SEARCH_BASE_URL:}");
        defaults.put("platform.sandbox.mode", "${PLATFORM_SANDBOX_MODE:in-process}");
        defaults.put("platform.sandbox.base-url",
                "${PLATFORM_SANDBOX_BASE_URL:http://127.0.0.1:9200}");
        defaults.put("platform.sandbox.internal-token",
                "${PLATFORM_SANDBOX_INTERNAL_TOKEN:}");
        defaults.put("platform.sandbox.connect-timeout-seconds",
                "${PLATFORM_SANDBOX_CONNECT_TIMEOUT_SECONDS:5}");
        defaults.put("platform.sandbox.request-timeout-seconds",
                "${PLATFORM_SANDBOX_REQUEST_TIMEOUT_SECONDS:630}");
        defaults.put("platform.sandbox.max-response-bytes",
                "${PLATFORM_SANDBOX_MAX_RESPONSE_BYTES:1000000}");
        defaults.put("platform.tooling.mcp.github.client-id",
                "${PLATFORM_GITHUB_MCP_CLIENT_ID:}");
        defaults.put("platform.tooling.mcp.github.client-secret",
                "${PLATFORM_GITHUB_MCP_CLIENT_SECRET:}");
        defaults.put("platform.tooling.mcp.github.scopes",
                "${PLATFORM_GITHUB_MCP_SCOPES:repo,read:user,read:org}");
        defaults.put("platform.tooling.mcp.github.allowed-redirect-uris",
                "${PLATFORM_GITHUB_MCP_ALLOWED_REDIRECT_URIS:}");
        defaults.put("platform.workspace.managed-root",
                "${PLATFORM_WORKSPACE_MANAGED_ROOT:${java.io.tmpdir}/spaceagent-managed-workspaces}");
        defaults.put("platform.workspace.git-timeout-seconds",
                "${PLATFORM_WORKSPACE_GIT_TIMEOUT_SECONDS:120}");
        defaults.put("platform.project.intake.worker-enabled",
                "${PLATFORM_PROJECT_INTAKE_WORKER_ENABLED:true}");
        defaults.put("platform.project.intake.worker-id",
                "${PLATFORM_PROJECT_INTAKE_WORKER_ID:}");
        defaults.put("platform.project.intake.poll-delay-ms",
                "${PLATFORM_PROJECT_INTAKE_POLL_DELAY_MS:1000}");
        defaults.put("platform.project.intake.lease-seconds",
                "${PLATFORM_PROJECT_INTAKE_LEASE_SECONDS:900}");
        defaults.put("platform.project.intake.maximum-attempts",
                "${PLATFORM_PROJECT_INTAKE_MAXIMUM_ATTEMPTS:3}");
        defaults.put("platform.project.coding.worker-enabled",
                "${PLATFORM_PROJECT_CODING_WORKER_ENABLED:true}");
        defaults.put("platform.project.coding.worker-id",
                "${PLATFORM_PROJECT_CODING_WORKER_ID:}");
        defaults.put("platform.project.coding.poll-delay-ms",
                "${PLATFORM_PROJECT_CODING_POLL_DELAY_MS:1000}");
        defaults.put("platform.project.coding.lease-seconds",
                "${PLATFORM_PROJECT_CODING_LEASE_SECONDS:900}");
        defaults.put("platform.project.coding.maximum-attempts",
                "${PLATFORM_PROJECT_CODING_MAXIMUM_ATTEMPTS:3}");
        defaults.put("platform.project.coding.maximum-iterations",
                "${PLATFORM_PROJECT_CODING_MAXIMUM_ITERATIONS:20}");
        defaults.put("platform.project.coding.maximum-review-rounds",
                "${PLATFORM_PROJECT_CODING_MAXIMUM_REVIEW_ROUNDS:2}");
        defaults.put("platform.runtime.coordination.enabled",
                "${PLATFORM_RUNTIME_COORDINATION_ENABLED:true}");
        defaults.put("platform.runtime.coordination.worker-id",
                "${PLATFORM_RUNTIME_WORKER_ID:}");
        defaults.put("platform.runtime.coordination.lease-seconds",
                "${PLATFORM_RUNTIME_LEASE_SECONDS:30}");
        defaults.put("platform.runtime.coordination.poll-delay-ms",
                "${PLATFORM_RUNTIME_CONTINUATION_POLL_DELAY_MS:500}");
        defaults.put("platform.runtime.coordination.retry-delay-seconds",
                "${PLATFORM_RUNTIME_CONTINUATION_RETRY_DELAY_SECONDS:5}");
        defaults.put("platform.runtime.coordination.batch-size",
                "${PLATFORM_RUNTIME_CONTINUATION_BATCH_SIZE:8}");
        defaults.put("platform.multi-agent-orchestrator.reasoning-mode",
                "${PLATFORM_MULTI_AGENT_REASONING_MODE:deterministic}");
        defaults.put("platform.multi-agent-orchestrator.mode",
                "${PLATFORM_MULTI_AGENT_ORCHESTRATOR_MODE:none}");
        defaults.put("platform.multi-agent-orchestrator.base-url",
                "${PLATFORM_MULTI_AGENT_ORCHESTRATOR_BASE_URL:http://127.0.0.1:9300}");
        defaults.put("platform.multi-agent-orchestrator.internal-token",
                "${PLATFORM_MULTI_AGENT_ORCHESTRATOR_INTERNAL_TOKEN:${PLATFORM_INTERNAL_TOKEN:local-dev-internal-token-change-me}}");
        defaults.put("platform.knowledge.embedding-mode",
                "${PLATFORM_KNOWLEDGE_EMBEDDING_MODE:http}");
        defaults.put("platform.knowledge.embedding-base-url",
                "${PLATFORM_KNOWLEDGE_EMBEDDING_BASE_URL:https://api.openai.com/v1/}");
        defaults.put("platform.knowledge.embedding-api-key",
                "${PLATFORM_KNOWLEDGE_EMBEDDING_API_KEY:}");
        defaults.put("platform.knowledge.embedding-model",
                "${PLATFORM_KNOWLEDGE_EMBEDDING_MODEL:text-embedding-3-small}");
        defaults.put("platform.knowledge.vector-store.mode", "${PLATFORM_KNOWLEDGE_VECTOR_STORE_MODE:none}");
        defaults.put("platform.knowledge.legacy-mode", "${PLATFORM_KNOWLEDGE_LEGACY_MODE:compatibility}");
        defaults.put("platform.knowledge.index-content-root", "${PLATFORM_KNOWLEDGE_INDEX_CONTENT_ROOT:data/knowledge-index}");
        defaults.put("platform.knowledge.indexing.intake-enabled", "${PLATFORM_KNOWLEDGE_INDEX_INTAKE_ENABLED:false}");
        defaults.put("platform.knowledge.indexing.worker-enabled", "${PLATFORM_KNOWLEDGE_INDEX_WORKER_ENABLED:false}");
        defaults.put("platform.knowledge.limits.documents-per-base", "${PLATFORM_KNOWLEDGE_MAX_DOCUMENTS:10000}");
        defaults.put("platform.knowledge.limits.pending-per-tenant", "${PLATFORM_KNOWLEDGE_MAX_PENDING:64}");
        defaults.put("platform.knowledge.limits.source-bytes-per-tenant", "${PLATFORM_KNOWLEDGE_MAX_SOURCE_BYTES:1073741824}");
        defaults.put("platform.knowledge.parser.uri", "${PLATFORM_KNOWLEDGE_PARSER_URI:}");
        defaults.put("platform.knowledge.parser.allow-private-http", "${PLATFORM_KNOWLEDGE_PARSER_ALLOW_PRIVATE_HTTP:false}");
        defaults.put("platform.knowledge.milvus.uri", "${PLATFORM_KNOWLEDGE_MILVUS_URI:}");
        defaults.put("platform.knowledge.milvus.token", "${PLATFORM_KNOWLEDGE_MILVUS_TOKEN:}");
        defaults.put("platform.knowledge.milvus.database", "${PLATFORM_KNOWLEDGE_MILVUS_DATABASE:default}");
        defaults.put("platform.knowledge.milvus.collection-prefix", "${PLATFORM_KNOWLEDGE_MILVUS_COLLECTION_PREFIX:spaceagent}");
        defaults.put("platform.knowledge.milvus.allow-insecure-local", "${PLATFORM_KNOWLEDGE_MILVUS_ALLOW_INSECURE_LOCAL:false}");
        defaults.put("platform.security.jwt-secret",
                "${PLATFORM_JWT_SECRET:local-dev-jwt-secret-should-be-overridden-12345}");
        defaults.put("platform.security.internal-token",
                "${PLATFORM_INTERNAL_TOKEN:local-dev-internal-token-change-me}");
        defaults.put("platform.security.refresh-token-expiration-days",
                "${PLATFORM_REFRESH_TOKEN_EXPIRATION_DAYS:30}");
        defaults.put("platform.security.allow-insecure-local",
                "${PLATFORM_ALLOW_INSECURE_LOCAL:false}");
        defaults.put("platform.identity.activity-hash-key",
                "${PLATFORM_IDENTITY_ACTIVITY_HASH_KEY:local-dev-identity-activity-hash-key-change-me}");
        defaults.put("platform.system-admin.security.issuer",
                "${PLATFORM_SYSTEM_ADMIN_JWT_ISSUER:spaceagent-platform-admin}");
        defaults.put("platform.system-admin.security.audience",
                "${PLATFORM_SYSTEM_ADMIN_JWT_AUDIENCE:spaceagent-platform-admin-internal}");
        defaults.put("platform.system-admin.security.jwt-secret",
                "${PLATFORM_SYSTEM_ADMIN_JWT_SECRET:local-dev-system-admin-jwt-secret-change-me}");
        defaults.put("platform.system-admin.security.maximum-token-seconds",
                "${PLATFORM_SYSTEM_ADMIN_TOKEN_SECONDS:60}");
        defaults.put("platform.system-admin.security.allow-insecure-local",
                "${PLATFORM_SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL:false}");
        defaults.put("platform.identity.user-cleanup.deletion-enabled",
                "${PLATFORM_USER_DELETION_ENABLED:false}");
        defaults.put("platform.chat.automatic-planning-enabled",
                "${PLATFORM_CHAT_AUTOMATIC_PLANNING_ENABLED:false}");
        defaults.put("platform.release.mode", "${PLATFORM_RELEASE_MODE:development}");
        defaults.put("platform.release.version", "${SPACEAGENT_RELEASE_VERSION:dev}");
        defaults.put("platform.release.expected-schema-version",
                "${PLATFORM_EXPECTED_SCHEMA_VERSION:1098}");
        defaults.put("platform.release.trusted-code-only",
                "${PLATFORM_TRUSTED_CODE_ONLY:true}");
        defaults.put("platform.release.public-untrusted-code-enabled",
                "${PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED:false}");
        defaults.put("info.spaceagent.release.version", "${SPACEAGENT_RELEASE_VERSION:dev}");
        defaults.put("info.spaceagent.release.mode", "${PLATFORM_RELEASE_MODE:development}");
        defaults.put("info.spaceagent.release.schema", "${PLATFORM_EXPECTED_SCHEMA_VERSION:1098}");
        return defaults;
    }
}
