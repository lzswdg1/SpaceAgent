package com.spaceagent.platform.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.api.ProjectBlueprintApplicationApi;
import com.spaceagent.platform.project.api.ProjectIntakeApplicationApi;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.application.ProjectIntakeApplicationService;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectBlueprintDocument;
import com.spaceagent.platform.project.domain.ProjectBlueprintSource;
import com.spaceagent.platform.project.domain.ProjectBlueprintStatus;
import com.spaceagent.platform.project.domain.ProjectDirectory;
import com.spaceagent.platform.project.domain.ProjectIntakeWorkspaceGateway;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.domain.WorkspaceCheckoutCredentialPort;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectDirectoryRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectIntakeRepository;
import com.spaceagent.platform.project.infrastructure.memory.InMemorySourceRepositoryRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformProjectIntakeApplicationTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final String TENANT = "tenant";
    private static final String USER = "user";
    private static final String PROJECT_ID = UUID.randomUUID().toString();
    private static final String DIRECTORY_ID = UUID.randomUUID().toString();
    private static final String SOURCE_ID = UUID.randomUUID().toString();
    private static final String CONVERSATION_ID = UUID.randomUUID().toString();
    private static final String AGENT_ID = UUID.randomUUID().toString();
    private static final String VERSION_ID = UUID.randomUUID().toString();
    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final String HEAD = "a".repeat(40);

    private final InMemoryProjectIntakeRepository jobs = new InMemoryProjectIntakeRepository();
    private final InMemoryProjectDirectoryRepository directories =
            new InMemoryProjectDirectoryRepository();
    private final InMemorySourceRepositoryRepository sources =
            new InMemorySourceRepositoryRepository();
    private final ProjectAccessPolicy access = mock(ProjectAccessPolicy.class);
    private final ProjectIntakeWorkspaceGateway workspace =
            mock(ProjectIntakeWorkspaceGateway.class);
    private final WorkspaceCheckoutCredentialPort credentials =
            mock(WorkspaceCheckoutCredentialPort.class);
    private final ProjectBlueprintApplicationApi blueprints =
            mock(ProjectBlueprintApplicationApi.class);
    private final TaskApplicationApi tasks = mock(TaskApplicationApi.class);
    private final TaskPlanApplicationApi plans = mock(TaskPlanApplicationApi.class);
    private final ObjectMapper json = new ObjectMapper();
    private ProjectIntakeApplicationService service;

    @BeforeEach
    void setUp() {
        Project project = Project.create(PROJECT_ID, TENANT, USER, "Repository", null, NOW);
        directories.save(new ProjectDirectory(
                DIRECTORY_ID, TENANT, PROJECT_ID, SOURCE_ID, "Repository", ".", false,
                ProjectDirectoryState.ACTIVE, USER, NOW, NOW));
        sources.save(new SourceRepository(
                SOURCE_ID, PROJECT_ID, TENANT, null, null, null, "repository", "Repository",
                "https://example.com/repository.git", null, "main", SourceRepositoryType.GIT,
                SourceRepositoryState.READY, SourceRepositoryVisibility.PUBLIC, USER, NOW, NOW));
        when(access.requireProject(TENANT, USER, PROJECT_ID)).thenReturn(project);
        when(workspace.provision(any(), any(), any(), any())).thenReturn(
                new ProjectIntakeWorkspaceGateway.IntakeWorkspace(
                        "workspaces/" + UUID.randomUUID(), HEAD));
        service = new ProjectIntakeApplicationService(
                jobs, directories, sources, access, workspace, credentials, blueprints,
                tasks, plans, json, new UuidGenerator(), () -> NOW);
    }

    @Test
    void proposalIsDurableRestartSafeAndRejectsStaleFencingToken() throws Exception {
        var queued = enqueue("intake-idempotency-001");
        assertThat(enqueue("intake-idempotency-001").id()).isEqualTo(queued.id());
        var claim = service.claim("worker-1", 300, 3).orElseThrow();
        var provisioned = service.provisionWorkspace(command(claim));
        service.attachRun(new ProjectIntakeApplicationApi.AttachRunCommand(
                queued.id(), "worker-1", claim.claimToken(), claim.fencingToken(), RUN_ID));
        String inspection = json.writeValueAsString(Map.of(
                "headCommit", HEAD,
                "trackedFileCount", 2,
                "trackedPaths", List.of("README.md", "pom.xml"),
                "selectedFiles", List.of(Map.of(
                        "path", "README.md", "sha256", sha256("redacted"),
                        "truncated", false, "redactedContent", "redacted"))));
        service.recordInspection(new ProjectIntakeApplicationApi.InspectionCommand(
                queued.id(), "worker-1", claim.claimToken(), claim.fencingToken(), HEAD,
                sha256(inspection), inspection));
        assertThat(service.readInspection(command(claim))).isEqualTo(inspection);
        String proposal = proposalJson();
        var proposed = service.publishProposal(new ProjectIntakeApplicationApi.ProposalCommand(
                queued.id(), "worker-1", claim.claimToken(), claim.fencingToken(),
                sha256(proposal), proposal));

        assertThat(proposed.state().name()).isEqualTo("PROPOSED");
        assertThat(proposed.inspection().selectedFiles()).singleElement()
                .satisfies(file -> assertThat(file.path()).isEqualTo("README.md"));
        assertThat(service.get(new ProjectIntakeApplicationApi.Query(
                TENANT, USER, PROJECT_ID, DIRECTORY_ID, queued.id())).proposal())
                .isNotNull();
        assertThat(service.list(new ProjectIntakeApplicationApi.ListQuery(
                TENANT, USER, PROJECT_ID, DIRECTORY_ID, 1, 20)).total()).isEqualTo(1);
        assertThatThrownBy(() -> service.recordInspection(
                new ProjectIntakeApplicationApi.InspectionCommand(
                        queued.id(), "worker-1", claim.claimToken(), claim.fencingToken(), HEAD,
                        sha256(inspection), inspection)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_INTAKE_LEASE_LOST"));
        assertThat(provisioned.sourceHeadCommit()).isEqualTo(HEAD);
    }

    @Test
    void confirmationCreatesBlueprintTasksAndActivePlanExactlyOnce() throws Exception {
        var proposed = proposed();
        var document = new ProjectBlueprintDocument(
                "Build", List.of(), List.of(), List.of(), List.of(), Map.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of("done"), List.of());
        var blueprint = new ProjectBlueprintApplicationApi.BlueprintView(
                UUID.randomUUID().toString(), PROJECT_ID, 1, ProjectBlueprintStatus.DRAFT,
                ProjectBlueprintSource.AGENT, SOURCE_ID, VERSION_ID, USER, null, null,
                document, NOW, NOW);
        var confirmed = new ProjectBlueprintApplicationApi.BlueprintView(
                blueprint.id(), PROJECT_ID, 1, ProjectBlueprintStatus.CONFIRMED,
                ProjectBlueprintSource.AGENT, SOURCE_ID, VERSION_ID, USER, USER, NOW,
                document, NOW, NOW);
        when(blueprints.create(any())).thenReturn(blueprint);
        when(blueprints.confirm(any())).thenReturn(confirmed);
        when(tasks.createTask(any()))
                .thenReturn(task(UUID.randomUUID().toString(), null))
                .thenReturn(task(UUID.randomUUID().toString(), "root"));
        var plan = new TaskPlanView(
                UUID.randomUUID().toString(), PROJECT_ID, UUID.randomUUID().toString(), 1,
                TaskPlanStatus.DRAFT, VERSION_ID, USER, null, null, List.of(), NOW, NOW);
        when(plans.createPlan(any())).thenReturn(plan);
        when(plans.transition(any())).thenReturn(plan);

        var result = service.confirm(new ProjectIntakeApplicationApi.ConfirmCommand(
                TENANT, USER, PROJECT_ID, DIRECTORY_ID, proposed.id(), proposed.proposalHash()));
        var replay = service.confirm(new ProjectIntakeApplicationApi.ConfirmCommand(
                TENANT, USER, PROJECT_ID, DIRECTORY_ID, proposed.id(), proposed.proposalHash()));

        assertThat(result.state().name()).isEqualTo("CONFIRMED");
        assertThat(result.blueprintId()).isEqualTo(confirmed.id());
        assertThat(replay.rootTaskId()).isEqualTo(result.rootTaskId());
        assertThatThrownBy(() -> service.confirm(new ProjectIntakeApplicationApi.ConfirmCommand(
                TENANT, USER, PROJECT_ID, DIRECTORY_ID, proposed.id(),
                "sha256:" + "f".repeat(64))))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_INTAKE_PROPOSAL_STALE"));
        verify(blueprints, times(1)).create(org.mockito.ArgumentMatchers.argThat(command ->
                AGENT_ID.equals(command.generatedByAgentId())
                        && RUN_ID.equals(command.generatedByRunConfigurationSnapshotId())));
        verify(tasks, times(2)).createTask(any());
        verify(plans).createPlan(org.mockito.ArgumentMatchers.argThat(command ->
                command.generatedByConfigurationHash() == null
                        && AGENT_ID.equals(command.generatedByAgentId())
                        && RUN_ID.equals(command.generatedByRunConfigurationSnapshotId())));
        verify(plans, times(3)).transition(any());
    }

    private ProjectIntakeApplicationApi.JobView proposed() throws Exception {
        var queued = enqueue("intake-confirm-001");
        var claim = service.claim("worker-1", 300, 3).orElseThrow();
        service.provisionWorkspace(command(claim));
        service.attachRun(new ProjectIntakeApplicationApi.AttachRunCommand(
                queued.id(), "worker-1", claim.claimToken(), claim.fencingToken(), RUN_ID));
        String inspection = json.writeValueAsString(Map.of(
                "headCommit", HEAD, "trackedFileCount", 0, "trackedPaths", List.of(),
                "selectedFiles", List.of()));
        service.recordInspection(new ProjectIntakeApplicationApi.InspectionCommand(
                queued.id(), "worker-1", claim.claimToken(), claim.fencingToken(), HEAD,
                sha256(inspection), inspection));
        String proposal = proposalJson();
        return service.publishProposal(new ProjectIntakeApplicationApi.ProposalCommand(
                queued.id(), "worker-1", claim.claimToken(), claim.fencingToken(),
                sha256(proposal), proposal));
    }

    private ProjectIntakeApplicationApi.JobView enqueue(String key) {
        return service.enqueue(new ProjectIntakeApplicationApi.EnqueueCommand(
                TENANT, USER, PROJECT_ID, DIRECTORY_ID, CONVERSATION_ID, AGENT_ID,
                "Understand the repository and prepare an implementation plan", key));
    }

    private static ProjectIntakeApplicationApi.ClaimCommand command(
            ProjectIntakeApplicationApi.ClaimView claim) {
        return new ProjectIntakeApplicationApi.ClaimCommand(
                claim.job().id(), "worker-1", claim.claimToken(), claim.fencingToken());
    }

    private String proposalJson() throws Exception {
        var blueprint = new ProjectIntakeApplicationApi.BlueprintDraft(
                "Build", List.of("implement"), List.of("platform"),
                List.of("project owns state"), List.of(), Map.of("test", "./mvnw test"),
                List.of("JAVA_HOME"), List.of(), List.of(), List.of(), List.of(),
                List.of("done"));
        var root = new ProjectIntakeApplicationApi.TaskDraft(
                "Implement", "Build", "Root task", List.of(), List.of("done"));
        var child = new ProjectIntakeApplicationApi.ChildTaskDraft(
                "backend", "Backend", "Implement backend", "Backend task", List.of(),
                List.of("tests pass"));
        var step = new ProjectIntakeApplicationApi.PlanStepDraft(
                "backend-step", "backend", List.of(), "java", "Backend implementation",
                List.of("tests pass"), false);
        return json.writeValueAsString(new ProjectIntakeApplicationApi.ProjectIntakeProposal(
                blueprint, root, List.of(child), List.of(step)));
    }

    private static TaskView task(String id, String parent) {
        return new TaskView(id, PROJECT_ID, parent, "Task", "Goal", null, List.of(),
                List.of(), null, TaskState.PENDING, NOW, NOW);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
