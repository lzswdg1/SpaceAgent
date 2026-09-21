package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.ProjectIntakeJob;
import com.spaceagent.platform.project.domain.ProjectIntakeRepository;
import com.spaceagent.platform.project.domain.ProjectIntakeState;
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
public class InMemoryProjectIntakeRepository implements ProjectIntakeRepository {
    private final Map<String, ProjectIntakeJob> values = new LinkedHashMap<>();

    @Override
    public synchronized void insert(ProjectIntakeJob job) {
        if (values.containsKey(job.id()) || values.values().stream().anyMatch(value ->
                value.tenantId().equals(job.tenantId())
                        && value.ownerId().equals(job.ownerId())
                        && value.idempotencyHash().equals(job.idempotencyHash()))
                || active(job.state()) && values.values().stream().anyMatch(value ->
                        value.projectDirectoryId().equals(job.projectDirectoryId())
                                && active(value.state()))) {
            throw new IllegalStateException("Project intake already exists");
        }
        values.put(job.id(), job);
    }

    @Override
    public synchronized Optional<ProjectIntakeJob> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public synchronized Optional<ProjectIntakeJob> findByIdForUpdate(String id) {
        return findById(id);
    }

    @Override
    public synchronized Optional<ProjectIntakeJob> findByIdempotency(
            String tenantId, String ownerId, String idempotencyHash) {
        return values.values().stream().filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.idempotencyHash().equals(idempotencyHash)).findFirst();
    }

    @Override
    public synchronized List<ProjectIntakeJob> findByDirectory(
            String projectDirectoryId, String ownerId, int offset, int limit) {
        return values.values().stream()
                .filter(value -> value.projectDirectoryId().equals(projectDirectoryId))
                .filter(value -> value.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(ProjectIntakeJob::createdAt).reversed()
                        .thenComparing(ProjectIntakeJob::id))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long countByDirectory(String projectDirectoryId, String ownerId) {
        return values.values().stream()
                .filter(value -> value.projectDirectoryId().equals(projectDirectoryId))
                .filter(value -> value.ownerId().equals(ownerId)).count();
    }

    @Override
    public synchronized Optional<ProjectIntakeJob> claim(
            String workerId, String claimToken, Instant now, Instant leaseUntil,
            int maximumAttempts) {
        ProjectIntakeJob candidate = values.values().stream()
                .filter(value -> value.attempt() < maximumAttempts)
                .filter(value -> value.state() == ProjectIntakeState.PENDING
                        || value.state() == ProjectIntakeState.RUNNING
                        && value.leaseUntil() != null && !value.leaseUntil().isAfter(now))
                .min(Comparator.comparing(ProjectIntakeJob::createdAt)
                        .thenComparing(ProjectIntakeJob::id)).orElse(null);
        if (candidate == null) return Optional.empty();
        ProjectIntakeJob claimed = copy(candidate, ProjectIntakeState.RUNNING,
                candidate.attempt() + 1, workerId, claimToken, candidate.fencingToken() + 1,
                leaseUntil, candidate.workspaceRef(), candidate.sourceHeadCommit(),
                candidate.inspectionHash(), candidate.inspectionJson(), candidate.proposalHash(),
                candidate.proposalJson(), candidate.agentRunId(), candidate.blueprintId(),
                candidate.rootTaskId(), candidate.taskPlanId(), null, candidate.reviewedBy(),
                candidate.reviewReason(), candidate.reviewedAt(), candidate.revision() + 1,
                candidate.startedAt() == null ? now : candidate.startedAt(), now, null,
                candidate.workspaceCleanedAt());
        values.put(claimed.id(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized boolean updateClaimed(
            ProjectIntakeJob expected, ProjectIntakeJob updated, Instant now) {
        ProjectIntakeJob current = values.get(expected.id());
        if (current == null || current.revision() != expected.revision()
                || current.state() != ProjectIntakeState.RUNNING
                || !equal(current.claimOwner(), expected.claimOwner())
                || !equal(current.claimToken(), expected.claimToken())
                || current.fencingToken() != expected.fencingToken()
                || current.leaseUntil() == null || !current.leaseUntil().isAfter(now)) {
            return false;
        }
        values.put(updated.id(), updated);
        return true;
    }

    @Override
    public synchronized void saveLifecycle(ProjectIntakeJob expected, ProjectIntakeJob updated) {
        ProjectIntakeJob current = values.get(expected.id());
        if (current == null || current.revision() != expected.revision()) {
            throw new IllegalStateException("Project intake revision conflict");
        }
        values.put(updated.id(), updated);
    }

    @Override
    public synchronized Optional<ProjectIntakeJob> findWorkspaceCleanupCandidate() {
        return values.values().stream()
                .filter(value -> value.workspaceRef() != null && value.workspaceCleanedAt() == null)
                .filter(value -> value.state() != ProjectIntakeState.PENDING
                        && value.state() != ProjectIntakeState.RUNNING)
                .min(Comparator.comparing(ProjectIntakeJob::updatedAt));
    }

    @Override
    public synchronized boolean markWorkspaceCleaned(
            String jobId, long expectedRevision, Instant cleanedAt) {
        ProjectIntakeJob current = values.get(jobId);
        if (current == null || current.revision() != expectedRevision) return false;
        values.put(jobId, copy(current, current.state(), current.attempt(), current.claimOwner(),
                current.claimToken(), current.fencingToken(), current.leaseUntil(),
                current.workspaceRef(), current.sourceHeadCommit(), current.inspectionHash(),
                current.inspectionJson(), current.proposalHash(), current.proposalJson(),
                current.agentRunId(), current.blueprintId(), current.rootTaskId(),
                current.taskPlanId(), current.safeErrorCode(), current.reviewedBy(),
                current.reviewReason(), current.reviewedAt(), current.revision() + 1,
                current.startedAt(), cleanedAt, current.completedAt(), cleanedAt));
        return true;
    }

    public static ProjectIntakeJob copy(
            ProjectIntakeJob value, ProjectIntakeState state, int attempt,
            String claimOwner, String claimToken, long fencingToken, Instant leaseUntil,
            String workspaceRef, String sourceHeadCommit, String inspectionHash,
            String inspectionJson, String proposalHash, String proposalJson, String agentRunId,
            String blueprintId, String rootTaskId, String taskPlanId, String safeErrorCode,
            String reviewedBy, String reviewReason, Instant reviewedAt, long revision,
            Instant startedAt, Instant updatedAt, Instant completedAt,
            Instant workspaceCleanedAt) {
        return new ProjectIntakeJob(
                value.id(), value.tenantId(), value.ownerId(), value.projectId(),
                value.projectDirectoryId(), value.sourceRepositoryId(), value.conversationId(),
                value.agentId(), value.goal(), value.idempotencyHash(),
                value.inputHash(), state, attempt, claimOwner, claimToken, fencingToken, leaseUntil,
                workspaceRef, sourceHeadCommit, inspectionHash, inspectionJson, proposalHash,
                proposalJson, agentRunId, blueprintId, rootTaskId, taskPlanId, safeErrorCode,
                reviewedBy, reviewReason, reviewedAt, revision, value.createdAt(), startedAt,
                updatedAt, completedAt, workspaceCleanedAt);
    }

    private static boolean equal(String left, String right) {
        return java.util.Objects.equals(left, right);
    }

    private static boolean active(ProjectIntakeState state) {
        return state == ProjectIntakeState.PENDING || state == ProjectIntakeState.RUNNING;
    }
}
