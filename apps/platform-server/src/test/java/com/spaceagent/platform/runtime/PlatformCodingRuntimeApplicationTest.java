package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.artifact.application.ArtifactApplicationService;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.artifact.infrastructure.memory.InMemoryArtifactRepository;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.application.GovernanceApplicationService;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.infrastructure.memory.InMemoryGovernanceRepository;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.project.api.CodingWorkspaceApplicationApi;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.TaskExecutionReferenceView;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.WorkspaceCodingGateway;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.application.CodingRuntimeApplicationService;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeLedgerRepository;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.domain.SandboxExecutionUnavailableException;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformCodingRuntimeApplicationTest {

    @Test
    void ledgeredActionsRequireAcceptanceAndProducePatchCommitArtifacts() {
        Fixture fixture = new Fixture();
        var run = fixture.start();
        var action = fixture.service.execute(fixture.action(
                run.agentRunId(), "call-1", "done", null));
        assertThat(action.status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.ledger.findByRunId(run.agentRunId())).hasSize(1);

        assertThatThrownBy(() -> fixture.service.finalizeRun(
                new CodingRuntimeApplicationApi.FinalizeCommand(
                        "user", run.agentRunId(), "workspace", "implement")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("CODING_ACCEPTANCE_INCOMPLETE"));
        fixture.service.evidence(new CodingRuntimeApplicationApi.EvidenceCommand(
                "user", run.agentRunId(), "workspace", "tests pass", true, "verified"));
        var done = fixture.service.finalizeRun(
                new CodingRuntimeApplicationApi.FinalizeCommand(
                        "user", run.agentRunId(), "workspace", "implement"));
        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(fixture.artifactApi.byRun(run.agentRunId()))
                .extracting(value -> value.type())
                .contains(
                        ArtifactType.ACCEPTANCE_EVIDENCE,
                        ArtifactType.PATCH,
                        ArtifactType.COMMIT_PROPOSAL);
    }

    @Test
    void codingMutationIsBlockedBeforeClaimAndUsesExactOneTimeApproval() {
        Fixture fixture = new Fixture();
        fixture.governance.updatePolicy(new GovernanceApplicationApi.UpdatePolicyCommand(
                "tenant", "user", true, false, false,
                false, false, false, 3_600, 0));
        var run = fixture.start();
        var action = fixture.action(run.agentRunId(), "call-approval", "approved-content", null);

        assertThatThrownBy(() -> fixture.service.execute(action))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("GOVERNANCE_APPROVAL_REQUIRED");
                    assertThat(error.getMessage()).contains("id-");
                });
        assertThat(fixture.coding.executions).isZero();
        assertThat(fixture.ledger.findByRunId(run.agentRunId())).isEmpty();

        var pending = fixture.governance.listApprovals(
                new GovernanceApplicationApi.ListApprovalsQuery(
                        "tenant", "user", ApprovalState.PENDING, 10)).getFirst();
        var approved = fixture.governance.decide(new GovernanceApplicationApi.DecisionCommand(
                "tenant", "user", pending.id(), ApprovalState.APPROVED, "safe"));
        assertThat(approved.state()).isEqualTo(ApprovalState.APPROVED);

        var allowed = fixture.service.execute(fixture.action(
                run.agentRunId(), "call-approval", "approved-content", approved.id()));
        assertThat(allowed.status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.coding.executions).isEqualTo(1);
        assertThat(fixture.governance.getApproval(new GovernanceApplicationApi.ApprovalQuery(
                "tenant", "user", approved.id())).state()).isEqualTo(ApprovalState.CONSUMED);

        // Terminal Tool ledger replay does not invoke the side effect or consume a new approval.
        assertThat(fixture.service.execute(action).status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.coding.executions).isEqualTo(1);

        assertThatThrownBy(() -> fixture.service.execute(fixture.action(
                run.agentRunId(), "call-2", "different-operation", approved.id())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("GOVERNANCE_APPROVAL_INVALID"));
        assertThat(fixture.coding.executions).isEqualTo(1);
        assertThat(fixture.ledger.findByRunId(run.agentRunId())).hasSize(1);
    }

    @Test
    void commandRunsInSandboxAndTerminalReplayCreatesNoSecondContainer() {
        Fixture fixture = new Fixture();
        var run = fixture.start();
        var command = fixture.command(run.agentRunId(), "call-command", null);

        var result = fixture.service.execute(command);
        var replay = fixture.service.execute(command);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.output()).contains("sandbox-ok");
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.sandbox.executions).isEqualTo(1);
        assertThat(fixture.coding.executions).isZero();
        assertThat(fixture.artifactApi.byRun(run.agentRunId()))
                .extracting(value -> value.type())
                .contains(ArtifactType.TEST_REPORT);
    }

    @Test
    void unavailableSandboxLeavesCommandOutcomeUnknown() {
        Fixture fixture = new Fixture();
        fixture.sandbox.unavailable = true;
        var run = fixture.start();

        assertThatThrownBy(() -> fixture.service.execute(
                fixture.command(run.agentRunId(), "call-unknown", null)))
                .isInstanceOf(SandboxExecutionUnavailableException.class);
        assertThat(fixture.ledger.findByRunId(run.agentRunId()).getFirst().status().name())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void commandApprovalIsConsumedBeforeSandboxClaim() {
        Fixture fixture = new Fixture();
        fixture.governance.updatePolicy(new GovernanceApplicationApi.UpdatePolicyCommand(
                "tenant", "user", false, true, false,
                false, false, false, 3_600, 0));
        var run = fixture.start();
        var command = fixture.command(run.agentRunId(), "call-command-approval", null);

        assertThatThrownBy(() -> fixture.service.execute(command))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("GOVERNANCE_APPROVAL_REQUIRED"));
        assertThat(fixture.sandbox.executions).isZero();
        assertThat(fixture.ledger.findByRunId(run.agentRunId())).isEmpty();

        var pending = fixture.governance.listApprovals(
                new GovernanceApplicationApi.ListApprovalsQuery(
                        "tenant", "user", ApprovalState.PENDING, 10)).getFirst();
        var approved = fixture.governance.decide(new GovernanceApplicationApi.DecisionCommand(
                "tenant", "user", pending.id(), ApprovalState.APPROVED, "sandboxed"));
        var result = fixture.service.execute(fixture.command(
                run.agentRunId(), "call-command-approval", approved.id()));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(fixture.sandbox.executions).isEqualTo(1);
        assertThat(fixture.governance.getApproval(new GovernanceApplicationApi.ApprovalQuery(
                "tenant", "user", approved.id())).state()).isEqualTo(ApprovalState.CONSUMED);
    }

    @Test
    void explicitSandboxTimeoutIsATerminalReplayableResult() {
        Fixture fixture = new Fixture();
        fixture.sandbox.status = "TIMED_OUT";
        var run = fixture.start();

        var result = fixture.service.execute(
                fixture.command(run.agentRunId(), "call-timeout", null));

        assertThat(result.status()).isEqualTo("TIMED_OUT");
        assertThat(fixture.ledger.findByRunId(run.agentRunId()).getFirst().status().name())
                .isEqualTo("TIMED_OUT");
        assertThat(fixture.service.execute(
                fixture.command(run.agentRunId(), "call-timeout", null)).status())
                .isEqualTo("TIMED_OUT");
        assertThat(fixture.sandbox.executions).isEqualTo(1);
    }

    @Test
    void codingRunPinsExactDirectoryAndWorkspaceAndRejectsCrossDirectoryStart() {
        Fixture fixture = new Fixture();
        var run = fixture.start();
        var persisted = fixture.runtime.findRun(run.agentRunId()).orElseThrow();
        assertThat(persisted.projectDirectoryId()).isEqualTo("directory");
        assertThat(persisted.workspaceId()).isEqualTo("workspace");

        when(fixture.workspaces.get(any())).thenReturn(fixture.workspace("other-directory"));
        assertThatThrownBy(fixture::start)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "CODING_PROJECT_DIRECTORY_MISMATCH"));
    }

    private static final class Fixture {
        private final Instant now = Instant.parse("2026-08-23T10:00:00Z");
        private final AtomicInteger sequence = new AtomicInteger();
        private final IdGenerator ids = () -> "id-" + sequence.incrementAndGet();
        private final ToolExecutionLedgerService ledger;
        private final ArtifactApplicationService artifactApi;
        private final GovernanceApplicationService governance;
        private final FakeCoding coding = new FakeCoding();
        private final FakeSandbox sandbox = new FakeSandbox();
        private final CodingRuntimeApplicationService service;
        private final RuntimeApplicationService runtime;
        private final WorkspaceApplicationApi workspaces;

        private Fixture() {
            ledger = new ToolExecutionLedgerService(
                    new InMemoryToolExecutionLedgerRepository(() -> now), ids, () -> now);
            TaskExecutionApplicationApi execution = mock(TaskExecutionApplicationApi.class);
            when(execution.resolve(any())).thenReturn(new TaskExecutionReferenceView(
                    "project", "root", "task", "plan", "plan-step",
                    TaskPlanStatus.ACTIVE, PlanStepState.IN_PROGRESS, TaskState.IN_PROGRESS));
            when(execution.transition(any())).thenReturn(new TaskExecutionReferenceView(
                    "project", "root", "task", "plan", "plan-step",
                    TaskPlanStatus.ACTIVE, PlanStepState.COMPLETED, TaskState.COMPLETED));
            runtime = new RuntimeApplicationService(
                    new InMemoryRuntimeLedgerRepository(), ledger, new ObjectMapper(),
                    ids, () -> now, null, null, execution);

            workspaces = mock(WorkspaceApplicationApi.class);
            when(workspaces.get(any())).thenReturn(workspace("directory"));
            TaskApplicationApi taskApi = mock(TaskApplicationApi.class);
            when(taskApi.getTask(any())).thenReturn(new TaskView(
                    "task", "project", null, "t", "g", null, List.of(),
                    List.of("tests pass"), null, TaskState.IN_PROGRESS, now, now));
            artifactApi = new ArtifactApplicationService(
                    new InMemoryArtifactRepository(), ids, () -> now);
            IdentityApplicationApi identity = mock(IdentityApplicationApi.class);
            when(identity.findTenantMembership("tenant", "user"))
                    .thenReturn(Optional.of(new TenantMembershipView(
                            "tenant", "user", TenantRole.OWNER,
                            TenantMembershipStatus.ACTIVE, now, now)));
            governance = new GovernanceApplicationService(
                    new InMemoryGovernanceRepository(), identity, ids, () -> now, (t, u, w) -> {});
            ConversationApplicationApi conversations = mock(ConversationApplicationApi.class);
            when(conversations.find("conv")).thenReturn(Optional.of(new ConversationView(
                    "conv", "project", "directory", null, "task", "tenant", "user",
                    "agent", "Coding", ConversationStatus.ACTIVE, now, now)));
            service = new CodingRuntimeApplicationService(
                    runtime, coding, workspaces, taskApi, ledger, artifactApi,
                    governance, sandbox, new ObjectMapper(), () -> now, conversations);
        }

        private WorkspaceApplicationApi.WorkspaceView workspace(String directoryId) {
            return new WorkspaceApplicationApi.WorkspaceView(
                    "workspace", "project", directoryId, "task", "source", null, "primary",
                    WorkspaceMode.MANAGED_GIT, "key", "main", "branch", "managed:workspace",
                    "a".repeat(40), true, WorkspaceState.READY, null, 1, now, now);
        }

        private CodingRuntimeApplicationApi.CodingRunView start() {
            return service.start(new CodingRuntimeApplicationApi.StartCommand(
                    "tenant", "user", "agent", "version", "conv", "project",
                    "task", "plan", "plan-step", "workspace"));
        }

        private CodingRuntimeApplicationApi.ActionCommand action(
                String runId, String toolCallId, String content, String approvalId) {
            return new CodingRuntimeApplicationApi.ActionCommand(
                    "user", runId, "workspace", toolCallId,
                    WorkspaceCodingGateway.Type.WRITE_FILE, "README.md", content,
                    null, List.of(), 30, approvalId);
        }

        private CodingRuntimeApplicationApi.ActionCommand command(
                String runId, String toolCallId, String approvalId) {
            return new CodingRuntimeApplicationApi.ActionCommand(
                    "user", runId, "workspace", toolCallId,
                    WorkspaceCodingGateway.Type.RUN_COMMAND, null, null,
                    "mvn", List.of("test"), 30, approvalId);
        }
    }

    private static final class FakeCoding implements CodingWorkspaceApplicationApi {
        private boolean changed;
        private int executions;

        @Override
        public Result execute(Command command) {
            changed = true;
            executions++;
            return new Result(0, "ok", "", List.of("README.md"));
        }

        @Override
        public Snapshot snapshot(Query query) {
            return new Snapshot(
                    "a".repeat(40),
                    changed ? "diff --git a/README.md b/README.md\n+done" : "",
                    " M README.md",
                    List.of("README.md"));
        }
    }

    private static final class FakeSandbox implements SandboxComputeApplicationApi {
        private int executions;
        private boolean unavailable;
        private String status = "SUCCEEDED";

        @Override
        public ComputeResult execute(ComputeCommand command) {
            executions++;
            if (unavailable) {
                throw new SandboxExecutionUnavailableException("worker unavailable");
            }
            return new ComputeResult(
                    status, "SUCCEEDED".equals(status) ? 0 : -1,
                    "sandbox-ok\n", "", "TIMED_OUT".equals(status),
                    10, 11, "SUCCEEDED".equals(status) ? null : "SANDBOX_TIMEOUT");
        }
    }
}
