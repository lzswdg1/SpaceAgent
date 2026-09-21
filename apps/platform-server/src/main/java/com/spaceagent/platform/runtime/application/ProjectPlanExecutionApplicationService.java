package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectCodingJob;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobRepository;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecution;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionControl;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionControlException;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionDesiredState;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionRepository;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectPlanExecutionApplicationService
        implements ProjectPlanExecutionApplicationApi {
    private final ProjectPlanExecutionRepository executions;
    private final ProjectCodingJobRepository jobs;
    private final TimeProvider time;

    public ProjectPlanExecutionApplicationService(
            ProjectPlanExecutionRepository executions,
            ProjectCodingJobRepository jobs,
            TimeProvider time) {
        this.executions = executions;
        this.jobs = jobs;
        this.time = time;
    }

    @Override
    @Transactional
    public ExecutionView start(StartCommand command) {
        StartInput input = normalize(command);
        String idempotencyHash = hash(input.idempotencyKey());
        String inputHash = hash(String.join("\n", Arrays.asList(
                input.tenantId(), input.ownerId(), input.projectId(),
                input.projectDirectoryId(), input.conversationId(),
                input.sourceRepositoryId(), input.rootTaskId(), input.taskPlanId(),
                input.agentId(), input.primaryConfigurationHash(), input.reviewerAgentId(),
                input.baseRef())));
        ProjectPlanExecution existing = executions.findByTaskPlanId(
                input.tenantId(), input.ownerId(), input.taskPlanId()).orElse(null);
        if (existing != null) {
            return replay(existing, inputHash);
        }

        Instant now = time.now();
        ProjectPlanExecution created = new ProjectPlanExecution(
                input.id(), input.tenantId(), input.ownerId(), input.projectId(),
                input.projectDirectoryId(), input.conversationId(),
                input.sourceRepositoryId(), input.rootTaskId(), input.taskPlanId(),
                input.agentId(), input.primaryConfigurationHash(), input.reviewerAgentId(),
                input.baseRef(), idempotencyHash, inputHash, ProjectPlanExecutionState.READY,
                null, 0, 1, now, null, now, null);
        if (executions.insertIfAbsent(created)) {
            return view(created);
        }
        ProjectPlanExecution winner = executions.findByTaskPlanId(
                input.tenantId(), input.ownerId(), input.taskPlanId()).orElse(null);
        if (winner != null) {
            return replay(winner, inputHash);
        }
        throw conflict("PROJECT_PLAN_EXECUTION_PERSISTENCE_CONFLICT");
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionView get(Query query) {
        ProjectPlanExecution value = scoped(query.tenantId(), query.ownerId(), query.projectId(),
                query.executionId(), false);
        if (query.taskPlanId() != null
                && !value.taskPlanId().equals(uuid(query.taskPlanId()))) {
            throw missing();
        }
        return view(value);
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionPage list(ListQuery query) {
        String projectId = uuid(query.projectId());
        String tenantId = text(query.tenantId(), "tenantId", 36);
        String ownerId = text(query.ownerId(), "ownerId", 36);
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(100, query.pageSize()));
        if (query.taskPlanId() != null) {
            Optional<ProjectPlanExecution> value = executions.findByTaskPlanId(
                            tenantId, ownerId, uuid(query.taskPlanId()))
                    .filter(execution -> execution.projectId().equals(projectId));
            List<ExecutionView> items = page == 1
                    ? value.map(this::view).map(List::of).orElseGet(List::of)
                    : List.of();
            return new ExecutionPage(items, page, size, value.isPresent() ? 1 : 0);
        }
        List<ExecutionView> items = executions.findByProject(
                        projectId, ownerId, (page - 1) * size, size).stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .map(this::view)
                .toList();
        return new ExecutionPage(items, page, size,
                executions.countByProject(projectId, ownerId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionView> getActiveByTaskPlan(QueryByTaskPlan query) {
        String tenantId = text(query.tenantId(), "tenantId", 36);
        String ownerId = text(query.ownerId(), "ownerId", 36);
        return executions.findByTaskPlanId(tenantId, ownerId, uuid(query.taskPlanId()))
                .filter(value -> Set.of(
                        ProjectPlanExecutionState.READY,
                        ProjectPlanExecutionState.RUNNING,
                        ProjectPlanExecutionState.PAUSING,
                        ProjectPlanExecutionState.PAUSED,
                        ProjectPlanExecutionState.CANCELLING).contains(value.state()))
                .map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionView> getByTaskPlan(QueryByTaskPlan query) {
        String tenantId = text(query.tenantId(), "tenantId", 36);
        String ownerId = text(query.ownerId(), "ownerId", 36);
        return executions.findByTaskPlanId(tenantId, ownerId, uuid(query.taskPlanId()))
                .map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public ControlView getControl(Query query) {
        ProjectPlanExecution value = scoped(query.tenantId(), query.ownerId(), query.projectId(),
                query.executionId(), false);
        if (query.taskPlanId() != null
                && !value.taskPlanId().equals(uuid(query.taskPlanId()))) {
            throw missing();
        }
        int activeJobCount = jobs.findActiveByExecutionId(value.id(), value.ownerId()).stream()
                .filter(job -> job.tenantId().equals(value.tenantId()))
                .filter(job -> job.projectId().equals(value.projectId()))
                .toList().size();
        return new ControlView(
                value.id(), value.state(), value.desiredState(), value.safeErrorCode(),
                value.controlReason() != null, value.revision(), activeJobCount,
                value.updatedAt(), value.completedAt());
    }

    @Override
    @Transactional
    public ExecutionView begin(TransitionCommand command) {
        ProjectPlanExecution current = scoped(command, true);
        if (current.state() != ProjectPlanExecutionState.READY) {
            return view(current);
        }
        Instant now = time.now();
        return save(current, copy(current, ProjectPlanExecutionState.RUNNING, null,
                current.attempt(), current.revision() + 1,
                current.startedAt() == null ? now : current.startedAt(), now, null));
    }

    @Override
    @Transactional
    public ExecutionView requestPause(PauseCommand command) {
        ProjectPlanExecution current = scoped(command.tenantId(), command.ownerId(),
                command.projectId(), command.executionId(), true);
        if (command.expectedRevision() < 1 || current.revision() != command.expectedRevision()) {
            throw conflict("PROJECT_PLAN_EXECUTION_STALE_REVISION");
        }
        try {
            ProjectPlanExecutionControl control = current.control().requestPause(command.reason());
            return save(current, current.withControl(control, time.now()));
        } catch (ProjectPlanExecutionControlException error) {
            throw conflict(error.safeErrorCode());
        }
    }

    @Override
    @Transactional
    public ExecutionView acknowledgePause(TransitionCommand command) {
        ProjectPlanExecution current = scoped(command, true);
        try {
            ProjectPlanExecutionControl control = current.control().acknowledgePause();
            return save(current, current.withControl(control, time.now()));
        } catch (ProjectPlanExecutionControlException error) {
            throw conflict(error.safeErrorCode());
        }
    }

    @Override
    @Transactional
    public ExecutionView resume(ResumeCommand command) {
        ProjectPlanExecution current = scoped(command.tenantId(), command.ownerId(),
                command.projectId(), command.executionId(), true);
        if (command.expectedRevision() < 1 || current.revision() != command.expectedRevision()) {
            throw conflict("PROJECT_PLAN_EXECUTION_STALE_REVISION");
        }
        try {
            ProjectPlanExecutionControl control = current.control().resume();
            return save(current, current.withControl(control, time.now()));
        } catch (ProjectPlanExecutionControlException error) {
            throw conflict(error.safeErrorCode());
        }
    }

    @Override
    @Transactional
    public ExecutionView requestCancel(CancelCommand command) {
        ProjectPlanExecution current = scoped(command.tenantId(), command.ownerId(),
                command.projectId(), command.executionId(), true);
        if (command.expectedRevision() < 1 || current.revision() != command.expectedRevision()) {
            throw conflict("PROJECT_PLAN_EXECUTION_STALE_REVISION");
        }
        try {
            ProjectPlanExecutionControl control = current.control().requestCancel(command.reason());
            return save(current, current.withControl(control, time.now()));
        } catch (ProjectPlanExecutionControlException error) {
            throw conflict(error.safeErrorCode());
        }
    }

    @Override
    @Transactional
    public ExecutionView acknowledgeCancel(TransitionCommand command) {
        ProjectPlanExecution current = scoped(command, true);
        try {
            ProjectPlanExecutionControl control = current.control().acknowledgeCancel();
            return save(current, current.withControl(control, time.now()));
        } catch (ProjectPlanExecutionControlException error) {
            throw conflict(error.safeErrorCode());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExecutionView> controlTransitions(int limit) {
        if (limit < 1 || limit > 100) {
            throw invalid("control transition limit is invalid");
        }
        return executions.findControlTransitions(limit).stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public ExecutionView block(TransitionCommand command, String safeErrorCode) {
        ProjectPlanExecution current = scoped(command, true);
        String error = safeError(safeErrorCode);
        if (current.state() == ProjectPlanExecutionState.BLOCKED
                && error.equals(current.safeErrorCode())) {
            return view(current);
        }
        if (terminal(current.state())) {
            throw conflict("PROJECT_PLAN_EXECUTION_STATE_CONFLICT");
        }
        Instant now = time.now();
        return save(current, copyWithControl(current, ProjectPlanExecutionState.BLOCKED, error,
                current.attempt(), current.revision() + 1, current.startedAt(), now, now,
                current.desiredState(), error));
    }

    @Override
    @Transactional
    public ExecutionView waitForReconciliation(ReconciliationWaitCommand command) {
        if (uuid(command.reconciliationStepId()) == null) throw invalid("reconciliationStepId is invalid");
        ProjectPlanExecution current = scoped(command.tenantId(), command.ownerId(),
                command.projectId(), command.executionId(), true);
        if (!current.taskPlanId().equals(uuid(command.taskPlanId())) || command.expectedRevision() < 1
                || current.revision() != command.expectedRevision()) {
            throw conflict("PROJECT_PLAN_EXECUTION_STALE_REVISION");
        }
        if (current.state() == ProjectPlanExecutionState.BLOCKED
                && "PROJECT_RECONCILIATION_REQUIRED".equals(current.safeErrorCode())) return view(current);
        if (terminal(current.state())) throw conflict("PROJECT_PLAN_EXECUTION_STATE_CONFLICT");
        Instant now = time.now();
        return save(current, copyWithControl(current, ProjectPlanExecutionState.BLOCKED,
                "PROJECT_RECONCILIATION_REQUIRED", current.attempt(), current.revision() + 1,
                current.startedAt(), now, now, current.desiredState(), "PROJECT_RECONCILIATION_REQUIRED"));
    }

    @Override
    @Transactional
    public ExecutionView resumeAfterReconciliation(ReconciliationResumeCommand command) {
        if (uuid(command.reconciliationStepId()) == null) throw invalid("reconciliationStepId is invalid");
        ProjectPlanExecution current = scoped(command.tenantId(), command.ownerId(),
                command.projectId(), command.executionId(), true);
        if (!current.taskPlanId().equals(uuid(command.taskPlanId())) || command.expectedRevision() < 1
                || current.revision() != command.expectedRevision()) {
            throw conflict("PROJECT_PLAN_EXECUTION_STALE_REVISION");
        }
        if (current.state() != ProjectPlanExecutionState.BLOCKED
                || !"PROJECT_RECONCILIATION_REQUIRED".equals(current.safeErrorCode())) {
            throw conflict("PROJECT_PLAN_EXECUTION_RECONCILIATION_NOT_WAITING");
        }
        Instant now = time.now();
        return save(current, copyWithControl(current, ProjectPlanExecutionState.RUNNING, null,
                current.attempt(), current.revision() + 1,
                current.startedAt() == null ? now : current.startedAt(), now, null,
                ProjectPlanExecutionDesiredState.RUNNING, null));
    }

    @Override
    @Transactional
    public ExecutionView fail(TransitionCommand command, String safeErrorCode) {
        ProjectPlanExecution current = scoped(command, true);
        String error = safeError(safeErrorCode);
        if (current.state() == ProjectPlanExecutionState.FAILED
                && error.equals(current.safeErrorCode())) {
            return view(current);
        }
        if (terminal(current.state())) {
            throw conflict("PROJECT_PLAN_EXECUTION_STATE_CONFLICT");
        }
        Instant now = time.now();
        return save(current, copyWithControl(current, ProjectPlanExecutionState.FAILED, error,
                current.attempt(), current.revision() + 1, current.startedAt(), now, now,
                ProjectPlanExecutionDesiredState.RUNNING, null));
    }

    @Override
    @Transactional
    public ExecutionView complete(TransitionCommand command) {
        ProjectPlanExecution current = scoped(command, true);
        if (current.state() == ProjectPlanExecutionState.COMPLETED) {
            return view(current);
        }
        if (current.state() != ProjectPlanExecutionState.RUNNING) {
            throw conflict("PROJECT_PLAN_EXECUTION_STATE_CONFLICT");
        }
        Instant now = time.now();
        return save(current, copy(current, ProjectPlanExecutionState.COMPLETED, null,
                current.attempt(), current.revision() + 1, current.startedAt(), now, now));
    }

    @Override
    @Transactional
    public ExecutionView reset(TransitionCommand command) {
        ProjectPlanExecution current = scoped(command, true);
        if (current.state() == ProjectPlanExecutionState.READY) {
            return view(current);
        }
        if (current.state() != ProjectPlanExecutionState.FAILED) {
            throw conflict("PROJECT_PLAN_EXECUTION_STATE_CONFLICT");
        }
        Instant now = time.now();
        return save(current, copy(current, ProjectPlanExecutionState.READY, null,
                current.attempt() + 1, current.revision() + 1, null, now, null));
    }

    private ExecutionView replay(ProjectPlanExecution execution, String inputHash) {
        if (!execution.inputHash().equals(inputHash)) {
            throw conflict("PROJECT_PLAN_EXECUTION_INPUT_CONFLICT");
        }
        return view(execution);
    }

    private ProjectPlanExecution scoped(TransitionCommand command, boolean lock) {
        ProjectPlanExecution value = scoped(command.tenantId(), command.ownerId(),
                command.projectId(), command.executionId(), lock);
        if (!value.taskPlanId().equals(uuid(command.taskPlanId()))) {
            throw missing();
        }
        return value;
    }

    private ProjectPlanExecution scoped(
            String tenantId, String ownerId, String projectId, String executionId,
            boolean lock) {
        String normalizedTenant = text(tenantId, "tenantId", 36);
        String normalizedOwner = text(ownerId, "ownerId", 36);
        String normalizedProject = uuid(projectId);
        Optional<ProjectPlanExecution> found = lock
                ? executions.findByIdForUpdate(uuid(executionId))
                : executions.findById(uuid(executionId));
        return found.filter(value -> value.tenantId().equals(normalizedTenant))
                .filter(value -> value.ownerId().equals(normalizedOwner))
                .filter(value -> value.projectId().equals(normalizedProject))
                .orElseThrow(ProjectPlanExecutionApplicationService::missing);
    }

    private ExecutionView save(ProjectPlanExecution current, ProjectPlanExecution updated) {
        executions.saveLifecycle(current, updated);
        return view(updated);
    }

    private ExecutionView view(ProjectPlanExecution value) {
        List<ActiveJobView> activeJobs = jobs.findActiveByExecutionId(
                        value.id(), value.ownerId()).stream()
                .filter(job -> job.tenantId().equals(value.tenantId()))
                .filter(job -> job.projectId().equals(value.projectId()))
                .map(ProjectPlanExecutionApplicationService::activeJob)
                .toList();
        return new ExecutionView(
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.conversationId(), value.sourceRepositoryId(),
                value.rootTaskId(), value.taskPlanId(), value.agentId(), value.primaryConfigurationHash(),
                value.reviewerAgentId(), value.baseRef(), value.state(),
                value.safeErrorCode(), value.attempt(), value.revision(), value.createdAt(),
                value.startedAt(), value.updatedAt(), value.completedAt(), activeJobs);
    }

    private static ActiveJobView activeJob(ProjectCodingJob job) {
        return new ActiveJobView(job.id(), job.planStepId(), job.state(), job.workspaceId(),
                job.codingRunId(), job.reviewerRunId(), job.revision(), job.updatedAt());
    }

    private static ProjectPlanExecution copy(
            ProjectPlanExecution value, ProjectPlanExecutionState state, String error,
            int attempt, long revision, Instant startedAt, Instant updatedAt,
            Instant completedAt) {
        return new ProjectPlanExecution(
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.conversationId(), value.sourceRepositoryId(),
                value.rootTaskId(), value.taskPlanId(), value.agentId(), value.primaryConfigurationHash(),
                value.reviewerAgentId(), value.baseRef(), value.idempotencyHash(),
                value.inputHash(), state, error, attempt, revision, value.createdAt(), startedAt,
                updatedAt, completedAt);
    }

    private static ProjectPlanExecution copyWithControl(
            ProjectPlanExecution value, ProjectPlanExecutionState state, String error,
            int attempt, long revision, Instant startedAt, Instant updatedAt,
            Instant completedAt, ProjectPlanExecutionDesiredState desiredState,
            String controlReason) {
        return new ProjectPlanExecution(
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.conversationId(), value.sourceRepositoryId(),
                value.rootTaskId(), value.taskPlanId(), value.agentId(), value.primaryConfigurationHash(),
                value.reviewerAgentId(), value.baseRef(), value.idempotencyHash(),
                value.inputHash(), state, error, attempt, revision, value.createdAt(), startedAt,
                updatedAt, completedAt, desiredState, controlReason);
    }

    private static StartInput normalize(StartCommand command) {
        String key = text(command.idempotencyKey(), "idempotencyKey", 200);
        if (key.length() < 8) {
            throw invalid("Idempotency-Key is too short");
        }
        return new StartInput(
                uuid(command.id()), text(command.tenantId(), "tenantId", 36),
                text(command.ownerId(), "ownerId", 36), uuid(command.projectId()),
                uuid(command.projectDirectoryId()),
                text(command.conversationId(), "conversationId", 36),
                uuid(command.sourceRepositoryId()), uuid(command.rootTaskId()),
                uuid(command.taskPlanId()), text(command.agentId(), "agentId", 36),
                normalizeOptional(command.primaryConfigurationHash()), text(command.reviewerAgentId(), "reviewerAgentId", 36),
                text(command.baseRef(), "baseRef", 240), key);
    }

    private static boolean terminal(ProjectPlanExecutionState state) {
        return state == ProjectPlanExecutionState.COMPLETED
                || state == ProjectPlanExecutionState.FAILED
                || state == ProjectPlanExecutionState.CANCELLED
                || state == ProjectPlanExecutionState.BLOCKED;
    }

    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw invalid(field + " is invalid");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String uuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (Exception error) {
            throw invalid("UUID is invalid");
        }
    }

    private static String safeError(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,120}")) {
            throw invalid("safeErrorCode is invalid");
        }
        return value;
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(
                message, HttpStatus.BAD_REQUEST, "PROJECT_PLAN_EXECUTION_INVALID");
    }

    private static BusinessException conflict(String code) {
        return new BusinessException("Project Plan Execution conflict", HttpStatus.CONFLICT, code);
    }

    private static BusinessException missing() {
        return new BusinessException("Project Plan Execution not found", HttpStatus.NOT_FOUND,
                "PROJECT_PLAN_EXECUTION_NOT_FOUND");
    }

    private record StartInput(
            String id, String tenantId, String ownerId, String projectId,
            String projectDirectoryId, String conversationId, String sourceRepositoryId,
            String rootTaskId, String taskPlanId, String agentId, String primaryConfigurationHash,
            String reviewerAgentId, String baseRef, String idempotencyKey) {
    }
}
