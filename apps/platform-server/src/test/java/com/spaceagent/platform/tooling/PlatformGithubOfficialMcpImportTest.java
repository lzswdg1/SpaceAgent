package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.AddTenantMembershipCommand;
import com.spaceagent.platform.identity.api.CreateTenantCommand;
import com.spaceagent.platform.identity.api.CreateUserCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.memory.InMemoryIdentityRepository;
import com.spaceagent.platform.tooling.api.GithubMcpImportApplicationApi;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.application.GithubMcpImportApplicationService;
import com.spaceagent.platform.tooling.application.GithubOfficialMcpAdapter;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpInvocationLedgerRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryMcpMarketplaceRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformGithubOfficialMcpImportTest {
    @Test
    void ledgeredImportUsesOfficialSearchToolAndReplays() {
        Instant now = Instant.parse("2026-08-24T05:00:00Z");
        var ids = new UuidGenerator();
        var identityRepository = new InMemoryIdentityRepository();
        var identity = new IdentityApplicationService(identityRepository, ids, () -> now);
        String tenant = identity.createTenant(new CreateTenantCommand("Import", "official")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "official-import@example.com", "Import")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(
                tenant, user, TenantRole.OWNER));
        var repository = new InMemoryMcpMarketplaceRepository();
        var cipher = new Cipher();
        var mapper = new ObjectMapper();
        var marketplace = new McpMarketplaceApplicationService(
                repository, identity, cipher, mapper, ids, () -> now);
        var installation = marketplace.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, repository.findEntryBySlug("github").orElseThrow().id(),
                McpInstallationScope.USER, null));
        String connection = marketplace.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), null, null, Map.of())).id();
        McpConnection pending = repository.findConnection(connection).orElseThrow();
        String storedAuth;
        try {
            storedAuth = mapper.writeValueAsString(Map.of(
                    "oauth_profile", GithubMcpProfiles.OFFICIAL_PROFILE,
                    "access_token", "official-import-token"));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        repository.saveConnection(new McpConnection(
                pending.id(), pending.installationId(), pending.tenantId(), pending.managedBy(),
                pending.endpointUrl(), cipher.encrypt(storedAuth), pending.authType(),
                com.spaceagent.platform.tooling.domain.McpConnectionState.ACTIVE,
                "42", "octocat", pending.revision() + 1, pending.createdAt(), now, null));
        var tools = new SearchTools();
        var official = new GithubOfficialMcpAdapter(
                tools, new UnsupportedRemoteGateway(), mapper);
        var imports = new GithubMcpImportApplicationService(
                repository, new InMemoryMcpInvocationLedgerRepository(),
                tools, official, mapper, ids);
        var command = new GithubMcpImportApplicationApi.ImportCommand(
                tenant, user, "00000000-0000-4000-8000-000000000921",
                connection, "octocat/private-repo", null, "official-import-one");

        var first = imports.resolve(command);
        var replay = imports.resolve(command);

        assertThat(first.providerRepositoryId()).isEqualTo("octocat/private-repo");
        assertThat(first.privateRepository()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(tools.calls).hasValue(1);
        assertThat(tools.lastTool).isEqualTo("search_repositories");
    }

    private static final class SearchTools implements McpRemoteToolApplicationApi {
        private final AtomicInteger calls = new AtomicInteger();
        private String lastTool;
        public List<ToolView> listTools(String tenantId, String userId, String connectionId) {
            return List.of();
        }
        public ToolResult callTool(CallCommand command) {
            calls.incrementAndGet();
            lastTool = command.toolName();
            return new ToolResult(false, null, """
                    {"total_count":1,"incomplete_results":false,"items":[{
                      "id":42,"name":"private-repo","full_name":"octocat/private-repo",
                      "html_url":"https://github.com/octocat/private-repo",
                      "private":true,"archived":false,"default_branch":"main"}]}
                    """);
        }
    }

    private static final class UnsupportedRemoteGateway implements McpRemoteToolGateway {
        public List<RemoteTool> listTools(McpConnection connection, Map<String, String> auth) {
            throw new UnsupportedOperationException();
        }
        public RemoteResult callTool(
                McpConnection connection, Map<String, String> auth,
                String tool, Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Cipher implements McpConnectionSecretCipher {
        public String encrypt(String value) {
            return "cipher:" + Base64.getEncoder().encodeToString(
                    value.getBytes(StandardCharsets.UTF_8));
        }
        public String decrypt(String value) {
            return new String(Base64.getDecoder().decode(value.substring(7)),
                    StandardCharsets.UTF_8);
        }
    }
}
