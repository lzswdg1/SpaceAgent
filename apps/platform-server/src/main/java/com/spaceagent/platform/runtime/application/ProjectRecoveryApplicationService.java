package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotView;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerView;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.project.api.GetTaskPlanQuery;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.PlanStepView;
import com.spaceagent.platform.project.api.ProjectBlueprintApplicationApi;
import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.ProjectBlueprintStatus;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CheckpointView;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.ProjectExecutionContextSnapshot;
import com.spaceagent.platform.runtime.domain.ProjectExecutionContextSnapshotRepository;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Builds immutable recovery evidence without moving lifecycle authority out of owner modules. */
@Service
public class ProjectRecoveryApplicationService implements ProjectRecoveryApplicationApi {

    private static final int MAX_PATCH_BYTES = 1_000_000;
    private static final int MAX_CHANGED_FILES = 100;
    private static final int MAX_EVIDENCE = 500;
    private static final int MAX_PAYLOAD_BYTES = 4_000_000;

    private final ProjectExecutionContextSnapshotRepository snapshots;
    private final RuntimeApplicationApi runtime;
    private final ProjectDirectoryApplicationApi directories;
    private final WorkspaceApplicationApi workspaces;
    private final TaskApplicationApi tasks;
    private final TaskPlanApplicationApi plans;
    private final ProjectBlueprintApplicationApi blueprints;
    private final ConversationApplicationApi conversations;
    private final ConversationContextSnapshotApplicationApi conversationSnapshots;
    private final ArtifactApplicationApi artifacts;
    private final ModelCallLedgerApplicationApi modelCalls;
    private final ToolExecutionLedgerApplicationApi toolExecutions;
    private final GovernanceApplicationApi governance;
    private final SandboxComputeApplicationApi sandbox;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;

