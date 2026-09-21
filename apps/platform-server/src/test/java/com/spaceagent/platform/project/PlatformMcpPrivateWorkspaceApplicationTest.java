package com.spaceagent.platform.project;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.application.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.platform.project.infrastructure.memory.*;
import com.spaceagent.platform.project.infrastructure.WorkspaceProvisioningRecoveryWorker;
import com.spaceagent.platform.project.infrastructure.WorkspaceProperties;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformMcpPrivateWorkspaceApplicationTest {
    private static final Instant NOW = Instant.parse("2026-08-23T18:30:00Z");
    private static final String HEADER = "Bearer short-lived-private-checkout-secret";
    private WorkspaceApplicationService workspaces;
    private InMemoryWorkspaceRepository workspaceRepository;
    private InMemorySourceRepositoryRepository sourceRepository;
    private String projectId;
    private String taskId;
    private SourceRepository source;
    private FakeCredentialPort credentials;
    private FakeGateway gateway;

    @BeforeEach
    void setUp() {
        var ids = new UuidGenerator();
        IdentityOwnershipPort identity = (tenant, user) -> true;
        var projectRepository = new InMemoryProjectRepository();
        var memberships = new InMemoryProjectMembershipRepository();
        var taskRepository = new InMemoryTaskRepository();
        sourceRepository = new InMemorySourceRepositoryRepository();
        var access = new ProjectAccessPolicy(projectRepository, memberships, identity, (t, u, w) -> {});
        var projects = new ProjectApplicationService(
                projectRepository, memberships, access, ids, () -> NOW);
        var tasks = new TaskApplicationService(taskRepository, access, ids, () -> NOW);
        projectId = projects.createProject(new CreateProjectCommand(
                "tenant-1", "owner", "Private MCP", null)).id();
        taskId = tasks.createTask(new CreateTaskCommand(
                "tenant-1", "owner", projectId, null, "Checkout", "Checkout private repo",
                null, List.of(), List.of())).id();
        source = new SourceRepository(
                ids.nextId(), projectId, "tenant-1",
                "00000000-0000-4000-8000-000000000701",
                "00000000-0000-4000-8000-000000000702", null,
                "repo-42", "openai/private-repo",
                "https://github.com/openai/private-repo.git", null, "main",
                SourceRepositoryType.GITHUB, SourceRepositoryState.READY,
                SourceRepositoryVisibility.PRIVATE, "owner", NOW, NOW);
        sourceRepository.save(source);
        var bridgeRepository = new InMemoryLocalWorkspaceBridgeRepository();
        var bridgeApi = new LocalWorkspaceBridgeApplicationService(
                bridgeRepository, access, ids, () -> NOW);
        workspaceRepository = new InMemoryWorkspaceRepository();
        credentials = new FakeCredentialPort();
        gateway = new FakeGateway();
        workspaces = new WorkspaceApplicationService(
                workspaceRepository, new InMemoryBridgeWorkspaceCommandRepository(), access,
                taskRepository, sourceRepository, bridgeRepository, bridgeApi,
                credentials, gateway, ids, () -> NOW);
    }

    @Test
    void usesAndClosesEphemeralMcpCredentialWithoutPersistingIt() {
        var workspace = workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(
                "tenant-1", "owner", projectId, taskId, source.id(), null));
        assertThat(workspace.state()).isEqualTo(WorkspaceState.READY);
        assertThat(credentials.request.get().connectionId()).isEqualTo(source.mcpConnectionId());
        assertThat(gateway.authorizationHeader.get()).isEqualTo(HEADER);
        assertThat(credentials.closed).isTrue();
        assertThat(workspace.toString()).doesNotContain(HEADER, "private-checkout-secret");
        assertThat(workspaceRepository.findById(workspace.id()).orElseThrow().toString())
                .doesNotContain(HEADER, "private-checkout-secret");
    }

    @Test
    void redactsGitFailureBeforeWorkspacePersistenceAndHttpPropagation() {
        gateway.fail = true;
        assertThatThrownBy(() -> workspaces.provision(
                new WorkspaceApplicationApi.ProvisionCommand(
                        "tenant-1", "owner", projectId, taskId, source.id(), null)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("WORKSPACE_PROVISIONING_FAILED");
                    assertThat(error.getMessage()).doesNotContain(HEADER, "private-checkout-secret");
                });
        assertThat(credentials.closed).isTrue();
        var failed = workspaces.list(new WorkspaceApplicationApi.ListQuery(
                "tenant-1", "owner", projectId)).getFirst();
        assertThat(failed.state()).isEqualTo(WorkspaceState.FAILED);
        assertThat(failed.failureReason()).isEqualTo("WORKSPACE_PROVISIONING_FAILED");
        assertThat(failed.toString()).doesNotContain(HEADER, "private-checkout-secret");
    }

    @Test
    void recoversStaleProvisioningAfterProcessInterruption() {
        Workspace stale = new Workspace(
                "00000000-0000-4000-8000-000000000711", "tenant-1", projectId, taskId,
                source.id(), null, "recovery", WorkspaceMode.MANAGED_GIT,
                "00000000-0000-4000-8000-000000000712", "main",
                "spaceagent/recovery/stale", null, null, true,
                WorkspaceState.PROVISIONING, null, 0, "owner",
                NOW.minusSeconds(600), NOW.minusSeconds(600));
        workspaceRepository.save(stale);
        new WorkspaceProvisioningRecoveryWorker(
                workspaceRepository, sourceRepository, gateway, () -> NOW,
                new WorkspaceProperties(), 300)
                .recoverStale();
        Workspace recovered = workspaceRepository.findById(stale.id()).orElseThrow();
        assertThat(gateway.cleaned).isTrue();
        assertThat(recovered.state()).isEqualTo(WorkspaceState.FAILED);
        assertThat(recovered.failureReason()).isEqualTo("WORKSPACE_PROVISIONING_INTERRUPTED");
        assertThat(recovered.toString()).doesNotContain(HEADER, "private-checkout-secret");
    }

    private static final class FakeCredentialPort implements WorkspaceCheckoutCredentialPort {
        private final AtomicReference<Request> request = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public CredentialLease acquire(Request value) {
            request.set(value);
            return new CredentialLease() {
                public String authorizationHeader() { return HEADER; }
                public void close() { closed.set(true); }
                public String toString() {
                    return "CredentialLease[authorizationHeader=<redacted>]";
                }
            };
        }
    }

    private static final class FakeGateway implements WorkspaceProvisioningGateway {
        private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private boolean fail;

        @Override
        public ProvisionedWorkspace provision(
                Workspace workspace, SourceRepository source, String header) {
            authorizationHeader.set(header);
            if (fail) throw new IllegalStateException("Git failed with " + HEADER);
            return new ProvisionedWorkspace("managed:" + workspace.id(), "b".repeat(40));
        }

        @Override
        public void cleanup(Workspace workspace, SourceRepository source) {
            cleaned.set(true);
        }
    }
}
