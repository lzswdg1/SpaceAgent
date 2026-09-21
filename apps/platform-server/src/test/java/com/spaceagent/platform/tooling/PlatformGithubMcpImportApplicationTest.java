package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.tooling.api.*;
import com.spaceagent.platform.tooling.application.*;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.platform.tooling.infrastructure.memory.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformGithubMcpImportApplicationTest {
    private String tenant;
    private String user;
    private String connection;
    private GithubMcpImportApplicationService imports;
    private FakeTools tools;

    @BeforeEach
    void setUp() {
        var ids = new UuidGenerator();
        var identityRepository = new InMemoryIdentityRepository();
        var identity = new IdentityApplicationService(
                identityRepository, ids, () -> Instant.parse("2026-08-23T18:00:00Z"));
        tenant = identity.createTenant(new CreateTenantCommand("Import", "mcp-import")).id();
        user = identity.createUser(new CreateUserCommand(
                tenant, "mcp-import@example.com", "Import")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var marketplace = new InMemoryMcpMarketplaceRepository();
        var cipher = new Cipher();
        var marketplaceApi = new McpMarketplaceApplicationService(
                marketplace, identity, cipher, new ObjectMapper(), ids,
                () -> Instant.parse("2026-08-23T18:00:00Z"));
        var entry = marketplace.findEntryBySlug("github").orElseThrow();
        var installation = marketplaceApi.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, entry.id(), McpInstallationScope.USER, null));
        connection = marketplaceApi.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), "https://mcp.github.example/mcp",
                McpAuthType.BEARER, Map.of("token", "secret"))).id();
        McpConnection configured = marketplace.findConnection(connection).orElseThrow();
        marketplace.saveConnection(new McpConnection(
                configured.id(), configured.installationId(), configured.tenantId(),
                configured.managedBy(), configured.endpointUrl(), configured.encryptedAuthJson(),
                configured.authType(), com.spaceagent.platform.tooling.domain.McpConnectionState.ACTIVE,
                configured.externalAccountId(), configured.externalAccountName(),
                configured.revision() + 1, configured.createdAt(),
                Instant.parse("2026-08-23T18:00:00Z"), null));
        tools = new FakeTools();
        var objectMapper = new ObjectMapper();
        imports = new GithubMcpImportApplicationService(
                marketplace, new InMemoryMcpInvocationLedgerRepository(), tools,
                new GithubOfficialMcpAdapter(tools, new UnsupportedGateway(), objectMapper),
                objectMapper, ids);
    }

    @Test
    void replaysSameImportAndRejectsIdempotencyConflict() {
        var command = command("repo-42", null, "import-one");
        var first = imports.resolve(command);
        var replay = imports.resolve(command);
        assertThat(replay).isEqualTo(first);
        assertThat(tools.calls).hasValue(1);

        assertThatThrownBy(() -> imports.resolve(command("repo-43", null, "import-one")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_IMPORT_IDEMPOTENCY_CONFLICT"));
        assertThat(tools.calls).hasValue(1);
    }

    @Test
    void unknownRemoteOutcomeIsNotBlindlyRetried() {
        tools.fail = true;
        var command = command("repo-42", null, "import-unknown");
        assertThatThrownBy(() -> imports.resolve(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_IMPORT_OUTCOME_UNKNOWN"));
        tools.fail = false;
        assertThatThrownBy(() -> imports.resolve(command))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("MCP_IMPORT_OUTCOME_UNKNOWN"));
        assertThat(tools.calls).hasValue(1);
    }

    private GithubMcpImportApplicationApi.ImportCommand command(
            String repositoryId, String url, String key) {
        return new GithubMcpImportApplicationApi.ImportCommand(
                tenant, user, "00000000-0000-4000-8000-000000000901", connection,
                repositoryId, url, key);
    }

    private static class FakeTools implements McpRemoteToolApplicationApi {
        private final AtomicInteger calls = new AtomicInteger();
        private boolean fail;

        @Override
        public List<ToolView> listTools(String tenantId, String userId, String connectionId) {
            return List.of();
        }

        @Override
        public ToolResult callTool(CallCommand command) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("timeout");
            return new ToolResult(false, Map.of(
                    "id", command.arguments().getOrDefault("repositoryId", "openai/repo"),
                    "owner", "openai",
                    "name", "repo",
                    "htmlUrl", "https://github.com/openai/repo",
                    "cloneUrl", "https://github.com/openai/repo.git",
                    "defaultBranch", "main",
                    "private", false,
                    "archived", false), "");
        }
    }

    private static class Cipher implements McpConnectionSecretCipher {
        public String encrypt(String value) { return "cipher:" + value; }
        public String decrypt(String value) { return value.substring(7); }
    }

    private static class UnsupportedGateway implements McpRemoteToolGateway {
        public List<RemoteTool> listTools(McpConnection connection, Map<String, String> auth) {
            throw new UnsupportedOperationException();
        }
        public RemoteResult callTool(
                McpConnection connection, Map<String, String> auth,
                String tool, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
    }
}
