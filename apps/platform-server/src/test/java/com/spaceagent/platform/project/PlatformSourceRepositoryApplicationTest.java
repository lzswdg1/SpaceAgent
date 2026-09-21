package com.spaceagent.platform.project;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.HeartbeatLocalWorkspaceBridgeCommand;
import com.spaceagent.platform.project.api.ImportGithubMcpRepositoryCommand;
import com.spaceagent.platform.project.api.ImportLocalRepositoryCommand;
import com.spaceagent.platform.project.api.ListSourceRepositoriesQuery;
import com.spaceagent.platform.project.api.RegisterLocalWorkspaceBridgeCommand;
import com.spaceagent.platform.project.application.LocalWorkspaceBridgeApplicationService;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectApplicationService;
import com.spaceagent.platform.project.application.SourceRepositoryApplicationService;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryLocalWorkspaceBridgeRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectMembershipRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectDirectoryRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemorySourceRepositoryRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformSourceRepositoryApplicationTest {
    private static final Instant NOW = Instant.parse("2026-08-22T23:30:00Z");
    private LocalWorkspaceBridgeApplicationService bridges;
    private SourceRepositoryApplicationService sources;
    private InMemoryProjectDirectoryRepository directories;
    private String projectId;

    @BeforeEach
    void setUp() {
        IdentityOwnershipPort identity = (tenant, user) ->
                "tenant-1".equals(tenant) && List.of("owner-1", "other-1").contains(user);
        var projectRepository = new InMemoryProjectRepository();
        var memberships = new InMemoryProjectMembershipRepository();
        directories = new InMemoryProjectDirectoryRepository();
        ProjectAccessPolicy access = new ProjectAccessPolicy(projectRepository, memberships, identity, (t, u, w) -> {});
        UuidGenerator ids = new UuidGenerator();
        ProjectApplicationService projects = new ProjectApplicationService(
                projectRepository, memberships, access, ids, () -> NOW, directories);
        projectId = projects.createProject(new CreateProjectCommand(
                "tenant-1", "owner-1", "Source Project", null)).id();
        bridges = new LocalWorkspaceBridgeApplicationService(
                new InMemoryLocalWorkspaceBridgeRepository(), access, ids, () -> NOW);
        sources = new SourceRepositoryApplicationService(
                new InMemorySourceRepositoryRepository(), access, bridges, ids, () -> NOW,
                directories);
    }

    @Test
    void importsValidatedMcpSourceIdempotentlyWithOpaqueProvenance() {
        String connection = "00000000-0000-4000-8000-000000000099";
        String invocation = "00000000-0000-4000-8000-000000000098";
        var command = new ImportGithubMcpRepositoryCommand(
                "tenant-1", "owner-1", projectId, connection, invocation, "mcp-repo-42",
                "openai", "openai-java", "https://github.com/openai/openai-java.git",
                "main", true, false);

        var first = sources.importGithubMcp(command);
        var replay = sources.importGithubMcp(command);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(first.mcpConnectionId()).isEqualTo(connection);
        assertThat(first.mcpInvocationId()).isEqualTo(invocation);
        assertThat(first.visibility()).isEqualTo(SourceRepositoryVisibility.PRIVATE);
        assertThat(first.displayName()).isEqualTo("openai/openai-java");
        assertThat(directories.findBySourceAndPath(projectId, first.id(), "."))
                .hasValueSatisfying(directory -> {
                    assertThat(directory.name()).isEqualTo("openai/openai-java");
                    assertThat(directory.defaultDirectory()).isFalse();
                });
    }

    @Test
    void importsLocalSourceWithOpaquePathProtection() {
        assertThatThrownBy(() -> bridges.register(new RegisterLocalWorkspaceBridgeCommand(
                "tenant-1", "owner-1", "Laptop", "device-1", "/Users/me/project")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("WORKSPACE_BRIDGE_ROOT_HANDLE_INVALID"));
        var created = bridges.register(new RegisterLocalWorkspaceBridgeCommand(
                "tenant-1", "owner-1", "Laptop", "device-1", "root_12345678"));
        assertThat(created.bridgeToken()).startsWith("brg_");
        assertThat(created.bridge().toString()).doesNotContain(created.bridgeToken());
        assertThatThrownBy(() -> bridges.heartbeat(new HeartbeatLocalWorkspaceBridgeCommand(
                "tenant-1", "owner-1", created.bridge().id(), "wrong")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("WORKSPACE_BRIDGE_AUTHENTICATION_FAILED"));
        bridges.heartbeat(new HeartbeatLocalWorkspaceBridgeCommand(
                "tenant-1", "owner-1", created.bridge().id(), created.bridgeToken()));

        var local = sources.importLocal(new ImportLocalRepositoryCommand(
                "tenant-1", "owner-1", projectId, created.bridge().id(),
                "root_12345678", "Local Project", "main"));
        assertThat(local.type()).isEqualTo(SourceRepositoryType.LOCAL);
        assertThat(local.remoteUrl()).isNull();
        assertThat(local.localRootHandle()).isEqualTo("root_12345678");
        assertThat(local.toString()).doesNotContain("/Users", "C:\\");
    }

    @Test
    void projectOwnershipFailsClosed() {
        assertThatThrownBy(() -> sources.list(new ListSourceRepositoriesQuery(
                "tenant-1", "other-1", projectId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PROJECT_ACCESS_DENIED"));
    }
}