    public ProjectRecoveryApplicationService(
            ProjectExecutionContextSnapshotRepository snapshots,
            RuntimeApplicationApi runtime,
            ProjectDirectoryApplicationApi directories,
            WorkspaceApplicationApi workspaces,
            TaskApplicationApi tasks,
            TaskPlanApplicationApi plans,
            ProjectBlueprintApplicationApi blueprints,
            ConversationApplicationApi conversations,
            ConversationContextSnapshotApplicationApi conversationSnapshots,
            ArtifactApplicationApi artifacts,
            ModelCallLedgerApplicationApi modelCalls,
            ToolExecutionLedgerApplicationApi toolExecutions,
            GovernanceApplicationApi governance,
            SandboxComputeApplicationApi sandbox,
            ObjectMapper json,
            IdGenerator ids,
            TimeProvider time) {
        this.snapshots = snapshots;
        this.runtime = runtime;
        this.directories = directories;
        this.workspaces = workspaces;
        this.tasks = tasks;
        this.plans = plans;
        this.blueprints = blueprints;
        this.conversations = conversations;
        this.conversationSnapshots = conversationSnapshots;
        this.artifacts = artifacts;
        this.modelCalls = modelCalls;
        this.toolExecutions = toolExecutions;
        this.governance = governance;
        this.sandbox = sandbox;
        this.json = json;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public CodingRecoveryPackageView capture(CaptureCommand command) {
        String idempotencyHash = sha256(idempotencyKey(command.idempotencyKey()));
        String inputHash = sha256(String.join("\n",
                required(command.tenantId(), "tenantId"),
                required(command.userId(), "userId"),
                required(command.projectId(), "projectId"),
                required(command.agentRunId(), "agentRunId")));
        AgentRunView run = requireStrictRun(
                command.tenantId(), command.userId(), command.projectId(), command.agentRunId());
        requireDirectoryAccess(run, command.userId());
        ProjectExecutionContextSnapshot existing = snapshots.findByIdempotencyHash(
                command.tenantId(), command.userId(), idempotencyHash).orElse(null);
        if (existing != null) return replay(existing, inputHash);

        RecoveryContext context = compose(command, run);
        verifyCaptureFence(run, context.workspace());
        String payload = write(context);
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw tooLarge("Recovery package exceeds the durable payload limit");
        }
        Instant now = time.now();
        ProjectExecutionContextSnapshot created = new ProjectExecutionContextSnapshot(
                ids.nextId(), run.tenantId(), run.ownerId(), run.projectId(),
                run.projectDirectoryId(), run.conversationId(), run.taskId(), run.taskPlanId(),
                run.planStepId(), run.id(), run.configurationSnapshotId(), run.workspaceId(),
                context.blueprint() == null ? null : context.blueprint().id(),
                context.blueprint() == null ? null : context.blueprint().version(),
                context.conversation().snapshotId(), context.conversation().snapshotVersion(),
                context.runtime().checkpointId(),
                idempotencyHash, inputHash, sha256(payload), payload, now);
        try {
            snapshots.save(created);
            return view(created);
        } catch (DataIntegrityViolationException | IllegalStateException error) {
            ProjectExecutionContextSnapshot winner = snapshots.findByIdempotencyHash(
                    command.tenantId(), command.userId(), idempotencyHash).orElse(null);
            if (winner != null) return replay(winner, inputHash);
            throw conflict("Recovery snapshot persistence conflict",
                    "CODING_RECOVERY_PERSISTENCE_CONFLICT");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CodingRecoveryPackageView get(Query query) {
        AgentRunView run = requireStrictRun(
                query.tenantId(), query.userId(), query.projectId(), query.agentRunId());
        requireDirectoryAccess(run, query.userId());
        return view(snapshots.findById(uuid(query.snapshotId()))
                .filter(value -> value.agentRunId().equals(run.id()))
                .filter(value -> value.ownerId().equals(run.ownerId()))
                .orElseThrow(ProjectRecoveryApplicationService::notFound));
    }

    @Override
    @Transactional(readOnly = true)
    public CodingRecoveryPackageView latest(LatestQuery query) {
        AgentRunView run = requireStrictRun(
                query.tenantId(), query.userId(), query.projectId(), query.agentRunId());
        requireDirectoryAccess(run, query.userId());
        return view(snapshots.findLatestByRunId(run.id())
                .orElseThrow(ProjectRecoveryApplicationService::notFound));
    }

    private RecoveryContext compose(CaptureCommand command, AgentRunView run) {
        var directory = requireDirectoryAccess(run, command.userId());
        var workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                run.tenantId(), run.ownerId(), run.projectId(), run.workspaceId()));
        if (!run.projectDirectoryId().equals(workspace.projectDirectoryId())
                || !run.taskId().equals(workspace.taskId())
                || workspace.mode() != WorkspaceMode.MANAGED_GIT
                || (workspace.state() != WorkspaceState.READY
                    && workspace.state() != WorkspaceState.DIRTY)) {
            throw conflict("Coding Workspace is not recoverable",
                    "CODING_RECOVERY_WORKSPACE_INVALID");
        }
        var conversation = conversations.find(run.conversationId())
                .filter(value -> run.tenantId().equals(value.tenantId()))
                .filter(value -> run.ownerId().equals(value.userId()))
                .filter(value -> run.projectId().equals(value.projectId()))
                .filter(value -> run.projectDirectoryId().equals(value.projectDirectoryId()))
                .orElseThrow(() -> conflict("Conversation scope changed",
                        "CODING_RECOVERY_CONVERSATION_SCOPE_MISMATCH"));

        TaskView task = tasks.getTask(new GetTaskQuery(
                run.tenantId(), run.ownerId(), run.projectId(), run.taskId()));
        if (task.parentTaskId() == null) {
            throw conflict("Coding Run Task is not a TaskPlan child",
                    "CODING_RECOVERY_TASK_SCOPE_MISMATCH");
        }
        TaskView root = tasks.getTask(new GetTaskQuery(
                run.tenantId(), run.ownerId(), run.projectId(), task.parentTaskId()));
        TaskPlanView plan = plans.getPlan(new GetTaskPlanQuery(
                run.tenantId(), run.ownerId(), run.projectId(), root.id(), run.taskPlanId()));
        PlanStepView step = plan.steps().stream()
                .filter(value -> value.id().equals(run.planStepId()))
                .filter(value -> value.childTaskId().equals(run.taskId()))
                .findFirst()
                .orElseThrow(() -> conflict("Coding Run PlanStep scope changed",
                        "CODING_RECOVERY_PLAN_STEP_SCOPE_MISMATCH"));

        GitCapture git = captureGit(run);
        ProjectRecoveryApplicationApi.BlueprintContext blueprint = blueprints.list(
                        new ProjectBlueprintApplicationApi.ListQuery(
                                run.tenantId(), run.ownerId(), run.projectId())).stream()
                .filter(value -> value.status() == ProjectBlueprintStatus.CONFIRMED)
                .max(Comparator.comparingInt(ProjectBlueprintApplicationApi.BlueprintView::versionNumber))
                .map(this::blueprint)
                .orElse(null);
        ConversationContextSnapshotView conversationSnapshot = conversationSnapshots
                .findLatestByConversationId(conversation.id())
                .filter(value -> value.conversationId().equals(conversation.id()))
                .orElse(null);
        CheckpointView checkpoint = runtime.findLatestCheckpoint(run.id()).orElse(null);
        RunStepView currentStep = runtime.findSteps(run.id()).stream()
                .filter(value -> value.state() == RunStepState.IN_PROGRESS
                        || value.state() == RunStepState.PENDING)
                .max(Comparator.comparingInt(RunStepView::sequence))
                .orElse(null);

        List<ArtifactApplicationApi.ArtifactView> runArtifacts = artifacts.byRun(run.id());
        requireBounded(runArtifacts, "Artifact");
        List<ModelCallLedgerView> calls = modelCalls.findByRunId(run.id());
        requireBounded(calls, "ModelCall");
        List<ToolExecutionLedgerView> tools = toolExecutions.findByRunId(run.id());
        requireBounded(tools, "ToolExecution");
        if (calls.stream().anyMatch(value -> value.status() == ModelCallStatus.RUNNING)
                || tools.stream().anyMatch(value -> value.status() == ToolExecutionStatus.PENDING
                    || value.status() == ToolExecutionStatus.RUNNING)) {
            throw conflict("Recovery capture cannot race an in-flight effect",
                    "CODING_RECOVERY_EFFECT_IN_FLIGHT");
        }
        List<GovernanceApplicationApi.ApprovalView> approvals = governance
                .listRequestedApprovals(new GovernanceApplicationApi.RequesterApprovalsQuery(
                        run.tenantId(), run.ownerId(), "WORKSPACE", run.workspaceId(), null, 200))
                .stream()
                .filter(value -> value.state() == ApprovalState.PENDING
                        || value.state() == ApprovalState.APPROVED)
                .toList();

        List<ArtifactEvidence> artifactEvidence = runArtifacts.stream()
                .sorted(Comparator.comparing(ArtifactApplicationApi.ArtifactView::createdAt)
                .thenComparing(ArtifactApplicationApi.ArtifactView::id))
                .map(value -> new ArtifactEvidence(
                        value.id(), value.type(), value.contentHash(), value.createdAt()))
                .toList();
        List<TestEvidence> tests = runArtifacts.stream()
                .filter(value -> value.type() == ArtifactType.TEST_REPORT)
                .sorted(Comparator.comparing(ArtifactApplicationApi.ArtifactView::createdAt)
                        .thenComparing(ArtifactApplicationApi.ArtifactView::id))
                .map(this::testEvidence)
                .toList();
        List<AcceptanceEvidence> acceptance = runArtifacts.stream()
                .filter(value -> value.type() == ArtifactType.ACCEPTANCE_EVIDENCE)
                .sorted(Comparator.comparing(ArtifactApplicationApi.ArtifactView::createdAt)
                        .thenComparing(ArtifactApplicationApi.ArtifactView::id))
                .map(this::acceptanceEvidence)
                .toList();
        List<ModelCallEvidence> modelEvidence = calls.stream()
                .sorted(Comparator.comparing(ModelCallLedgerView::createdAt)
                        .thenComparing(ModelCallLedgerView::id))
                .map(this::modelEvidence)
                .toList();
        List<UnknownToolEffect> unknownTools = tools.stream()
                .filter(value -> value.status() == ToolExecutionStatus.UNKNOWN)
                .sorted(Comparator.comparing(ToolExecutionLedgerView::updatedAt)
                        .thenComparing(ToolExecutionLedgerView::id))
                .map(value -> new UnknownToolEffect(
                        value.id(), value.toolCallId(), value.toolName(), value.status(),
                        value.resultRef(), value.updatedAt()))
                .toList();
        List<UnknownModelEffect> unknownModels = calls.stream()
                .filter(value -> value.status() == ModelCallStatus.UNKNOWN)
                .sorted(Comparator.comparing(ModelCallLedgerView::updatedAt)
                        .thenComparing(ModelCallLedgerView::id))
                .map(value -> new UnknownModelEffect(
                        value.id(), value.logicalCallId(), value.status(), value.providerId(),
                        value.modelId(), value.errorCode(), value.updatedAt()))
                .toList();
        List<ApprovalRequirement> approvalEvidence = approvals.stream()
                .sorted(Comparator.comparing(GovernanceApplicationApi.ApprovalView::createdAt)
                        .thenComparing(GovernanceApplicationApi.ApprovalView::id))
                .map(value -> new ApprovalRequirement(
                        value.id(), value.actionType(), value.resourceType(), value.resourceId(),
                        bounded(value.summary(), 2_000), value.state(), value.expiresAt()))
                .toList();

        List<String> blockers = blockers(
                run, task, step, workspace, git, blueprint, conversationSnapshot, checkpoint,
                tests, acceptance, unknownTools, unknownModels, approvals);
        String nextAction = nextAction(run, blockers);
        return new RecoveryContext(
                new ProjectContext(
                        run.projectId(), directory.id(), directory.name(), directory.relativePath(),
                        workspace.sourceRepositoryId()),
                blueprint,
                taskContext(root, task, plan, step),
                new WorkspaceGitContext(
                        workspace.id(), workspace.sourceRepositoryId(), workspace.mode(),
                        workspace.state(), workspace.revision(), workspace.baseRef(),
                        workspace.branchName(), workspace.headCommit(), git.headCommit(), git.status(),
                        git.changedFiles(), git.patchHash(), git.patch()),
                new RuntimeContext(
                        run.id(), run.agentId(), run.configurationSnapshotId(), run.state(), run.revision(),
                        run.executionCursor().phase(),
                        currentStep == null ? null : currentStep.id(),
                        currentStep == null ? null : currentStep.type(),
                        currentStep == null ? null : currentStep.state(),
                        checkpoint == null ? null : checkpoint.id(),
                        checkpoint == null ? 0 : checkpoint.sequence(),
                        checkpoint == null ? null : sha256(checkpoint.stateSnapshot()),
                        runtime.latestEventSequence(run.id())),
                conversationContext(conversation.id(), conversationSnapshot),
                artifactEvidence, tests, acceptance, modelEvidence, unknownTools, unknownModels,
                approvalEvidence, nextAction, blockers);
    }

