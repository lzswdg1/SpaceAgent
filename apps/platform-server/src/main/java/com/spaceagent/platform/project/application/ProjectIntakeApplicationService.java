package com.spaceagent.platform.project.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.api.CreateTaskCommand;
import com.spaceagent.platform.project.api.CreateTaskPlanCommand;
import com.spaceagent.platform.project.api.ProjectBlueprintApplicationApi;
import com.spaceagent.platform.project.api.ProjectIntakeApplicationApi;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanActionCommand;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectBlueprintDocument;
import com.spaceagent.platform.project.domain.ProjectBlueprintSource;
import com.spaceagent.platform.project.domain.ProjectDirectory;
import com.spaceagent.platform.project.domain.ProjectDirectoryRepository;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import com.spaceagent.platform.project.api.ProjectIntakeInspection;
import com.spaceagent.platform.project.domain.ProjectIntakeJob;
import com.spaceagent.platform.project.domain.ProjectIntakeRepository;
import com.spaceagent.platform.project.domain.ProjectIntakeState;
import com.spaceagent.platform.project.domain.ProjectIntakeWorkspaceGateway;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import com.spaceagent.platform.project.domain.WorkspaceCheckoutCredentialPort;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class ProjectIntakeApplicationService implements ProjectIntakeApplicationApi {
    private static final int MAX_PAYLOAD_BYTES = 500_000;

    private final ProjectIntakeRepository jobs;
    private final ProjectDirectoryRepository directories;
    private final SourceRepositoryRepository sources;
    private final ProjectAccessPolicy access;
    private final ProjectIntakeWorkspaceGateway workspaceGateway;
    private final WorkspaceCheckoutCredentialPort credentials;
    private final ProjectBlueprintApplicationApi blueprints;
    private final TaskApplicationApi tasks;
    private final TaskPlanApplicationApi plans;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;

    public ProjectIntakeApplicationService(
            ProjectIntakeRepository jobs,
            ProjectDirectoryRepository directories,
            SourceRepositoryRepository sources,
            ProjectAccessPolicy access,
            ProjectIntakeWorkspaceGateway workspaceGateway,
            WorkspaceCheckoutCredentialPort credentials,
            ProjectBlueprintApplicationApi blueprints,
            TaskApplicationApi tasks,
            TaskPlanApplicationApi plans,
            ObjectMapper json,
            IdGenerator ids,
            TimeProvider time) {
        this.jobs = jobs;
        this.directories = directories;
        this.sources = sources;
        this.access = access;
        this.workspaceGateway = workspaceGateway;
        this.credentials = credentials;
        this.blueprints = blueprints;
        this.tasks = tasks;
        this.plans = plans;
        this.json = json;
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional
    public JobView enqueue(EnqueueCommand command) {
        Project project = requireManage(command.tenantId(), command.userId(), command.projectId());
        ProjectDirectory directory = requireDirectory(project, command.projectDirectoryId());
        SourceRepository source = requireManagedSource(project, directory);
        String goal = text(command.goal(), "goal", 8_000);
        String idempotencyHash = sha256(text(command.idempotencyKey(), "idempotencyKey", 200));
        String inputHash = sha256(String.join("\n", project.tenantId(), command.userId(),
                project.id(), directory.id(), source.id(),
                text(command.conversationId(), "conversationId", 80),
                text(command.agentId(), "agentId", 80), goal));
        ProjectIntakeJob existing = jobs.findByIdempotency(
                project.tenantId(), command.userId(), idempotencyHash).orElse(null);
        if (existing != null) {
            if (!existing.inputHash().equals(inputHash)) {
                throw conflict("Project intake idempotency key was reused with different input",
                        "PROJECT_INTAKE_IDEMPOTENCY_CONFLICT");
            }
            return view(existing);
        }
        Instant now = time.now();
        ProjectIntakeJob created = new ProjectIntakeJob(
                ids.nextId(), project.tenantId(), command.userId(), project.id(), directory.id(),
                source.id(), command.conversationId(), command.agentId(), goal,
                idempotencyHash, inputHash, ProjectIntakeState.PENDING, 0,
                null, null, 0, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null,
                1, now, null, now, null, null);
        try {
            jobs.insert(created);
            return view(created);
        } catch (DataIntegrityViolationException | IllegalStateException error) {
            ProjectIntakeJob winner = jobs.findByIdempotency(
                    project.tenantId(), command.userId(), idempotencyHash).orElse(null);
            if (winner != null && winner.inputHash().equals(inputHash)) return view(winner);
            throw conflict("An intake analysis is already active for this ProjectDirectory",
                    "PROJECT_INTAKE_ACTIVE");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public JobView get(Query query) {
        Project project = access.requireProject(query.tenantId(), query.userId(), query.projectId());
        requireDirectory(project, query.projectDirectoryId());
        return view(requireJob(query.jobId(), project, query.projectDirectoryId(), query.userId()));
    }

    @Override
    @Transactional(readOnly = true)
    public JobPageView list(ListQuery query) {
        Project project = access.requireProject(query.tenantId(), query.userId(), query.projectId());
        ProjectDirectory directory = requireDirectory(project, query.projectDirectoryId());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(100, query.pageSize()));
        List<JobView> items = jobs.findByDirectory(
                directory.id(), query.userId(), (page - 1) * size, size).stream()
                .map(this::view).toList();
        return new JobPageView(
                items, page, size, jobs.countByDirectory(directory.id(), query.userId()));
    }

    @Override
    public Optional<ClaimView> claim(String workerId, int leaseSeconds, int maximumAttempts) {
        String worker = text(workerId, "workerId", 160);
        int lease = Math.max(60, Math.min(1_800, leaseSeconds));
        int attempts = Math.max(1, Math.min(10, maximumAttempts));
        Instant now = time.now();
        return jobs.claim(worker, ids.nextId(), now, now.plus(lease, ChronoUnit.SECONDS), attempts)
                .map(value -> new ClaimView(view(value), value.claimToken(),
                        value.fencingToken(), value.leaseUntil()));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public JobView provisionWorkspace(ClaimCommand command) {
        ProjectIntakeJob job = requireClaim(command);
        if (job.workspaceRef() != null) return view(job);
        SourceRepository source = requireSource(job.sourceRepositoryId());
        ProjectIntakeWorkspaceGateway.IntakeWorkspace workspace;
        try (WorkspaceCheckoutCredentialPort.CredentialLease lease = credential(job, source)) {
            workspace = workspaceGateway.provision(
                    job.id(), source, source.defaultBranch(), lease.authorizationHeader());
        }
        Instant now = time.now();
        ProjectIntakeJob updated = copy(job, ProjectIntakeState.RUNNING, job.attempt(),
                job.claimOwner(), job.claimToken(), job.fencingToken(), job.leaseUntil(),
                workspace.workspaceRef(), workspace.headCommit().toLowerCase(), job.inspectionHash(),
                job.inspectionJson(), job.proposalHash(), job.proposalJson(), job.agentRunId(),
                job.blueprintId(), job.rootTaskId(), job.taskPlanId(), null, job.reviewedBy(),
                job.reviewReason(), job.reviewedAt(), job.revision() + 1, job.startedAt(), now,
                null, job.workspaceCleanedAt());
        if (!jobs.updateClaimed(job, updated, now)) throw leaseLost();
        return view(updated);
    }

    @Override
    public JobView attachRun(AttachRunCommand command) {
        ProjectIntakeJob job = requireClaim(command.jobId(), command.workerId(),
                command.claimToken(), command.fencingToken());
        if (job.agentRunId() != null) {
            if (!job.agentRunId().equals(command.agentRunId())) throw leaseLost();
            return view(job);
        }
        Instant now = time.now();
        ProjectIntakeJob updated = copy(job, job.state(), job.attempt(), job.claimOwner(),
                job.claimToken(), job.fencingToken(), job.leaseUntil(), job.workspaceRef(),
                job.sourceHeadCommit(), job.inspectionHash(), job.inspectionJson(),
                job.proposalHash(), job.proposalJson(), command.agentRunId(), job.blueprintId(),
                job.rootTaskId(), job.taskPlanId(), null, job.reviewedBy(), job.reviewReason(),
                job.reviewedAt(), job.revision() + 1, job.startedAt(), now, null,
                job.workspaceCleanedAt());
        if (!jobs.updateClaimed(job, updated, now)) throw leaseLost();
        return view(updated);
    }

    @Override
    public JobView recordInspection(InspectionCommand command) {
        ProjectIntakeJob job = requireClaim(command.jobId(), command.workerId(),
                command.claimToken(), command.fencingToken());
        verifyPayload(command.inspectionHash(), command.inspectionJson(), "inspection");
        inspection(command.inspectionJson());
        if (!command.sourceHeadCommit().equalsIgnoreCase(job.sourceHeadCommit())) {
            throw conflict("Project source changed during intake", "PROJECT_INTAKE_SOURCE_CHANGED");
        }
        Instant now = time.now();
        ProjectIntakeJob updated = copy(job, job.state(), job.attempt(), job.claimOwner(),
                job.claimToken(), job.fencingToken(), job.leaseUntil(), job.workspaceRef(),
                job.sourceHeadCommit(), command.inspectionHash(), command.inspectionJson(),
                job.proposalHash(), job.proposalJson(), job.agentRunId(), job.blueprintId(),
                job.rootTaskId(), job.taskPlanId(), null, job.reviewedBy(), job.reviewReason(),
                job.reviewedAt(), job.revision() + 1, job.startedAt(), now, null,
                job.workspaceCleanedAt());
        if (!jobs.updateClaimed(job, updated, now)) throw leaseLost();
        return view(updated);
    }

    @Override
    public String readInspection(ClaimCommand command) {
        ProjectIntakeJob job = requireClaim(command);
        if (job.inspectionJson() == null) {
            throw conflict("Project intake inspection is not available",
                    "PROJECT_INTAKE_INSPECTION_MISSING");
        }
        return job.inspectionJson();
    }

    @Override
    public JobView publishProposal(ProposalCommand command) {
        ProjectIntakeJob job = requireClaim(command.jobId(), command.workerId(),
                command.claimToken(), command.fencingToken());
        verifyPayload(command.proposalHash(), command.proposalJson(), "proposal");
        validate(proposal(command.proposalJson()));
        Instant now = time.now();
        ProjectIntakeJob updated = copy(job, ProjectIntakeState.PROPOSED, job.attempt(), null,
                null, job.fencingToken(), null, job.workspaceRef(), job.sourceHeadCommit(),
                job.inspectionHash(), job.inspectionJson(), command.proposalHash(),
                command.proposalJson(), job.agentRunId(), null, null, null, null, null, null, null,
                job.revision() + 1, job.startedAt(), now, now, job.workspaceCleanedAt());
        if (!jobs.updateClaimed(job, updated, now)) throw leaseLost();
        return view(updated);
    }

    @Override
    public JobView fail(FailCommand command) {
        ProjectIntakeJob job = requireClaim(command.jobId(), command.workerId(),
                command.claimToken(), command.fencingToken());
        String code = safeCode(command.safeErrorCode());
        Instant now = time.now();
        ProjectIntakeJob updated = copy(job,
                command.blocked() ? ProjectIntakeState.BLOCKED : ProjectIntakeState.FAILED,
                job.attempt(), null, null, job.fencingToken(), null, job.workspaceRef(),
                job.sourceHeadCommit(), job.inspectionHash(), job.inspectionJson(),
                job.proposalHash(), job.proposalJson(), job.agentRunId(), null, null, null, code,
                null, null, null, job.revision() + 1, job.startedAt(), now, now,
                job.workspaceCleanedAt());
        if (!jobs.updateClaimed(job, updated, now)) throw leaseLost();
        return view(updated);
    }

    @Override
    @Transactional
    public JobView confirm(ConfirmCommand command) {
        Project project = requireManage(command.tenantId(), command.userId(), command.projectId());
        requireDirectory(project, command.projectDirectoryId());
        ProjectIntakeJob job = requireJobForUpdate(
                command.jobId(), project, command.projectDirectoryId(), command.userId());
        requireExpectedProposalHash(job, command.expectedProposalHash());
        if (job.state() == ProjectIntakeState.CONFIRMED) return view(job);
        requireProposalState(job, command.expectedProposalHash());
        ProjectIntakeProposal proposal = proposal(job.proposalJson());
        validate(proposal);

        var blueprintDraft = proposal.blueprint();
        ProjectIntakeInspection inspection = inspection(job.inspectionJson());
        List<String> evidence = new ArrayList<>();
        evidence.add("source-head:" + job.sourceHeadCommit());
        evidence.add("inspection:" + job.inspectionHash());
        inspection.selectedFiles().forEach(value ->
                evidence.add("file:" + value.path() + ":" + value.sha256()));
        var createdBlueprint = blueprints.create(new ProjectBlueprintApplicationApi.CreateCommand(
                job.tenantId(), job.ownerId(), job.projectId(), job.sourceRepositoryId(),
                job.agentId(), job.agentRunId(), ProjectBlueprintSource.AGENT,
                new ProjectBlueprintDocument(
                        blueprintDraft.goal(), blueprintDraft.requirements(), blueprintDraft.modules(),
                        blueprintDraft.boundaries(), blueprintDraft.architectureDecisions(),
                        new TreeMap<>(blueprintDraft.commands()), blueprintDraft.environmentRefs(),
                        blueprintDraft.conventions(), blueprintDraft.forbiddenAreas(),
                        blueprintDraft.risks(), blueprintDraft.openQuestions(),
                        blueprintDraft.acceptanceCriteria(), evidence)));
        var confirmedBlueprint = blueprints.confirm(new ProjectBlueprintApplicationApi.ConfirmCommand(
                job.tenantId(), job.ownerId(), job.projectId(), createdBlueprint.id()));

        var rootDraft = proposal.rootTask();
        var root = tasks.createTask(new CreateTaskCommand(
                job.tenantId(), job.ownerId(), job.projectId(), null, rootDraft.title(),
                rootDraft.goal(), rootDraft.description(), rootDraft.constraints(),
                rootDraft.acceptanceCriteria()));
        Map<String, String> childIds = new LinkedHashMap<>();
        for (ChildTaskDraft child : proposal.childTasks()) {
            var created = tasks.createTask(new CreateTaskCommand(
                    job.tenantId(), job.ownerId(), job.projectId(), root.id(), child.title(),
                    child.goal(), child.description(), child.constraints(),
                    child.acceptanceCriteria()));
            childIds.put(child.key(), created.id());
        }
        List<CreateTaskPlanCommand.PlanStepDraft> steps = proposal.steps().stream()
                .map(step -> new CreateTaskPlanCommand.PlanStepDraft(
                        step.stepKey(), childIds.get(step.childTaskKey()),
                        step.dependsOnStepKeys(), step.requiredCapability(), job.agentId(),
                        step.expectedOutput(), step.acceptanceCriteria(), step.approvalRequired()))
                .toList();
        var plan = plans.createPlan(new CreateTaskPlanCommand(
                job.tenantId(), job.ownerId(), job.projectId(), root.id(),
                null, steps, job.agentId(), job.agentRunId()));
        plan = plans.transition(action(job, root.id(), plan.id(), TaskPlanAction.PROPOSE));
        plan = plans.transition(action(job, root.id(), plan.id(), TaskPlanAction.APPROVE));
        plan = plans.transition(action(job, root.id(), plan.id(), TaskPlanAction.ACTIVATE));

        Instant now = time.now();
        ProjectIntakeJob updated = copy(job, ProjectIntakeState.CONFIRMED, job.attempt(), null,
                null, job.fencingToken(), null, job.workspaceRef(), job.sourceHeadCommit(),
                job.inspectionHash(), job.inspectionJson(), job.proposalHash(), job.proposalJson(),
                job.agentRunId(), confirmedBlueprint.id(), root.id(), plan.id(), null,
                command.userId(), "CONFIRMED", now, job.revision() + 1, job.startedAt(), now, now,
                job.workspaceCleanedAt());
        jobs.saveLifecycle(job, updated);
        return view(updated);
    }

    @Override
    @Transactional
    public JobView reject(RejectCommand command) {
        Project project = requireManage(command.tenantId(), command.userId(), command.projectId());
        requireDirectory(project, command.projectDirectoryId());
        ProjectIntakeJob job = requireJobForUpdate(
                command.jobId(), project, command.projectDirectoryId(), command.userId());
        requireExpectedProposalHash(job, command.expectedProposalHash());
        if (job.state() == ProjectIntakeState.REJECTED) return view(job);
        requireProposalState(job, command.expectedProposalHash());
        String reason = text(command.reason(), "reason", 500);
        Instant now = time.now();
        ProjectIntakeJob updated = copy(job, ProjectIntakeState.REJECTED, job.attempt(), null,
                null, job.fencingToken(), null, job.workspaceRef(), job.sourceHeadCommit(),
                job.inspectionHash(), job.inspectionJson(), job.proposalHash(), job.proposalJson(),
                job.agentRunId(), null, null, null, null, command.userId(), reason, now,
                job.revision() + 1, job.startedAt(), now, now, job.workspaceCleanedAt());
        jobs.saveLifecycle(job, updated);
        return view(updated);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean cleanupOneWorkspace() {
        ProjectIntakeJob job = jobs.findWorkspaceCleanupCandidate().orElse(null);
        if (job == null) return false;
        try {
            workspaceGateway.cleanup(job.id(), requireSource(job.sourceRepositoryId()));
            jobs.markWorkspaceCleaned(job.id(), job.revision(), time.now());
        } catch (RuntimeException ignored) {
            return false;
        }
        return true;
    }

    private TaskPlanActionCommand action(
            ProjectIntakeJob job, String rootTaskId, String planId, TaskPlanAction action) {
        return new TaskPlanActionCommand(
                job.tenantId(), job.ownerId(), job.projectId(), rootTaskId, planId, action);
    }

    private WorkspaceCheckoutCredentialPort.CredentialLease credential(
            ProjectIntakeJob job, SourceRepository source) {
        if (source.mcpConnectionId() != null
                && source.visibility() == SourceRepositoryVisibility.PRIVATE) {
            return credentials.acquire(new WorkspaceCheckoutCredentialPort.Request(
                    job.tenantId(), job.ownerId(), job.id(), source.id(), source.mcpConnectionId(),
                    source.providerRepositoryId(), source.remoteUrl()));
        }
        return new WorkspaceCheckoutCredentialPort.CredentialLease() {
            public String authorizationHeader() { return null; }
            public void close() { }
        };
    }

    private Project requireManage(String tenantId, String userId, String projectId) {
        Project project = access.requireProject(tenantId, userId, projectId);
        access.requireActive(project);
        access.requireRole(project, userId, ProjectRole::canManageTasks);
        return project;
    }

    private ProjectDirectory requireDirectory(Project project, String id) {
        uuid(id, "ProjectDirectory");
        return directories.findById(id)
                .filter(value -> value.projectId().equals(project.id()))
                .filter(value -> value.tenantId().equals(project.tenantId()))
                .filter(value -> value.state() == ProjectDirectoryState.ACTIVE)
                .orElseThrow(() -> notFound("ProjectDirectory"));
    }

    private SourceRepository requireManagedSource(Project project, ProjectDirectory directory) {
        if (directory.sourceRepositoryId() == null || !".".equals(directory.relativePath())) {
            throw conflict("Project intake requires an active SourceRepository root directory",
                    "PROJECT_INTAKE_SOURCE_ROOT_REQUIRED");
        }
        SourceRepository source = requireSource(directory.sourceRepositoryId());
        if (!source.projectId().equals(project.id()) || !source.tenantId().equals(project.tenantId())
                || source.state() != SourceRepositoryState.READY
                || source.type() == SourceRepositoryType.LOCAL) {
            throw conflict("Project intake requires a READY managed Git source",
                    "PROJECT_INTAKE_MANAGED_SOURCE_REQUIRED");
        }
        if (source.visibility() == SourceRepositoryVisibility.PRIVATE
                && source.mcpConnectionId() == null) {
            throw conflict("Private Project intake requires an MCP checkout credential source",
                    "PROJECT_INTAKE_PRIVATE_SOURCE_CREDENTIAL_REQUIRED");
        }
        return source;
    }

    private SourceRepository requireSource(String id) {
        uuid(id, "SourceRepository");
        return sources.findById(id).orElseThrow(() -> notFound("SourceRepository"));
    }

    private ProjectIntakeJob requireJob(
            String id, Project project, String directoryId, String userId) {
        uuid(id, "Project intake");
        return jobs.findById(id).filter(value -> value.projectId().equals(project.id()))
                .filter(value -> value.projectDirectoryId().equals(directoryId))
                .filter(value -> value.tenantId().equals(project.tenantId()))
                .filter(value -> value.ownerId().equals(userId))
                .orElseThrow(() -> notFound("Project intake"));
    }

    private ProjectIntakeJob requireJobForUpdate(
            String id, Project project, String directoryId, String userId) {
        uuid(id, "Project intake");
        return jobs.findByIdForUpdate(id).filter(value -> value.projectId().equals(project.id()))
                .filter(value -> value.projectDirectoryId().equals(directoryId))
                .filter(value -> value.tenantId().equals(project.tenantId()))
                .filter(value -> value.ownerId().equals(userId))
                .orElseThrow(() -> notFound("Project intake"));
    }

    private ProjectIntakeJob requireClaim(ClaimCommand command) {
        return requireClaim(command.jobId(), command.workerId(), command.claimToken(),
                command.fencingToken());
    }

    private ProjectIntakeJob requireClaim(
            String jobId, String workerId, String claimToken, long fencingToken) {
        uuid(jobId, "Project intake");
        ProjectIntakeJob job = jobs.findById(jobId).orElseThrow(() -> notFound("Project intake"));
        if (job.state() != ProjectIntakeState.RUNNING
                || !java.util.Objects.equals(job.claimOwner(), workerId)
                || !java.util.Objects.equals(job.claimToken(), claimToken)
                || job.fencingToken() != fencingToken
                || job.leaseUntil() == null || !job.leaseUntil().isAfter(time.now())) {
            throw leaseLost();
        }
        return job;
    }

    private void requireProposalState(ProjectIntakeJob job, String expectedHash) {
        if (job.state() != ProjectIntakeState.PROPOSED) {
            throw conflict("Project intake is not awaiting confirmation",
                    "PROJECT_INTAKE_STATE_CONFLICT");
        }
        requireExpectedProposalHash(job, expectedHash);
    }

    private void requireExpectedProposalHash(ProjectIntakeJob job, String expectedHash) {
        if (!java.util.Objects.equals(job.proposalHash(), expectedHash)) {
            throw conflict("Project intake proposal changed", "PROJECT_INTAKE_PROPOSAL_STALE");
        }
    }

    private ProjectIntakeProposal proposal(String value) {
        try {
            return json.readerFor(ProjectIntakeProposal.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);
        } catch (Exception error) {
            throw invalid("Project intake proposal is invalid");
        }
    }

    private ProjectIntakeInspection inspection(String value) {
        try {
            return json.readerFor(ProjectIntakeInspection.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value);
        } catch (Exception error) {
            throw invalid("Project intake inspection is invalid");
        }
    }

    private void validate(ProjectIntakeProposal value) {
        if (value == null || value.blueprint() == null || value.rootTask() == null
                || value.childTasks().isEmpty() || value.childTasks().size() > 50
                || value.steps().size() != value.childTasks().size()) {
            throw invalid("Project intake proposal must contain one to fifty planned child Tasks");
        }
        BlueprintDraft blueprint = value.blueprint();
        text(blueprint.goal(), "blueprint.goal", 8_000);
        bounded(blueprint.requirements(), "requirements", 100);
        bounded(blueprint.modules(), "modules", 100);
        bounded(blueprint.boundaries(), "boundaries", 100);
        bounded(blueprint.architectureDecisions(), "architectureDecisions", 100);
        bounded(blueprint.environmentRefs(), "environmentRefs", 100).forEach(ref -> {
            if (!ref.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw invalid("environmentRefs must contain environment variable names only");
            }
        });
        bounded(blueprint.conventions(), "conventions", 100);
        bounded(blueprint.forbiddenAreas(), "forbiddenAreas", 100);
        bounded(blueprint.risks(), "risks", 100);
        bounded(blueprint.openQuestions(), "openQuestions", 100);
        bounded(blueprint.acceptanceCriteria(), "acceptanceCriteria", 100);
        if (blueprint.commands().size() > 50) throw invalid("Blueprint command limit exceeded");
        blueprint.commands().forEach((key, command) -> {
            text(key, "command name", 120);
            text(command, "command", 4_096);
        });
        validateTask(value.rootTask(), "rootTask");
        Map<String, ChildTaskDraft> children = new LinkedHashMap<>();
        for (ChildTaskDraft child : value.childTasks()) {
            String key = key(child.key(), "childTask.key");
            if (children.putIfAbsent(key, child) != null) throw invalid("Duplicate child Task key");
            validateTask(new TaskDraft(child.title(), child.goal(), child.description(),
                    child.constraints(), child.acceptanceCriteria()), "childTask");
        }
        Map<String, PlanStepDraft> steps = new LinkedHashMap<>();
        Set<String> assigned = new HashSet<>();
        for (PlanStepDraft step : value.steps()) {
            String stepKey = key(step.stepKey(), "step.stepKey");
            if (steps.putIfAbsent(stepKey, step) != null) throw invalid("Duplicate PlanStep key");
            String childKey = key(step.childTaskKey(), "step.childTaskKey");
            if (!children.containsKey(childKey) || !assigned.add(childKey)) {
                throw invalid("Every child Task must be assigned exactly once");
            }
            bounded(step.dependsOnStepKeys(), "dependsOnStepKeys", 50);
            optional(step.requiredCapability(), 160);
            text(step.expectedOutput(), "expectedOutput", 8_000);
            bounded(step.acceptanceCriteria(), "step.acceptanceCriteria", 100);
        }
        if (assigned.size() != children.size()) throw invalid("Unassigned child Task");
        Map<String, Integer> colors = new HashMap<>();
        steps.forEach((stepKey, step) -> step.dependsOnStepKeys().forEach(dependency -> {
            String key = key(dependency, "dependency");
            if (key.equals(stepKey) || !steps.containsKey(key)) {
                throw invalid("PlanStep dependency is invalid");
            }
        }));
        steps.keySet().forEach(step -> visit(step, steps, colors));
    }

    private void visit(String key, Map<String, PlanStepDraft> graph, Map<String, Integer> colors) {
        int color = colors.getOrDefault(key, 0);
        if (color == 1) throw invalid("TaskPlan dependency graph contains a cycle");
        if (color == 2) return;
        colors.put(key, 1);
        graph.get(key).dependsOnStepKeys().forEach(value -> visit(value.trim(), graph, colors));
        colors.put(key, 2);
    }

    private void validateTask(TaskDraft value, String field) {
        if (value == null) throw invalid(field + " is required");
        text(value.title(), field + ".title", 200);
        text(value.goal(), field + ".goal", 8_000);
        optional(value.description(), 8_000);
        bounded(value.constraints(), field + ".constraints", 100);
        bounded(value.acceptanceCriteria(), field + ".acceptanceCriteria", 100);
    }

    private List<String> bounded(List<String> values, String field, int maximum) {
        if (values == null || values.size() > maximum) throw invalid(field + " exceeds limit");
        values.forEach(value -> text(value, field, 4_000));
        return values;
    }

    private void verifyPayload(String hash, String payload, String noun) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES
                || !sha256(payload).equals(hash)) {
            throw invalid("Project intake " + noun + " hash or size is invalid");
        }
    }

    private JobView view(ProjectIntakeJob value) {
        ProjectIntakeInspection inspected = value.inspectionJson() == null
                ? null : inspection(value.inspectionJson());
        InspectionView inspection = inspected == null ? null : new InspectionView(
                value.inspectionHash(), inspected.headCommit(), inspected.trackedFileCount(),
                inspected.trackedPaths(),
                inspected.selectedFiles().stream().map(file -> new InspectedFileView(
                        file.path(), file.sha256(), file.truncated())).toList());
        return new JobView(value.id(), value.tenantId(), value.ownerId(), value.projectId(), value.projectDirectoryId(),
                value.sourceRepositoryId(), value.conversationId(), value.agentId(),
                value.goal(), value.state(), value.attempt(),
                value.sourceHeadCommit(), inspection, value.proposalHash(),
                value.proposalJson() == null ? null : proposal(value.proposalJson()),
                value.agentRunId(), value.blueprintId(), value.rootTaskId(), value.taskPlanId(),
                value.safeErrorCode(), value.reviewedBy(), value.reviewReason(), value.reviewedAt(),
                value.revision(), value.createdAt(), value.startedAt(), value.updatedAt(),
                value.completedAt(), value.workspaceCleanedAt());
    }

    private static ProjectIntakeJob copy(
            ProjectIntakeJob value, ProjectIntakeState state, int attempt,
            String claimOwner, String claimToken, long fencingToken, Instant leaseUntil,
            String workspaceRef, String sourceHeadCommit, String inspectionHash,
            String inspectionJson, String proposalHash, String proposalJson, String agentRunId,
            String blueprintId, String rootTaskId, String taskPlanId, String safeErrorCode,
            String reviewedBy, String reviewReason, Instant reviewedAt, long revision,
            Instant startedAt, Instant updatedAt, Instant completedAt,
            Instant workspaceCleanedAt) {
        return new ProjectIntakeJob(value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.sourceRepositoryId(), value.conversationId(),
                value.agentId(), value.goal(), value.idempotencyHash(),
                value.inputHash(), state, attempt, claimOwner, claimToken, fencingToken, leaseUntil,
                workspaceRef, sourceHeadCommit, inspectionHash, inspectionJson, proposalHash,
                proposalJson, agentRunId, blueprintId, rootTaskId, taskPlanId, safeErrorCode,
                reviewedBy, reviewReason, reviewedAt, revision, value.createdAt(), startedAt,
                updatedAt, completedAt, workspaceCleanedAt);
    }

    private static String key(String value, String field) {
        String key = text(value, field, 64);
        if (!key.matches("[a-z][a-z0-9_-]{0,63}")) throw invalid(field + " is invalid");
        return key;
    }

    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw invalid(field + " is required and bounded");
        }
        return value.trim();
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > maximum) throw invalid("Optional value exceeds limit");
        return value.trim();
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,120}")
                ? value : "PROJECT_INTAKE_FAILED";
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash Project intake evidence", error);
        }
    }

    private static void uuid(String value, String noun) {
        try { UUID.fromString(value); } catch (RuntimeException error) { throw notFound(noun); }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "PROJECT_INTAKE_INVALID");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private static BusinessException leaseLost() {
        return conflict("Project intake worker lease was lost", "PROJECT_INTAKE_LEASE_LOST");
    }

    private static BusinessException notFound(String noun) {
        return new BusinessException(noun + " not found", HttpStatus.NOT_FOUND,
                "PROJECT_INTAKE_NOT_FOUND");
    }
}
