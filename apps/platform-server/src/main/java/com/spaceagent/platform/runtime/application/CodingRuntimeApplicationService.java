package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.api.GovernanceApprovalRequiredException;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.project.api.CodingWorkspaceApplicationApi;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.WorkspaceCodingGateway;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.CompleteRunStepCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.FailRunStepCommand;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.CompleteToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CodingRuntimeApplicationService implements CodingRuntimeApplicationApi {

    private final RuntimeApplicationApi runtime;
    private final CodingWorkspaceApplicationApi coding;
    private final WorkspaceApplicationApi workspaces;
    private final TaskApplicationApi tasks;
    private final ToolExecutionLedgerApplicationApi ledger;
    private final ArtifactApplicationApi artifacts;
    private final GovernanceApplicationApi governance;
    private final SandboxComputeApplicationApi sandbox;
    private final ObjectMapper json;
    private final ConversationApplicationApi conversations;

    public CodingRuntimeApplicationService(
            RuntimeApplicationApi runtime,
            CodingWorkspaceApplicationApi coding,
            WorkspaceApplicationApi workspaces,
            TaskApplicationApi tasks,
            ToolExecutionLedgerApplicationApi ledger,
            ArtifactApplicationApi artifacts,
            GovernanceApplicationApi governance,
            SandboxComputeApplicationApi sandbox,
            ObjectMapper json,
            TimeProvider ignoredTimeProvider) {
        this(runtime, coding, workspaces, tasks, ledger, artifacts, governance, sandbox,
                json, ignoredTimeProvider, null);
    }

    @Autowired
    public CodingRuntimeApplicationService(
            RuntimeApplicationApi runtime,
            CodingWorkspaceApplicationApi coding,
            WorkspaceApplicationApi workspaces,
            TaskApplicationApi tasks,
            ToolExecutionLedgerApplicationApi ledger,
            ArtifactApplicationApi artifacts,
            GovernanceApplicationApi governance,
            SandboxComputeApplicationApi sandbox,
            ObjectMapper json,
            TimeProvider ignoredTimeProvider,
            ConversationApplicationApi conversations) {
        this.runtime = runtime;
        this.coding = coding;
        this.workspaces = workspaces;
        this.tasks = tasks;
        this.ledger = ledger;
        this.artifacts = artifacts;
        this.governance = governance;
        this.sandbox = sandbox;
        this.json = json;
        this.conversations = conversations;
    }

    @Override
    public CodingRunView start(StartCommand command) {
        var workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                command.tenantId(), command.userId(), command.projectId(), command.workspaceId()));
        if (!workspace.taskId().equals(command.taskId())
                || workspace.state() != com.spaceagent.platform.project.domain.WorkspaceState.READY
                || workspace.mode()
                    != com.spaceagent.platform.project.domain.WorkspaceMode.MANAGED_GIT) {
            throw conflict("Workspace is not READY for managed coding", "CODING_WORKSPACE_INVALID");
        }
        if (conversations != null) {
            var conversation = conversations.find(command.conversationId())
                    .filter(value -> command.tenantId().equals(value.tenantId()))
                    .filter(value -> command.userId().equals(value.userId()))
                    .filter(value -> command.projectId().equals(value.projectId()))
                    .orElseThrow(() -> conflict(
                            "Conversation is not in the Coding Project",
                            "CODING_CONVERSATION_SCOPE_MISMATCH"));
            if (workspace.projectDirectoryId() == null
                    || !workspace.projectDirectoryId().equals(conversation.projectDirectoryId())) {
                throw conflict(
                        "Conversation and Workspace must belong to the same ProjectDirectory",
                        "CODING_PROJECT_DIRECTORY_MISMATCH");
            }
        }
        AgentRunView run = runtime.startRun(new StartAgentRunCommand(
                command.tenantId(), command.userId(), command.agentId(), command.configurationSnapshotId(),
                command.conversationId(), command.projectId(), workspace.projectDirectoryId(),
                workspace.id(), command.taskId(),
                command.taskPlanId(), command.planStepId()));
        runtime.markRunInProgress(run.id());
        checkpoint(run.id(), "coding-start", Map.of("workspaceId", command.workspaceId()));
        return new CodingRunView(run.id(), command.workspaceId(), "IN_PROGRESS", List.of());
    }

    @Override
    public CodingActionView execute(ActionCommand command) {
        AgentRunView run = requireRun(command.userId(), command.agentRunId());
        if (!run.taskId().equals(workspace(command.userId(), run, command.workspaceId()).taskId())) {
            throw conflict("Workspace Task mismatch", "CODING_WORKSPACE_MISMATCH");
        }
        String arguments = encode(command);
        String inputHash = "sha256:" + hash(arguments);
        ToolExecutionLedgerView existing = existingExecution(run.id(), command.toolCallId());
        if (existing != null) {
            return replayOrRejectExisting(run, command, inputHash, existing);
        }

        GovernanceApplicationApi.AuthorizationView authorization = governance.authorize(
                new GovernanceApplicationApi.AuthorizeCommand(
                        run.tenantId(), command.userId(), governanceAction(command.type()),
                        "WORKSPACE", command.workspaceId(), operationHash(run, command, arguments),
                        approvalSummary(command), command.approvalId()));
        requireAuthorization(authorization);

        RunStepView step = runtime.startStep(new StartRunStepCommand(
                run.id(), "coding-" + command.type().name().toLowerCase()));
        checkpoint(run.id(), "before-coding-action", Map.of(
                "toolCallId", command.toolCallId(), "type", command.type().name(),
                "approvalId", authorization.approval() == null
                        ? "policy-not-required" : authorization.approval().id()));
        var claim = ledger.claim(new ClaimToolExecutionCommand(
                run.id(), step.id(), "workspace-" + command.type().name().toLowerCase(),
                command.toolCallId(), command.toolCallId(), arguments, inputHash,
                Math.max(60, command.timeoutSeconds() + 30L)));
        if (claim.type() == ToolExecutionClaimDecisionType.REPLAY) {
            runtime.completeStep(new CompleteRunStepCommand(run.id(), step.id()));
            return replay(run, command.toolCallId(), claim.ledger());
        }
        if (claim.type() != ToolExecutionClaimDecisionType.CLAIMED) {
            throw conflict(
                    "Coding action cannot be claimed: " + claim.type(),
                    "CODING_ACTION_CLAIM_CONFLICT");
        }

        try {
            CodingExecution result = executeEffect(run, command);
            ToolExecutionStatus status = result.status();
            String output = result.stdout() + result.stderr();
            ledger.complete(new CompleteToolExecutionCommand(
                    run.id(), command.toolCallId(), claim.claimToken(), claim.revision(), status,
                    output, null, status == ToolExecutionStatus.SUCCEEDED
                            ? null : result.error()));
            String artifactId = command.type() == WorkspaceCodingGateway.Type.RUN_COMMAND
                    ? createCommandArtifact(run, command, result.exitCode(), output)
                    : null;
            if (status == ToolExecutionStatus.SUCCEEDED) {
                runtime.completeStep(new CompleteRunStepCommand(run.id(), step.id()));
            } else {
                runtime.failStep(new FailRunStepCommand(run.id(), step.id()));
            }
            checkpoint(run.id(), "after-coding-action", Map.of(
                    "toolCallId", command.toolCallId(), "status", status.name()));
            return new CodingActionView(
                    run.id(), command.toolCallId(), status.name(), result.exitCode(), output,
                    result.changedFiles(), artifactId);
        } catch (RuntimeException error) {
            ledger.markUnknown(new MarkToolExecutionUnknownCommand(
                    run.id(), command.toolCallId(), claim.claimToken(), claim.revision(),
                    "coding gateway outcome unknown"));
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private CodingExecution executeEffect(
            AgentRunView run,
            ActionCommand command) {
        if (command.type() == WorkspaceCodingGateway.Type.RUN_COMMAND) {
            var result = sandbox.execute(new SandboxComputeApplicationApi.ComputeCommand(
                    run.id(), command.toolCallId(),
                    "workspaces/" + command.workspaceId(), run.taskId(),
                    "coding-run-command", command.executable(), command.arguments(),
                    command.timeoutSeconds()));
            var snapshot = coding.snapshot(new CodingWorkspaceApplicationApi.Query(
                    run.tenantId(), command.userId(), run.projectId(), run.taskId(),
                    command.workspaceId(), run.id(), command.toolCallId() + ":snapshot"));
            ToolExecutionStatus status = switch (result.status()) {
                case "SUCCEEDED" -> result.exitStatus() == 0
                        ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.FAILED;
                case "TIMED_OUT" -> ToolExecutionStatus.TIMED_OUT;
                default -> ToolExecutionStatus.FAILED;
            };
            return new CodingExecution(
                    status, result.exitStatus(), result.stdout(), result.stderr(),
                    snapshot.changedFiles(), result.error() == null
                    ? "SANDBOX_FAILED" : result.error());
        }
        var result = coding.execute(new CodingWorkspaceApplicationApi.Command(
                run.tenantId(), command.userId(), run.projectId(), run.taskId(),
                command.workspaceId(), command.type(), command.relativePath(), command.content(),
                command.executable(), command.arguments(), command.timeoutSeconds(),
                run.id(), command.toolCallId()));
        ToolExecutionStatus status = result.exitCode() == 0
                ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.FAILED;
        return new CodingExecution(
                status, result.exitCode(), result.stdout(), result.stderr(),
                result.changedFiles(), status == ToolExecutionStatus.SUCCEEDED
                ? null : "exit=" + result.exitCode());
    }

    @Override
    public CodingActionView evidence(EvidenceCommand command) {
        AgentRunView run = requireRun(command.userId(), command.agentRunId());
        workspace(command.userId(), run, command.workspaceId());
        try {
            String contentHash = "sha256:" + hash(
                    command.criterion() + command.passed() + command.details());
            var existing = artifacts.byRun(run.id()).stream()
                    .filter(value -> value.type() == ArtifactType.ACCEPTANCE_EVIDENCE)
                    .filter(value -> contentHash.equals(value.contentHash()))
                    .findFirst().orElse(null);
            if (existing != null) {
                return new CodingActionView(run.id(), "evidence", "RECORDED", 0,
                        command.details(), List.of(), existing.id());
            }
            var artifact = artifacts.create(new ArtifactApplicationApi.CreateCommand(
                    run.tenantId(), run.projectId(), run.taskId(), run.id(), command.workspaceId(),
                    ArtifactType.ACCEPTANCE_EVIDENCE, "acceptance", null,
                    contentHash,
                    command.details(), json.writeValueAsString(Map.of(
                            "criterion", command.criterion(), "passed", command.passed()))));
            return new CodingActionView(
                    run.id(), "evidence", "RECORDED", 0, command.details(),
                    List.of(), artifact.id());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public CodingRunView prepareCompletion(FinalizeCommand command) {
        AgentRunView run = requireRun(command.userId(), command.agentRunId());
        if (command.commitMessage() == null || command.commitMessage().isBlank()
                || command.commitMessage().trim().length() > 500) {
            throw new IllegalArgumentException(
                    "commitMessage is required and must not exceed 500 characters");
        }
        String commitMessage = command.commitMessage().trim();
        WorkspaceApplicationApi.WorkspaceView workspace =
                workspace(command.userId(), run, command.workspaceId());
        var task = tasks.getTask(new GetTaskQuery(
                run.tenantId(), command.userId(), run.projectId(), run.taskId()));
        List<ArtifactApplicationApi.ArtifactView> existing = artifacts.byRun(run.id());
        Map<String, Boolean> evidence = new HashMap<>();
        for (var artifact : existing) {
            if (artifact.type() == ArtifactType.ACCEPTANCE_EVIDENCE) {
                try {
                    var node = json.readTree(artifact.metadataJson());
                    evidence.put(node.path("criterion").asText(), node.path("passed").asBoolean());
                } catch (Exception ignored) {
                    // Invalid legacy evidence cannot satisfy a criterion.
                }
            }
        }
        for (String criterion : task.acceptanceCriteria()) {
            if (!Boolean.TRUE.equals(evidence.get(criterion))) {
                throw conflict(
                        "Missing PASS evidence: " + criterion,
                        "CODING_ACCEPTANCE_INCOMPLETE");
            }
        }
        var snapshot = coding.snapshot(new CodingWorkspaceApplicationApi.Query(
                run.tenantId(), command.userId(), run.projectId(), run.taskId(),
                command.workspaceId(), run.id(), "finalize:snapshot"));
        if (snapshot.patch() == null || snapshot.patch().isBlank()) {
            throw conflict("Workspace patch is empty", "CODING_PATCH_EMPTY");
        }
        if (snapshot.truncated()) {
            throw conflict("Workspace patch exceeds the reviewable limit", "CODING_PATCH_TOO_LARGE");
        }
        String patchHash = "sha256:" + hash(snapshot.patch());
        List<ArtifactApplicationApi.ArtifactView> current = artifacts.byRun(run.id());
        var patch = current.stream().filter(value -> value.type() == ArtifactType.PATCH)
                .filter(value -> patchHash.equals(value.contentHash())).findFirst().orElseGet(() ->
                        artifacts.create(new ArtifactApplicationApi.CreateCommand(
                                run.tenantId(), run.projectId(), run.taskId(), run.id(),
                                command.workspaceId(), ArtifactType.PATCH, "workspace.patch",
                                "postgres:inline:" + patchHash, patchHash,
                                "Changed " + snapshot.changedFiles().size() + " files",
                                safeJson(Map.of("patch", snapshot.patch(),
                                        "changedFiles", snapshot.changedFiles(),
                                        "head", snapshot.headCommit(), "sourceRepositoryId",
                                        workspace.sourceRepositoryId())))));
        String proposalMetadata = safeJson(Map.of(
                "baseHead", snapshot.headCommit(), "patchArtifactId", patch.id()));
        var commit = artifacts.byRun(run.id()).stream()
                .filter(value -> value.type() == ArtifactType.COMMIT_PROPOSAL)
                .filter(value -> patchHash.equals(value.contentHash()))
                .filter(value -> commitMessage.equals(value.summary()))
                .filter(value -> proposalMetadata.equals(value.metadataJson()))
                .findFirst().orElseGet(() -> artifacts.create(new ArtifactApplicationApi.CreateCommand(
                        run.tenantId(), run.projectId(), run.taskId(), run.id(), command.workspaceId(),
                        ArtifactType.COMMIT_PROPOSAL, "commit-proposal", null, patchHash,
                        commitMessage, proposalMetadata)));
        checkpoint(run.id(), "coding-accepted", Map.of(
                "patchArtifactId", patch.id(), "commitArtifactId", commit.id()));
        return new CodingRunView(
                run.id(), command.workspaceId(), "AWAITING_REVIEW",
                artifacts.byRun(run.id()).stream()
                        .map(ArtifactApplicationApi.ArtifactView::id).toList());
    }

    @Override
    public CodingRunView finalizeRun(FinalizeCommand command) {
        CodingRunView prepared = prepareCompletion(command);
        runtime.complete(new CompleteAgentRunCommand(command.agentRunId()));
        return new CodingRunView(prepared.agentRunId(), prepared.workspaceId(), "COMPLETED",
                prepared.artifactIds());
    }

    @Override
    public List<ArtifactApplicationApi.ArtifactView> artifacts(String userId, String runId) {
        requireRun(userId, runId);
        return artifacts.byRun(runId);
    }

    private CodingActionView replayOrRejectExisting(
            AgentRunView run,
            ActionCommand command,
            String inputHash,
            ToolExecutionLedgerView existing) {
        if (!inputHash.equals(existing.inputHash())) {
            throw conflict("Coding toolCallId input conflict", "CODING_ACTION_CLAIM_CONFLICT");
        }
        if (isTerminal(existing.status())) {
            return replay(run, command.toolCallId(), existing);
        }
        throw conflict(
                "Coding action already has non-terminal status: " + existing.status(),
                existing.status() == ToolExecutionStatus.UNKNOWN
                        ? "CODING_ACTION_OUTCOME_UNKNOWN" : "CODING_ACTION_CLAIM_CONFLICT");
    }

    private CodingActionView replay(
            AgentRunView run, String toolCallId, ToolExecutionLedgerView existing) {
        return new CodingActionView(
                run.id(), toolCallId, existing.status().name(),
                existing.status() == ToolExecutionStatus.SUCCEEDED ? 0 : 1,
                existing.result(), List.of(), null);
    }

    private void requireAuthorization(GovernanceApplicationApi.AuthorizationView authorization) {
        if (authorization.status()
                == GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED) {
            String id = authorization.approval() == null
                    ? "unknown" : authorization.approval().id();
            throw new GovernanceApprovalRequiredException(id);
        }
        if (!authorization.allowed()) {
            throw conflict(
                    "Approval is missing, expired, consumed, or does not match this operation",
                    "GOVERNANCE_APPROVAL_INVALID");
        }
    }

    private String createCommandArtifact(
            AgentRunView run, ActionCommand command, int exitCode, String output) throws Exception {
        return artifacts.create(new ArtifactApplicationApi.CreateCommand(
                run.tenantId(), run.projectId(), run.taskId(), run.id(), command.workspaceId(),
                ArtifactType.TEST_REPORT, "check-" + command.toolCallId(),
                "ledger:" + command.toolCallId(), "sha256:" + hash(output),
                "exit=" + exitCode, json.writeValueAsString(Map.of(
                        "exitCode", exitCode, "executable", command.executable())))).id();
    }

    private AgentRunView requireRun(String userId, String id) {
        return runtime.findRun(id)
                .filter(run -> run.ownerId().equals(userId))
                .orElseThrow(() -> new BusinessException(
                        "AgentRun not found", HttpStatus.NOT_FOUND));
    }

    private WorkspaceApplicationApi.WorkspaceView workspace(
            String userId, AgentRunView run, String id) {
        if (run.workspaceId() != null && !run.workspaceId().equals(id)) {
            throw conflict("Coding Run is bound to another Workspace",
                    "CODING_WORKSPACE_BINDING_MISMATCH");
        }
        WorkspaceApplicationApi.WorkspaceView workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                run.tenantId(), userId, run.projectId(), id));
        if (run.projectDirectoryId() != null
                && !run.projectDirectoryId().equals(workspace.projectDirectoryId())) {
            throw conflict("Coding Run is bound to another ProjectDirectory",
                    "CODING_PROJECT_DIRECTORY_MISMATCH");
        }
        return workspace;
    }

    private ToolExecutionLedgerView existingExecution(String runId, String toolCallId) {
        return ledger.findByRunId(runId).stream()
                .filter(value -> value.toolCallId().equals(toolCallId))
                .findFirst().orElse(null);
    }

    private void checkpoint(String runId, String phase, Map<String, ?> data) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("phase", phase);
            snapshot.putAll(data);
            runtime.createCheckpoint(new CreateCheckpointCommand(
                    runId, json.writeValueAsString(snapshot)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String encode(ActionCommand command) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", command.type().name());
        value.put("path", command.relativePath() == null ? "" : command.relativePath());
        value.put("content", command.content() == null ? "" : command.content());
        value.put("executable", command.executable() == null ? "" : command.executable());
        value.put("arguments", command.arguments() == null ? List.of() : command.arguments());
        return safeJson(value);
    }

    private String operationHash(AgentRunView run, ActionCommand command, String arguments) {
        Map<String, Object> exactOperation = new LinkedHashMap<>();
        exactOperation.put("agentRunId", run.id());
        exactOperation.put("workspaceId", command.workspaceId());
        exactOperation.put("toolCallId", command.toolCallId());
        exactOperation.put("operation", arguments);
        return "sha256:" + hash(safeJson(exactOperation));
    }

    private static GovernanceActionType governanceAction(WorkspaceCodingGateway.Type type) {
        return type == WorkspaceCodingGateway.Type.RUN_COMMAND
                ? GovernanceActionType.COMMAND_EXECUTION
                : GovernanceActionType.CODING_FILE_MUTATION;
    }

    private static String approvalSummary(ActionCommand command) {
        return switch (command.type()) {
            case WRITE_FILE -> "Write file " + command.relativePath();
            case DELETE_FILE -> "Delete file " + command.relativePath();
            case RUN_COMMAND -> "Run " + command.executable() + " with "
                    + (command.arguments() == null ? 0 : command.arguments().size()) + " arguments";
        };
    }

    private String safeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean isTerminal(ToolExecutionStatus status) {
        return status == ToolExecutionStatus.SUCCEEDED
                || status == ToolExecutionStatus.FAILED
                || status == ToolExecutionStatus.TIMED_OUT
                || status == ToolExecutionStatus.CANCELLED;
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private record CodingExecution(
            ToolExecutionStatus status,
            int exitCode,
            String stdout,
            String stderr,
            List<String> changedFiles,
            String error) {
    }
}
