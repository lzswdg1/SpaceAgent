package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotView;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerView;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.project.api.PlanStepView;
import com.spaceagent.platform.project.api.ProjectBlueprintApplicationApi;
import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.ProjectBlueprintDocument;
import com.spaceagent.platform.project.domain.ProjectBlueprintSource;
import com.spaceagent.platform.project.domain.ProjectBlueprintStatus;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CheckpointView;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectRecoveryApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectExecutionContextSnapshotRepository;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformProjectRecoveryApplicationTest {

    private final RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
    private final ProjectDirectoryApplicationApi directories = mock(ProjectDirectoryApplicationApi.class);
    private final WorkspaceApplicationApi workspaces = mock(WorkspaceApplicationApi.class);
    private final TaskApplicationApi tasks = mock(TaskApplicationApi.class);
    private final TaskPlanApplicationApi plans = mock(TaskPlanApplicationApi.class);
    private final ProjectBlueprintApplicationApi blueprints = mock(ProjectBlueprintApplicationApi.class);
    private final ConversationApplicationApi conversations = mock(ConversationApplicationApi.class);
    private final ConversationContextSnapshotApplicationApi conversationSnapshots =
            mock(ConversationContextSnapshotApplicationApi.class);
    private final ArtifactApplicationApi artifacts = mock(ArtifactApplicationApi.class);
    private final ModelCallLedgerApplicationApi modelCalls = mock(ModelCallLedgerApplicationApi.class);
    private final ToolExecutionLedgerApplicationApi tools = mock(ToolExecutionLedgerApplicationApi.class);
    private final GovernanceApplicationApi governance = mock(GovernanceApplicationApi.class);
    private final SandboxComputeApplicationApi sandbox = mock(SandboxComputeApplicationApi.class);
    private final InMemoryProjectExecutionContextSnapshotRepository repository =
            new InMemoryProjectExecutionContextSnapshotRepository();
    private final AtomicInteger sequence = new AtomicInteger(100);
    private ProjectRecoveryApplicationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        service = new ProjectRecoveryApplicationService(
                repository, runtime, directories, workspaces, tasks, plans, blueprints,
                conversations, conversationSnapshots, artifacts, modelCalls, tools, governance,
                sandbox, json, () -> uuid(sequence.incrementAndGet()), () -> NOW);
        fixture();
    }

    @Test
    void capturesCompleteSandboxGitPackageAndReplaysSameIdempotencyKey() {
        var command = new ProjectRecoveryApplicationApi.CaptureCommand(
                TENANT, USER, PROJECT, RUN, "recovery-request-001");

        var first = service.capture(command);
        var replay = service.capture(command);

        assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(service.latest(new ProjectRecoveryApplicationApi.LatestQuery(
                TENANT, USER, PROJECT, RUN)).snapshotId()).isEqualTo(first.snapshotId());
        assertThat(service.get(new ProjectRecoveryApplicationApi.Query(
                TENANT, USER, PROJECT, RUN, first.snapshotId())).snapshotId())
                .isEqualTo(first.snapshotId());
        assertThat(first.snapshotSha256()).startsWith("sha256:");
        assertThat(first.context().project().projectDirectoryId()).isEqualTo(DIRECTORY);
        assertThat(first.context().blueprint().version()).isEqualTo(2);
        assertThat(first.context().workspace().liveHeadCommit()).isEqualTo(HEAD);
        assertThat(first.context().workspace().changedFiles())
                .containsExactly("notes.txt", "src/Main.java");
        assertThat(first.context().workspace().patch())
                .contains("src/Main.java", "notes.txt");
        assertThat(first.context().runtime().checkpointSha256()).startsWith("sha256:");
        assertThat(first.context().runtime().runEventCursor()).isEqualTo(12L);
        assertThat(first.context().unknownToolEffects()).singleElement()
                .satisfies(value -> assertThat(value.toolCallId()).isEqualTo("tool-unknown"));
        assertThat(first.context().unknownModelEffects()).singleElement()
                .satisfies(value -> assertThat(value.logicalCallId()).isEqualTo("model-unknown"));
        assertThat(first.context().approvals()).singleElement()
                .satisfies(value -> assertThat(value.state()).isEqualTo(ApprovalState.PENDING));
        assertThat(first.context().blockers()).contains(
                "TOOL_EFFECT_RECONCILIATION_REQUIRED",
                "MODEL_EFFECT_RECONCILIATION_REQUIRED",
                "GOVERNANCE_APPROVAL_PENDING");
        assertThat(first.context().nextAction()).isEqualTo("RECONCILE_UNKNOWN_EFFECTS");

        String serialized = first.toString();
        assertThat(serialized).doesNotContain(
                "checkpoint-secret", "tool-arguments-secret", "tool-result-secret",
                "model-response-secret", "approval-operation-secret", "artifact-secret");
        verify(sandbox, times(6)).execute(any());
    }

    @Test
    void sameIdempotencyKeyCannotMoveToAnotherRunScope() {
        String otherRun = uuid(999);
        service.capture(new ProjectRecoveryApplicationApi.CaptureCommand(
                TENANT, USER, PROJECT, RUN, "recovery-request-002"));
        AgentRunView current = runtime.findRun(RUN).orElseThrow();
        when(runtime.findRun(otherRun)).thenReturn(Optional.of(new AgentRunView(
                otherRun, current.agentId(), current.configurationSnapshotId(), current.tenantId(),
                current.ownerId(), current.conversationId(), current.projectId(),
                current.projectDirectoryId(), current.workspaceId(), current.taskId(),
                current.taskPlanId(), current.planStepId(), current.executionCursor(),
                current.revision(), current.state(), null, NOW, NOW, null)));

        assertThatThrownBy(() -> service.capture(
                new ProjectRecoveryApplicationApi.CaptureCommand(
                        TENANT, USER, PROJECT, otherRun, "recovery-request-002")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "CODING_RECOVERY_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void sandboxFailureNeverPersistsPartialPackage() {
        doThrow(new IllegalStateException("host fallback forbidden"))
                .when(sandbox).execute(any());

        assertThatThrownBy(() -> service.capture(
                new ProjectRecoveryApplicationApi.CaptureCommand(
                        TENANT, USER, PROJECT, RUN, "recovery-request-003")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "CODING_RECOVERY_SANDBOX_REQUIRED"));
        assertThat(repository.findLatestByRunId(RUN)).isEmpty();
    }

    @Test
    void changingRunRevisionRejectsStaleCapture() {
        AgentRunView initial = runtime.findRun(RUN).orElseThrow();
        AgentRunView changed = new AgentRunView(
                initial.id(), initial.agentId(), initial.configurationSnapshotId(), initial.tenantId(),
                initial.ownerId(), initial.conversationId(), initial.projectId(),
                initial.projectDirectoryId(), initial.workspaceId(), initial.taskId(),
                initial.taskPlanId(), initial.planStepId(), initial.executionCursor(),
                initial.revision() + 1, initial.state(), null, NOW, NOW, null);
        when(runtime.findRun(RUN)).thenReturn(Optional.of(initial), Optional.of(changed));

        assertThatThrownBy(() -> service.capture(
                new ProjectRecoveryApplicationApi.CaptureCommand(
                        TENANT, USER, PROJECT, RUN, "recovery-request-004")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("CODING_RECOVERY_CAPTURE_STALE"));
        assertThat(repository.findLatestByRunId(RUN)).isEmpty();
    }

    @Test
    void outputLimitFailsWithoutPartialSnapshot() {
        doReturn(new SandboxComputeApplicationApi.ComputeResult(
                "OUTPUT_LIMIT_EXCEEDED", 0, "partial", "", false, 1,
                1_000_000, "SANDBOX_OUTPUT_LIMIT"))
                .when(sandbox).execute(any());

        assertThatThrownBy(() -> service.capture(
                new ProjectRecoveryApplicationApi.CaptureCommand(
                        TENANT, USER, PROJECT, RUN, "recovery-request-005")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "CODING_RECOVERY_EVIDENCE_TOO_LARGE"));
        assertThat(repository.findLatestByRunId(RUN)).isEmpty();
    }

    @Test
    void tenantOwnerScopeFailsClosedBeforeSandbox() {
        assertThatThrownBy(() -> service.capture(
                new ProjectRecoveryApplicationApi.CaptureCommand(
                        TENANT, "foreign-user", PROJECT, RUN, "recovery-request-006")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("CODING_RECOVERY_RUN_NOT_FOUND"));
        verify(sandbox, times(0)).execute(any());
    }

    private void fixture() {
        AgentRunView run = new AgentRunView(
                RUN, AGENT, AGENT_VERSION, TENANT, USER, CONVERSATION, PROJECT, DIRECTORY,
                WORKSPACE, TASK, PLAN, STEP, new ExecutionCursor("coding", STEP, null, 0),
                7, AgentRunState.IN_PROGRESS, null, NOW, NOW, null);
        when(runtime.findRun(RUN)).thenReturn(Optional.of(run));
        when(runtime.findLatestCheckpoint(RUN)).thenReturn(Optional.of(
                new CheckpointView(CHECKPOINT, RUN, 4, "checkpoint-secret", NOW)));
        when(runtime.findSteps(RUN)).thenReturn(List.of(
                new RunStepView(RUN_STEP, RUN, 3, "coding", RunStepState.IN_PROGRESS, NOW, null)));
        when(runtime.latestEventSequence(RUN)).thenReturn(12L);
        when(directories.get(any())).thenReturn(new ProjectDirectoryApplicationApi.DirectoryView(
                DIRECTORY, TENANT, PROJECT, SOURCE, "Repository", ".", false,
                ProjectDirectoryState.ACTIVE, NOW, NOW));
        when(workspaces.get(any())).thenReturn(new WorkspaceApplicationApi.WorkspaceView(
                WORKSPACE, PROJECT, DIRECTORY, TASK, SOURCE, null, "primary",
                WorkspaceMode.MANAGED_GIT, uuid(12), "main", "spaceagent/task", "managed",
                HEAD, true, WorkspaceState.READY, null, 2, NOW, NOW));
        when(conversations.find(CONVERSATION)).thenReturn(Optional.of(new ConversationView(
                CONVERSATION, PROJECT, DIRECTORY, null, ROOT_TASK, TENANT, USER, AGENT,
                "Project", ConversationStatus.ACTIVE, NOW, NOW)));
        TaskView child = new TaskView(
                TASK, PROJECT, ROOT_TASK, "Child", "Implement", null,
                List.of("sandbox"), List.of("tests pass"), null,
                TaskState.IN_PROGRESS, NOW, NOW);
        TaskView root = new TaskView(
                ROOT_TASK, PROJECT, null, "Root", "Ship feature", null,
                List.of(), List.of(), PLAN, TaskState.IN_PROGRESS, NOW, NOW);
        when(tasks.getTask(any())).thenAnswer(invocation -> {
            String id = invocation.<com.spaceagent.platform.project.api.GetTaskQuery>getArgument(0)
                    .taskId();
            return id.equals(TASK) ? child : root;
        });
        PlanStepView step = new PlanStepView(
                STEP, "implement", 0, TASK, List.of(), "coding", AGENT,
                "working code", List.of("tests pass"), true, PlanStepState.IN_PROGRESS,
                NOW, NOW);
        when(plans.getPlan(any())).thenReturn(new TaskPlanView(
                PLAN, PROJECT, ROOT_TASK, 3, TaskPlanStatus.ACTIVE, AGENT_VERSION, USER,
                USER, NOW, List.of(step), NOW, NOW));
        var document = new ProjectBlueprintDocument(
                "Build", List.of("recover"), List.of("runtime"), List.of("Java owns state"),
                List.of("ADR"), Map.of("test", "./mvnw test"), List.of("DB_URL"),
                List.of("Java 21"), List.of("no host execution"), List.of("unknown effect"),
                List.of(), List.of("tests pass"), List.of("baseline"));
        when(blueprints.list(any())).thenReturn(List.of(
                new ProjectBlueprintApplicationApi.BlueprintView(
                        BLUEPRINT, PROJECT, 2, ProjectBlueprintStatus.CONFIRMED,
                        ProjectBlueprintSource.USER, SOURCE, null, USER, USER, NOW,
                        document, NOW, NOW)));
        when(conversationSnapshots.findLatestByConversationId(CONVERSATION)).thenReturn(Optional.of(
                new ConversationContextSnapshotView(
                        CONTEXT_SNAPSHOT, CONVERSATION, 5, "current context", 0, 10,
                        20, "sha256:context", NOW)));
        when(artifacts.byRun(RUN)).thenReturn(List.of(
                new ArtifactApplicationApi.ArtifactView(
                        TEST_ARTIFACT, PROJECT, TASK, RUN, WORKSPACE, ArtifactType.TEST_REPORT,
                        "test", "ledger:test", "sha256:test", "exit=0",
                        "{\"exitCode\":0,\"executable\":\"./mvnw\",\"secret\":\"artifact-secret\"}", NOW),
                new ArtifactApplicationApi.ArtifactView(
                        ACCEPTANCE_ARTIFACT, PROJECT, TASK, RUN, WORKSPACE,
                        ArtifactType.ACCEPTANCE_EVIDENCE, "acceptance", null,
                        "sha256:acceptance", "passed",
                        "{\"criterion\":\"tests pass\",\"passed\":true}", NOW)));
        when(modelCalls.findByRunId(RUN)).thenReturn(List.of(new ModelCallLedgerView(
                MODEL_CALL, RUN, RUN_STEP, "model-unknown", "sha256:request",
                ModelCallStatus.UNKNOWN, "provider", "model", "worker", NOW.plusSeconds(60),
                1, NOW, null, null, null, "model-response-secret", null, "AMBIGUOUS", "secret", NOW, NOW)));
        when(tools.findByRunId(RUN)).thenReturn(List.of(new ToolExecutionLedgerView(
                TOOL_CALL, RUN, RUN_STEP, "coding-run-command", "tool-unknown", "key",
                "tool-arguments-secret", "sha256:input", ToolExecutionStatus.UNKNOWN,
                "tool-result-secret", null, "unknown", NOW, null, "worker",
                NOW.plusSeconds(60), 1, NOW, NOW, null, null, null)));
        when(governance.listRequestedApprovals(any())).thenReturn(List.of(
                new GovernanceApplicationApi.ApprovalView(
                        APPROVAL, TENANT, USER, GovernanceActionType.COMMAND_EXECUTION,
                        "WORKSPACE", WORKSPACE, "approval-operation-secret", "Run tests",
                        ApprovalState.PENDING, NOW.plusSeconds(600), null, null, null,
                        null, 1, NOW, NOW)));
        when(sandbox.execute(any())).thenAnswer(invocation -> sandboxResult(
                invocation.<SandboxComputeApplicationApi.ComputeCommand>getArgument(0)));
    }

    private SandboxComputeApplicationApi.ComputeResult sandboxResult(
            SandboxComputeApplicationApi.ComputeCommand command) {
        List<String> arguments = command.arguments();
        if (arguments.equals(List.of("rev-parse", "HEAD"))) return success(HEAD + "\n");
        if (arguments.getFirst().equals("status")) return success(" M src/Main.java\n?? notes.txt\n");
        if (arguments.equals(List.of("diff", "--name-only", "-z", "HEAD", "--"))) {
            return success("src/Main.java\0");
        }
        if (arguments.getFirst().equals("ls-files")) return success("notes.txt\0");
        if (arguments.contains("--no-index")) {
            return new SandboxComputeApplicationApi.ComputeResult(
                    "FAILED", 1, "diff --git a/notes.txt b/notes.txt\n+notes\n", "", false,
                    1, 40, "SANDBOX_EXIT_1");
        }
        return success("diff --git a/src/Main.java b/src/Main.java\n+code\n");
    }

    private static SandboxComputeApplicationApi.ComputeResult success(String stdout) {
        return new SandboxComputeApplicationApi.ComputeResult(
                "SUCCEEDED", 0, stdout, "", false, 1,
                stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, null);
    }

    private static String uuid(int value) {
        return "00000000-0000-4000-8000-" + String.format("%012d", value);
    }

    private static final Instant NOW = Instant.parse("2026-09-05T02:00:00Z");
    private static final String TENANT = uuid(1);
    private static final String USER = uuid(2);
    private static final String PROJECT = uuid(3);
    private static final String DIRECTORY = uuid(4);
    private static final String SOURCE = uuid(5);
    private static final String WORKSPACE = uuid(6);
    private static final String ROOT_TASK = uuid(7);
    private static final String TASK = uuid(8);
    private static final String PLAN = uuid(9);
    private static final String STEP = uuid(10);
    private static final String AGENT = uuid(11);
    private static final String AGENT_VERSION = uuid(13);
    private static final String CONVERSATION = uuid(14);
    private static final String RUN = uuid(15);
    private static final String RUN_STEP = uuid(16);
    private static final String CHECKPOINT = uuid(17);
    private static final String CONTEXT_SNAPSHOT = uuid(18);
    private static final String BLUEPRINT = uuid(19);
    private static final String TEST_ARTIFACT = uuid(20);
    private static final String ACCEPTANCE_ARTIFACT = uuid(21);
    private static final String MODEL_CALL = uuid(22);
    private static final String TOOL_CALL = uuid(23);
    private static final String APPROVAL = uuid(24);
    private static final String HEAD = "a".repeat(40);
}
