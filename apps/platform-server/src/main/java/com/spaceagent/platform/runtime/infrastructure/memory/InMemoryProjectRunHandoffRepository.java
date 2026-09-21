package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.ProjectRunHandoff;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffRepository;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryProjectRunHandoffRepository implements ProjectRunHandoffRepository {
    private final Map<String, ProjectRunHandoff> values = new LinkedHashMap<>();

    @Override
    public synchronized void insert(ProjectRunHandoff handoff) {
        boolean duplicate = values.containsKey(handoff.id()) || values.values().stream().anyMatch(value ->
                value.tenantId().equals(handoff.tenantId())
                        && value.ownerId().equals(handoff.ownerId())
                        && value.idempotencyHash().equals(handoff.idempotencyHash()));
        if (duplicate) throw new IllegalStateException("Project Run Handoff exists");
        values.put(handoff.id(), handoff);
    }

    @Override
    public synchronized Optional<ProjectRunHandoff> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public synchronized Optional<ProjectRunHandoff> findByIdForUpdate(String id) {
        return findById(id);
    }

    @Override
    public synchronized Optional<ProjectRunHandoff> findByIdempotency(
            String tenantId, String ownerId, String hash) {
        return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.idempotencyHash().equals(hash))
                .findFirst();
    }

    @Override
    public synchronized Optional<ProjectRunHandoff> findByTargetCodingJobId(String codingJobId) {
        return values.values().stream()
                .filter(value -> value.targetCodingJobId().equals(codingJobId)).findFirst();
    }

    @Override
    public synchronized Optional<ProjectRunHandoff> findBySourceCodingJobId(String codingJobId) {
        return values.values().stream()
                .filter(value -> value.sourceCodingJobId().equals(codingJobId)).findFirst();
    }

    @Override
    public synchronized List<ProjectRunHandoff> findByProject(
            String projectId, String ownerId, int offset, int limit) {
        return values.values().stream()
                .filter(value -> value.projectId().equals(projectId) && value.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(ProjectRunHandoff::createdAt).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long countByProject(String projectId, String ownerId) {
        return values.values().stream()
                .filter(value -> value.projectId().equals(projectId) && value.ownerId().equals(ownerId))
                .count();
    }

    @Override
    public synchronized void saveLifecycle(ProjectRunHandoff expected, ProjectRunHandoff updated) {
        ProjectRunHandoff current = values.get(expected.id());
        if (current == null || current.revision() != expected.revision()) {
            throw new IllegalStateException("Project Run Handoff revision conflict");
        }
        values.put(updated.id(), updated);
    }

    @Override
    public int promoteCompletedTargets(Instant now) {
        return 0;
    }

    @Override
    public synchronized Optional<ProjectRunHandoff> claimFinalization(
            String workerId, String claimToken, Instant now, Instant leaseUntil, int maximumAttempts) {
        values.values().stream()
                .filter(value -> value.state() == ProjectRunHandoffState.FINALIZING)
                .filter(value -> value.leaseUntil() != null && !value.leaseUntil().isAfter(now))
                .filter(value -> value.attempt() >= maximumAttempts)
                .toList().forEach(value -> values.put(value.id(), copy(
                        value, ProjectRunHandoffState.BLOCKED, value.targetAgentRunId(),
                        value.memoryKey(), "PROJECT_HANDOFF_FINALIZATION_ATTEMPTS_EXHAUSTED",
                        value.attempt(), null, null, value.fencingToken(), null,
                        value.revision() + 1, now, now)));
        ProjectRunHandoff candidate = values.values().stream()
                .filter(value -> value.targetAgentRunId() != null)
                .filter(value -> value.attempt() < maximumAttempts)
                .filter(value -> value.state() == ProjectRunHandoffState.READY_TO_FINALIZE
                        || value.state() == ProjectRunHandoffState.FINALIZING
                        && value.leaseUntil() != null && !value.leaseUntil().isAfter(now))
                .min(Comparator.comparing(ProjectRunHandoff::createdAt)).orElse(null);
        if (candidate == null) return Optional.empty();
        ProjectRunHandoff claimed = copy(candidate, ProjectRunHandoffState.FINALIZING,
                candidate.targetAgentRunId(), candidate.memoryKey(), null, candidate.attempt() + 1,
                workerId, claimToken, candidate.fencingToken() + 1, leaseUntil,
                candidate.revision() + 1, now, null);
        values.put(claimed.id(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized boolean saveClaimed(
            ProjectRunHandoff expected, ProjectRunHandoff updated, Instant now) {
        ProjectRunHandoff current = values.get(expected.id());
        if (current == null || current.revision() != expected.revision()
                || current.state() != ProjectRunHandoffState.FINALIZING
                || !java.util.Objects.equals(current.claimToken(), expected.claimToken())
                || current.fencingToken() != expected.fencingToken()
                || current.leaseUntil() == null || !current.leaseUntil().isAfter(now)) return false;
        values.put(updated.id(), updated);
        return true;
    }

    public static ProjectRunHandoff copy(
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
}
