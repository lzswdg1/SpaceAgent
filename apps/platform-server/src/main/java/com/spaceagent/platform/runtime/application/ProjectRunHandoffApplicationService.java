package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoff;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffRepository;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;
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
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectRunHandoffApplicationService implements ProjectRunHandoffApplicationApi {
    private final ProjectRunHandoffRepository repository;
    private final IdGenerator ids;
    private final TimeProvider time;

    public ProjectRunHandoffApplicationService(
            ProjectRunHandoffRepository repository, IdGenerator ids, TimeProvider time) {
        this.repository = repository;
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional
    public HandoffView create(CreateCommand command) {
        String idempotencyKey = text(command.idempotencyKey(), "idempotencyKey", 200);
        if (idempotencyKey.length() < 8) throw invalid("Idempotency-Key is too short");
        String idempotencyHash = hash(idempotencyKey);
        String inputHash = hash(String.join("\n", Arrays.asList(
                command.tenantId(), command.ownerId(), command.projectId(),
                command.projectDirectoryId(), command.sourceCodingJobId(),
                command.sourceRepositoryId(), command.rootTaskId(), command.taskId(),
                command.taskPlanId(), command.planStepId(), command.baseRef(),
                command.sourceAgentRunId(), command.workspaceId(), command.recoverySnapshotId(),
                command.recoverySnapshotHash(), command.targetConversationId(),
                command.targetAgentId(), command.reviewerAgentId(), command.targetCodingJobId())));
        ProjectRunHandoff existing = repository.findByIdempotency(
                command.tenantId(), command.ownerId(), idempotencyHash).orElse(null);
        if (existing != null) {
            if (!existing.inputHash().equals(inputHash)) {
                throw conflict("PROJECT_HANDOFF_IDEMPOTENCY_CONFLICT");
            }
            return view(existing);
        }
        Instant now = time.now();
        ProjectRunHandoff created = new ProjectRunHandoff(
                uuid(command.id()), text(command.tenantId(), "tenantId", 36),
                text(command.ownerId(), "ownerId", 36), uuid(command.projectId()),
                uuid(command.projectDirectoryId()), uuid(command.sourceRepositoryId()),
                uuid(command.rootTaskId()), uuid(command.taskId()),
                uuid(command.taskPlanId()), uuid(command.planStepId()),
                text(command.baseRef(), "baseRef", 240),
                uuid(command.sourceCodingJobId()),
                text(command.sourceAgentRunId(), "sourceAgentRunId", 36),
                uuid(command.workspaceId()), uuid(command.recoverySnapshotId()),
                sha256(command.recoverySnapshotHash()),
                text(command.targetConversationId(), "targetConversationId", 36),
                text(command.targetAgentId(), "targetAgentId", 36),
                text(command.reviewerAgentId(), "reviewerAgentId", 36),
                uuid(command.targetCodingJobId()), null, idempotencyHash, inputHash,
                ProjectRunHandoffState.PENDING, null, null, 0, null, null,
                0, null, 1, now, now, null);
        try {
            repository.insert(created);
            return view(created);
        } catch (DataIntegrityViolationException | IllegalStateException error) {
            ProjectRunHandoff winner = repository.findByIdempotency(
                    command.tenantId(), command.ownerId(), idempotencyHash).orElse(null);
            if (winner != null && winner.inputHash().equals(inputHash)) return view(winner);
            throw conflict("PROJECT_HANDOFF_PERSISTENCE_CONFLICT");
        }
    }

    @Override
    public HandoffView get(Query query) {
        return view(scoped(query.tenantId(), query.ownerId(), query.projectId(), query.handoffId(), false));
    }

    @Override
    public HandoffPage list(ListQuery query) {
        uuid(query.projectId());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(100, query.pageSize()));
        var items = repository.findByProject(query.projectId(), query.ownerId(),
                        (page - 1) * size, size).stream()
                .filter(value -> value.tenantId().equals(query.tenantId()))
                .map(ProjectRunHandoffApplicationService::view).toList();
        return new HandoffPage(items, page, size,
                repository.countByProject(query.projectId(), query.ownerId()));
    }

    @Override
    public Optional<HandoffView> findByTargetCodingJobId(String codingJobId) {
        return repository.findByTargetCodingJobId(uuid(codingJobId)).map(ProjectRunHandoffApplicationService::view);
    }

    @Override
    public Optional<HandoffView> findBySourceCodingJobId(String codingJobId) {
        return repository.findBySourceCodingJobId(uuid(codingJobId))
                .map(ProjectRunHandoffApplicationService::view);
    }

    @Override
    public HandoffView replayBySource(ReplayCommand command) {
        ProjectRunHandoff value = repository.findBySourceCodingJobId(uuid(command.sourceCodingJobId()))
                .orElseThrow(ProjectRunHandoffApplicationService::missing);
        if (!value.idempotencyHash().equals(hash(text(command.idempotencyKey(), "idempotencyKey", 200)))
                || !value.targetConversationId().equals(command.targetConversationId())
                || !value.targetAgentId().equals(command.targetAgentId())
                || !value.reviewerAgentId().equals(command.reviewerAgentId())) {
            throw conflict("PROJECT_HANDOFF_IDEMPOTENCY_CONFLICT");
        }
        return view(value);
    }

    @Override
    @Transactional
    public HandoffView attachTargetRun(String targetCodingJobId, String targetAgentRunId) {
        ProjectRunHandoff current = byTargetJobForUpdate(targetCodingJobId);
        if (current.targetAgentRunId() != null) {
            if (!current.targetAgentRunId().equals(targetAgentRunId)) {
                throw conflict("PROJECT_HANDOFF_TARGET_RUN_CONFLICT");
            }
            return view(current);
        }
        if (current.state() != ProjectRunHandoffState.PENDING) {
            throw conflict("PROJECT_HANDOFF_STATE_CONFLICT");
        }
        ProjectRunHandoff updated = copy(current, ProjectRunHandoffState.ACTIVE,
                text(targetAgentRunId, "targetAgentRunId", 36), current.memoryKey(), null,
                current.attempt(), null, null, current.fencingToken(), null,
                current.revision() + 1, time.now(), null);
        repository.saveLifecycle(current, updated);
        return view(updated);
    }

    @Override
    @Transactional
    public HandoffView readyForFinalization(String targetCodingJobId) {
        ProjectRunHandoff current = byTargetJobForUpdate(targetCodingJobId);
        if (current.state() == ProjectRunHandoffState.READY_TO_FINALIZE
                || current.state() == ProjectRunHandoffState.FINALIZING
                || current.state() == ProjectRunHandoffState.COMPLETED) return view(current);
        if (current.state() != ProjectRunHandoffState.ACTIVE || current.targetAgentRunId() == null) {
            throw conflict("PROJECT_HANDOFF_STATE_CONFLICT");
        }
        ProjectRunHandoff updated = copy(current, ProjectRunHandoffState.READY_TO_FINALIZE,
                current.targetAgentRunId(), current.memoryKey(), null, current.attempt(),
                null, null, current.fencingToken(), null, current.revision() + 1,
                time.now(), null);
        repository.saveLifecycle(current, updated);
        return view(updated);
    }

    @Override
    public Optional<FinalizationClaim> claimFinalization(
            String workerId, int leaseSeconds, int maximumAttempts) {
        Instant now = time.now();
        repository.promoteCompletedTargets(now);
        return repository.claimFinalization(text(workerId, "workerId", 160), ids.nextId(), now,
                        now.plus(Math.max(60, Math.min(1800, leaseSeconds)), ChronoUnit.SECONDS),
                        Math.max(1, Math.min(10, maximumAttempts)))
                .map(value -> new FinalizationClaim(view(value), value.claimToken(),
                        value.fencingToken(), value.leaseUntil()));
    }

    @Override
    public HandoffView heartbeat(FinalizationCommand command, int leaseSeconds) {
        return mutate(command, value -> copy(value, value.state(), value.targetAgentRunId(),
                value.memoryKey(), null, value.attempt(), value.claimOwner(), value.claimToken(),
                value.fencingToken(), time.now().plus(Math.max(60, Math.min(1800, leaseSeconds)),
                        ChronoUnit.SECONDS), value.revision() + 1, time.now(), null));
    }

    @Override
    public HandoffView complete(FinalizationCommand command, String memoryKey) {
        return mutate(command, value -> copy(value, ProjectRunHandoffState.COMPLETED,
                value.targetAgentRunId(), text(memoryKey, "memoryKey", 240), null,
                value.attempt(), null, null, value.fencingToken(), null,
                value.revision() + 1, time.now(), time.now()));
    }

    @Override
    public HandoffView fail(
            FinalizationCommand command, String safeErrorCode, boolean blocked) {
        return mutate(command, value -> copy(value,
                blocked ? ProjectRunHandoffState.BLOCKED : ProjectRunHandoffState.READY_TO_FINALIZE,
                value.targetAgentRunId(), value.memoryKey(), safe(safeErrorCode), value.attempt(),
                null, null, value.fencingToken(), null, value.revision() + 1,
                time.now(), blocked ? time.now() : null));
    }

    private HandoffView mutate(
            FinalizationCommand command,
            java.util.function.Function<ProjectRunHandoff, ProjectRunHandoff> mutation) {
        ProjectRunHandoff current = repository.findById(uuid(command.handoffId()))
                .orElseThrow(ProjectRunHandoffApplicationService::missing);
        if (current.state() != ProjectRunHandoffState.FINALIZING
                || !java.util.Objects.equals(current.claimOwner(), command.workerId())
                || !java.util.Objects.equals(current.claimToken(), command.claimToken())
                || current.fencingToken() != command.fencingToken()
                || current.leaseUntil() == null || !current.leaseUntil().isAfter(time.now())) {
            throw conflict("PROJECT_HANDOFF_LEASE_LOST");
        }
        ProjectRunHandoff updated = mutation.apply(current);
        if (!repository.saveClaimed(current, updated, time.now())) {
            throw conflict("PROJECT_HANDOFF_LEASE_LOST");
        }
        return view(updated);
    }

    private ProjectRunHandoff byTargetJobForUpdate(String codingJobId) {
        ProjectRunHandoff found = repository.findByTargetCodingJobId(uuid(codingJobId))
                .orElseThrow(ProjectRunHandoffApplicationService::missing);
        return repository.findByIdForUpdate(found.id())
                .orElseThrow(ProjectRunHandoffApplicationService::missing);
    }

    private ProjectRunHandoff scoped(
            String tenantId, String ownerId, String projectId, String handoffId, boolean lock) {
        uuid(projectId);
        return (lock ? repository.findByIdForUpdate(uuid(handoffId))
                : repository.findById(uuid(handoffId)))
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.projectId().equals(projectId))
                .orElseThrow(ProjectRunHandoffApplicationService::missing);
    }

    private static ProjectRunHandoff copy(
            ProjectRunHandoff value, ProjectRunHandoffState state, String targetRun,
            String memoryKey, String error, int attempt, String claimOwner, String claimToken,
            long fencingToken, Instant leaseUntil, long revision, Instant updatedAt,
            Instant completedAt) {
        return new ProjectRunHandoff(
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.sourceRepositoryId(), value.rootTaskId(),
                value.taskId(), value.taskPlanId(), value.planStepId(), value.baseRef(),
                value.sourceCodingJobId(), value.sourceAgentRunId(),
                value.workspaceId(), value.recoverySnapshotId(), value.recoverySnapshotHash(),
                value.targetConversationId(), value.targetAgentId(), value.reviewerAgentId(),
                value.targetCodingJobId(), targetRun,
                value.idempotencyHash(), value.inputHash(), state, memoryKey, error, attempt,
                claimOwner, claimToken, fencingToken, leaseUntil, revision, value.createdAt(),
                updatedAt, completedAt);
    }

    private static HandoffView view(ProjectRunHandoff value) {
        return new HandoffView(
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.sourceRepositoryId(), value.rootTaskId(),
                value.taskId(), value.taskPlanId(), value.planStepId(), value.baseRef(),
                value.sourceCodingJobId(), value.sourceAgentRunId(),
                value.workspaceId(), value.recoverySnapshotId(), value.recoverySnapshotHash(),
                value.targetConversationId(), value.targetAgentId(), value.reviewerAgentId(),
                value.targetCodingJobId(), value.targetAgentRunId(),
                value.state(), value.memoryKey(), value.safeErrorCode(), value.attempt(),
                value.revision(), value.createdAt(), value.updatedAt(), value.completedAt());
    }

    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw invalid(field + " is invalid");
        }
        return value.trim();
    }

    private static String uuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (Exception error) {
            throw missing();
        }
    }

    private static String sha256(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid("recoverySnapshotHash is invalid");
        }
        return value;
    }

    private static String safe(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,120}")
                ? value : "PROJECT_HANDOFF_FINALIZATION_FAILED";
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
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "PROJECT_HANDOFF_INVALID");
    }

    private static BusinessException conflict(String code) {
        return new BusinessException("Project handoff conflict", HttpStatus.CONFLICT, code);
    }

    private static BusinessException missing() {
        return new BusinessException(
                "Project handoff not found", HttpStatus.NOT_FOUND, "PROJECT_HANDOFF_NOT_FOUND");
    }
}