    private void verifyCaptureFence(AgentRunView expected, WorkspaceGitContext workspaceContext) {
        AgentRunView current = runtime.findRun(expected.id())
                .orElseThrow(() -> conflict("Coding Run changed during recovery capture",
                        "CODING_RECOVERY_CAPTURE_STALE"));
        if (current.revision() != expected.revision()
                || current.state() != expected.state()
                || !current.executionCursor().equals(expected.executionCursor())
                || !current.workspaceId().equals(expected.workspaceId())
                || !current.projectDirectoryId().equals(expected.projectDirectoryId())) {
            throw conflict("Coding Run changed during recovery capture",
                    "CODING_RECOVERY_CAPTURE_STALE");
        }
        var workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                expected.tenantId(), expected.ownerId(), expected.projectId(), expected.workspaceId()));
        if (workspace.revision() != workspaceContext.revision()
                || workspace.state() != workspaceContext.state()
                || !equalsIgnoreCase(workspace.headCommit(), workspaceContext.persistedHeadCommit())) {
            throw conflict("Workspace changed during recovery capture",
                    "CODING_RECOVERY_CAPTURE_STALE");
        }
    }

    private ProjectDirectoryApplicationApi.DirectoryView requireDirectoryAccess(
            AgentRunView run, String userId) {
        return directories.get(new ProjectDirectoryApplicationApi.Query(
                run.tenantId(), userId, run.projectId(), run.projectDirectoryId()));
    }

    private AgentRunView requireStrictRun(
            String tenantId, String userId, String projectId, String runId) {
        return runtime.findRun(required(runId, "agentRunId"))
                .filter(value -> required(tenantId, "tenantId").equals(value.tenantId()))
                .filter(value -> required(userId, "userId").equals(value.ownerId()))
                .filter(value -> required(projectId, "projectId").equals(value.projectId()))
                .filter(value -> value.projectDirectoryId() != null)
                .filter(value -> value.workspaceId() != null)
                .filter(value -> value.taskPlanId() != null)
                .filter(value -> value.planStepId() != null)
                .filter(value -> value.configurationSnapshotId() != null)
                .orElseThrow(() -> new BusinessException(
                        "Directory-bound Coding Run not found", HttpStatus.NOT_FOUND,
                        "CODING_RECOVERY_RUN_NOT_FOUND"));
    }

    private GitCapture captureGit(AgentRunView run) {
        String prefix = "recovery-" + run.id().substring(0, Math.min(12, run.id().length()));
        String head = git(run, prefix + "-head", List.of("rev-parse", "HEAD"), false)
                .stdout().trim();
        if (!head.matches("[0-9a-fA-F]{7,64}")) {
            throw conflict("Sandbox returned invalid Git HEAD", "CODING_RECOVERY_GIT_INVALID");
        }
        String status = git(run, prefix + "-status",
                List.of("status", "--porcelain=v1", "--untracked-files=all"), false).stdout();
        List<String> tracked = paths(git(run, prefix + "-tracked",
                List.of("diff", "--name-only", "-z", "HEAD", "--"), false).stdout());
        List<String> untracked = paths(git(run, prefix + "-untracked",
                List.of("ls-files", "--others", "--exclude-standard", "-z"), false).stdout());
        Set<String> changed = new TreeSet<>();
        changed.addAll(tracked);
        changed.addAll(untracked);
        if (changed.size() > MAX_CHANGED_FILES) {
            throw tooLarge("Recovery snapshot changed-file limit exceeded");
        }

        String trackedPatch = git(run, prefix + "-patch",
                List.of("diff", "--binary", "--no-ext-diff", "HEAD", "--"), false).stdout();
        int patchBytes = trackedPatch.getBytes(StandardCharsets.UTF_8).length;
        if (patchBytes > MAX_PATCH_BYTES) {
            throw tooLarge("Recovery snapshot patch limit exceeded");
        }
        StringBuilder patch = new StringBuilder(trackedPatch);
        for (String path : untracked) {
            String untrackedPatch = git(run, prefix + "-untracked-patch-" + sha256(path).substring(7, 19),
                    List.of("diff", "--binary", "--no-ext-diff", "--no-index", "--",
                            "/dev/null", path), true).stdout();
            boolean separator = !patch.isEmpty() && !untrackedPatch.isEmpty()
                    && patch.charAt(patch.length() - 1) != '\n';
            int nextBytes = untrackedPatch.getBytes(StandardCharsets.UTF_8).length
                    + (separator ? 1 : 0);
            if (patchBytes + nextBytes > MAX_PATCH_BYTES) {
                throw tooLarge("Recovery snapshot patch limit exceeded");
            }
            if (separator) {
                patch.append('\n');
            }
            patch.append(untrackedPatch);
            patchBytes += nextBytes;
        }
        String value = patch.toString();
        return new GitCapture(
                head.toLowerCase(), status, List.copyOf(changed),
                value.isBlank() ? null : sha256(value), value);
    }

    private SandboxComputeApplicationApi.ComputeResult git(
            AgentRunView run, String toolCallId, List<String> arguments, boolean allowExitOne) {
        try {
            var result = sandbox.execute(new SandboxComputeApplicationApi.ComputeCommand(
                    run.id(), toolCallId, "workspaces/" + run.workspaceId(), run.taskId(),
                    "project-context-snapshot", "git", arguments, 60));
            if ("OUTPUT_LIMIT_EXCEEDED".equals(result.status())
                    || "SANDBOX_OUTPUT_LIMIT".equals(result.error())) {
                throw tooLarge("Sandbox Git evidence exceeds the recovery limit");
            }
            boolean accepted = "SUCCEEDED".equals(result.status()) && result.exitStatus() == 0;
            if (allowExitOne && "FAILED".equals(result.status()) && result.exitStatus() == 1) {
                accepted = true;
            }
            if (!accepted) {
                throw unavailable("Sandbox Git inspection failed");
            }
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("OCI Sandbox is required for Project recovery capture");
        }
    }

    private List<String> paths(String value) {
        if (value == null || value.isEmpty()) return List.of();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String path : value.split("\\u0000", -1)) {
            if (path.isEmpty()) continue;
            if (path.length() > 4_096 || path.startsWith("/") || path.indexOf('\0') >= 0
                    || java.util.Arrays.stream(path.split("/", -1)).anyMatch(".."::equals)) {
                throw conflict("Sandbox returned an invalid changed-file path",
                        "CODING_RECOVERY_GIT_INVALID");
            }
            paths.add(path);
            if (paths.size() > MAX_CHANGED_FILES) {
                throw tooLarge("Recovery snapshot changed-file limit exceeded");
            }
        }
        return List.copyOf(paths);
    }

    private BlueprintContext blueprint(ProjectBlueprintApplicationApi.BlueprintView value) {
        var document = value.document();
        Map<String, String> commands = new TreeMap<>(document.commands());
        if (commands.size() > 100 || commands.entrySet().stream().anyMatch(entry ->
                entry.getKey().length() > 120 || entry.getValue().length() > 4_096)) {
            throw tooLarge("Blueprint command evidence exceeds recovery bounds");
        }
        return new BlueprintContext(
                value.id(), value.versionNumber(), value.status(), bounded(document.goal(), 8_000),
                boundedList(document.requirements()), boundedList(document.modules()),
                boundedList(document.boundaries()), boundedList(document.architectureDecisions()),
                commands, boundedList(document.environmentRefs()),
                boundedList(document.conventions()), boundedList(document.forbiddenAreas()),
                boundedList(document.risks()), boundedList(document.openQuestions()),
                boundedList(document.acceptanceCriteria()), boundedList(document.evidence()));
    }

    private TaskExecutionContext taskContext(
            TaskView root, TaskView task, TaskPlanView plan, PlanStepView step) {
        return new TaskExecutionContext(
                root.id(), bounded(root.title(), 1_000), bounded(root.goal(), 8_000), root.state(),
                root.currentTaskPlanId(), task.id(), task.parentTaskId(),
                bounded(task.title(), 1_000), bounded(task.goal(), 8_000),
                bounded(task.description(), 8_000), boundedList(task.constraints()),
                boundedList(task.acceptanceCriteria()), task.state(), plan.id(),
                plan.versionNumber(), plan.status(), step.id(), step.stepKey(), step.sequence(),
                step.state(), step.dependencyStepIds(), step.requiredCapability(),
                step.preferredAgentId(), bounded(step.expectedOutput(), 8_000),
                boundedList(step.acceptanceCriteria()), step.approvalRequired());
    }

    private ConversationContext conversationContext(
            String conversationId, ConversationContextSnapshotView snapshot) {
        if (snapshot == null) {
            return new ConversationContext(
                    conversationId, null, null, null, null, null, null, null);
        }
        return new ConversationContext(
                conversationId, snapshot.id(), snapshot.version(),
                bounded(snapshot.summary(), 8_000), snapshot.fromMessageSequence(),
                snapshot.toMessageSequence(), snapshot.tokenCount(), snapshot.checksum());
    }

    private TestEvidence testEvidence(ArtifactApplicationApi.ArtifactView value) {
        JsonNode metadata = readTree(value.metadataJson());
        Integer exit = metadata != null && metadata.path("exitCode").isIntegralNumber()
                ? metadata.path("exitCode").asInt() : null;
        String executable = metadata == null ? null : bounded(metadata.path("executable").asText(null), 120);
        return new TestEvidence(value.id(), executable, exit, exit != null && exit == 0,
                value.createdAt());
    }

    private AcceptanceEvidence acceptanceEvidence(ArtifactApplicationApi.ArtifactView value) {
        JsonNode metadata = readTree(value.metadataJson());
        return new AcceptanceEvidence(
                value.id(), metadata == null ? null : bounded(metadata.path("criterion").asText(null), 4_000),
                metadata != null && metadata.path("passed").asBoolean(false), value.createdAt());
    }

    private ModelCallEvidence modelEvidence(ModelCallLedgerView value) {
        return new ModelCallEvidence(
                value.id(), value.logicalCallId(), value.status(), value.providerId(),
                value.modelId(), value.errorCode(), value.updatedAt());
    }

    private List<String> blockers(
            AgentRunView run,
            TaskView task,
            PlanStepView step,
            WorkspaceApplicationApi.WorkspaceView workspace,
            GitCapture git,
            BlueprintContext blueprint,
            ConversationContextSnapshotView conversationSnapshot,
            CheckpointView checkpoint,
            List<TestEvidence> tests,
            List<AcceptanceEvidence> acceptance,
            List<UnknownToolEffect> unknownTools,
            List<UnknownModelEffect> unknownModels,
            List<GovernanceApplicationApi.ApprovalView> approvals) {
        TreeSet<String> blockers = new TreeSet<>();
        if (blueprint == null) blockers.add("PROJECT_BLUEPRINT_MISSING");
        if (conversationSnapshot == null) blockers.add("CONVERSATION_CONTEXT_SNAPSHOT_MISSING");
        if (checkpoint == null) blockers.add("RUNTIME_CHECKPOINT_MISSING");
        if (!equalsIgnoreCase(workspace.headCommit(), git.headCommit())) {
            blockers.add("WORKSPACE_HEAD_DRIFT");
        }
        if (!git.status().isBlank() && git.patch().isBlank()) {
            blockers.add("WORKSPACE_DIRTY_PATCH_MISSING");
        }
        if (step.state() == com.spaceagent.platform.project.domain.PlanStepState.BLOCKED) {
            blockers.add("PLAN_STEP_BLOCKED");
        }
        if (step.state() == com.spaceagent.platform.project.domain.PlanStepState.FAILED) {
            blockers.add("PLAN_STEP_FAILED");
        }
        if (tests.stream().anyMatch(value -> !value.passed())) {
            blockers.add("TEST_FAILURE_PRESENT");
        }
        Set<String> passed = acceptance.stream().filter(AcceptanceEvidence::passed)
                .map(AcceptanceEvidence::criterion).collect(java.util.stream.Collectors.toSet());
        if (run.state() != AgentRunState.COMPLETED
                && (taskAcceptanceMissing(task.acceptanceCriteria(), passed)
                    || taskAcceptanceMissing(step.acceptanceCriteria(), passed))) {
            blockers.add("ACCEPTANCE_EVIDENCE_MISSING");
        }
        if (!unknownTools.isEmpty()) blockers.add("TOOL_EFFECT_RECONCILIATION_REQUIRED");
        if (!unknownModels.isEmpty()) blockers.add("MODEL_EFFECT_RECONCILIATION_REQUIRED");
        if (approvals.stream().anyMatch(value -> value.state() == ApprovalState.PENDING)) {
            blockers.add("GOVERNANCE_APPROVAL_PENDING");
        }
        if (approvals.size() == 200) blockers.add("APPROVAL_EVIDENCE_LIMIT_REACHED");
        return List.copyOf(blockers);
    }

    private static boolean taskAcceptanceMissing(List<String> criteria, Set<String> passed) {
        return criteria.stream().anyMatch(value -> !passed.contains(value));
    }

    private static String nextAction(AgentRunView run, List<String> blockers) {
        if (blockers.contains("TOOL_EFFECT_RECONCILIATION_REQUIRED")
                || blockers.contains("MODEL_EFFECT_RECONCILIATION_REQUIRED")) {
            return "RECONCILE_UNKNOWN_EFFECTS";
        }
        if (run.state() == AgentRunState.COMPLETED) return "NO_ACTION";
        if (blockers.contains("GOVERNANCE_APPROVAL_PENDING")) return "WAIT_FOR_APPROVAL";
        if (blockers.contains("WORKSPACE_HEAD_DRIFT")) return "RECONCILE_WORKSPACE_HEAD";
        if (blockers.contains("TEST_FAILURE_PRESENT")) return "FIX_AND_RERUN_TESTS";
        if (blockers.contains("ACCEPTANCE_EVIDENCE_MISSING")) {
            return "SATISFY_ACCEPTANCE_CRITERIA";
        }
        if (blockers.contains("PROJECT_BLUEPRINT_MISSING")) return "CONFIRM_PROJECT_BLUEPRINT";
        if (blockers.contains("CONVERSATION_CONTEXT_SNAPSHOT_MISSING")) {
            return "REFRESH_CONVERSATION_CONTEXT";
        }
        if (blockers.contains("RUNTIME_CHECKPOINT_MISSING")) return "CREATE_CHECKPOINT";
        if (run.state() == AgentRunState.FAILED || run.state() == AgentRunState.CANCELLED) {
            return "START_REPLACEMENT_RUN";
        }
        if (run.state() == AgentRunState.WAITING_FOR_USER) return "WAIT_FOR_USER";
        return "RESUME_PLAN_STEP";
    }

    private CodingRecoveryPackageView replay(
            ProjectExecutionContextSnapshot snapshot, String inputHash) {
        if (!snapshot.inputHash().equals(inputHash)) {
            throw conflict("Idempotency key belongs to another recovery scope",
                    "CODING_RECOVERY_IDEMPOTENCY_CONFLICT");
        }
        return view(snapshot);
    }

    private CodingRecoveryPackageView view(ProjectExecutionContextSnapshot snapshot) {
        if (!snapshot.snapshotHash().equals(sha256(snapshot.payloadJson()))) {
            throw new IllegalStateException("Recovery snapshot hash verification failed");
        }
        try {
            return new CodingRecoveryPackageView(
                    snapshot.id(), snapshot.snapshotHash(), snapshot.createdAt(),
                    json.readValue(snapshot.payloadJson(), RecoveryContext.class));
        } catch (Exception error) {
            throw new IllegalStateException("Recovery snapshot payload is invalid", error);
        }
    }

    private JsonNode readTree(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize recovery snapshot", error);
        }
    }

    private static <T> void requireBounded(List<T> values, String kind) {
        if (values.size() > MAX_EVIDENCE) {
            throw tooLarge(kind + " evidence exceeds recovery bounds");
        }
    }

    private static List<String> boundedList(List<String> values) {
        if (values.size() > 200) throw tooLarge("Recovery list evidence exceeds bounds");
        return values.stream().map(value -> bounded(value, 4_000)).toList();
    }

    private static String bounded(String value, int limit) {
        if (value == null) return null;
        if (value.length() > limit) throw tooLarge("Recovery text evidence exceeds bounds");
        return value;
    }

    private static String idempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() < 8 || value.length() > 200
                || value.indexOf('\0') >= 0) {
            throw new BusinessException(
                    "Idempotency-Key must contain 8-200 characters", HttpStatus.BAD_REQUEST,
                    "CODING_RECOVERY_IDEMPOTENCY_INVALID");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String uuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (Exception error) {
            throw notFound();
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(
                message, HttpStatus.SERVICE_UNAVAILABLE, "CODING_RECOVERY_SANDBOX_REQUIRED");
    }

    private static BusinessException tooLarge(String message) {
        return new BusinessException(
                message, HttpStatus.PAYLOAD_TOO_LARGE, "CODING_RECOVERY_EVIDENCE_TOO_LARGE");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "Coding recovery snapshot not found", HttpStatus.NOT_FOUND,
                "CODING_RECOVERY_SNAPSHOT_NOT_FOUND");
    }

    private record GitCapture(
            String headCommit,
            String status,
            List<String> changedFiles,
            String patchHash,
            String patch) {
    }
}
