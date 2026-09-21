package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.ProjectCodingJob;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobRepository;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryProjectCodingJobRepository implements ProjectCodingJobRepository {
    private final Map<String, ProjectCodingJob> values = new LinkedHashMap<>();
    public synchronized void insert(ProjectCodingJob job) {
        if (values.containsKey(job.id()) || values.values().stream().anyMatch(value ->
                value.tenantId().equals(job.tenantId()) && value.ownerId().equals(job.ownerId())
                        && value.idempotencyHash().equals(job.idempotencyHash()))) {
            throw new IllegalStateException("Project Coding Job exists");
        }
        values.put(job.id(), job);
    }
    public synchronized Optional<ProjectCodingJob> findById(String id) { return Optional.ofNullable(values.get(id)); }
    public synchronized Optional<ProjectCodingJob> findByIdForUpdate(String id) { return findById(id); }
    public synchronized Optional<ProjectCodingJob> findByIdempotency(String tenant, String owner, String hash) {
        return values.values().stream().filter(v -> v.tenantId().equals(tenant))
                .filter(v -> v.ownerId().equals(owner)).filter(v -> v.idempotencyHash().equals(hash)).findFirst();
    }
    public synchronized List<ProjectCodingJob> findActiveByTaskPlan(String plan, String owner) {
        return values.values().stream().filter(v -> v.taskPlanId().equals(plan))
                .filter(v -> v.ownerId().equals(owner))
                .filter(v -> v.state() == ProjectCodingJobState.PENDING
                        || v.state() == ProjectCodingJobState.RUNNING
                        || v.state() == ProjectCodingJobState.WAITING_APPROVAL)
                .sorted(Comparator.comparing(ProjectCodingJob::createdAt)
                        .thenComparing(ProjectCodingJob::id))
                .toList();
    }

    @Override
    public synchronized List<ProjectCodingJob> findActiveByExecutionId(
            String executionId, String owner) {
        return values.values().stream()
                .filter(value -> java.util.Objects.equals(executionId, value.executionId()))
                .filter(value -> value.ownerId().equals(owner))
                .filter(value -> Set.of(ProjectCodingJobState.PENDING,
                        ProjectCodingJobState.RUNNING,
                        ProjectCodingJobState.WAITING_APPROVAL).contains(value.state()))
                .sorted(Comparator.comparing(ProjectCodingJob::createdAt)
                        .thenComparing(ProjectCodingJob::id))
                .toList();
    }
    public synchronized List<ProjectCodingJob> findByPlanStep(String step, String owner, int offset, int limit) {
        return values.values().stream().filter(v -> v.planStepId().equals(step))
                .filter(v -> v.ownerId().equals(owner))
                .sorted(Comparator.comparing(ProjectCodingJob::createdAt).reversed())
                .skip(offset).limit(limit).toList();
    }
    public synchronized long countByPlanStep(String step, String owner) {
        return values.values().stream().filter(v -> v.planStepId().equals(step))
                .filter(v -> v.ownerId().equals(owner)).count();
    }
    public synchronized Optional<ProjectCodingJob> claim(
            String owner, String token, Instant now, Instant until, int maximumAttempts) {
        ProjectCodingJob candidate = values.values().stream()
                .filter(v -> v.attempt() < maximumAttempts)
                .filter(v -> v.state() == ProjectCodingJobState.PENDING
                        || v.state() == ProjectCodingJobState.RUNNING && v.leaseUntil() != null
                        && !v.leaseUntil().isAfter(now))
                .min(Comparator.comparing(ProjectCodingJob::createdAt)).orElse(null);
        if (candidate == null) return Optional.empty();
        var claimed = copy(candidate, ProjectCodingJobState.RUNNING, candidate.workspaceId(),
                candidate.codingRunId(), candidate.reviewerRunId(), candidate.iteration(),
                candidate.reviewRound(), candidate.contextJson(), candidate.pendingToolJson(),
                candidate.pendingApprovalId(), candidate.patchArtifactId(), candidate.commitArtifactId(),
                candidate.reviewId(), candidate.sourceMergeId(), null, candidate.attempt() + 1,
                owner, token, candidate.fencingToken() + 1, until, candidate.revision() + 1,
                candidate.startedAt() == null ? now : candidate.startedAt(), now, null);
        values.put(claimed.id(), claimed); return Optional.of(claimed);
    }
    public synchronized boolean saveClaimed(ProjectCodingJob expected, ProjectCodingJob updated, Instant now) {
        var current = values.get(expected.id());
        if (current == null || current.revision() != expected.revision()
                || current.state() != ProjectCodingJobState.RUNNING
                || !java.util.Objects.equals(current.claimToken(), expected.claimToken())
                || current.fencingToken() != expected.fencingToken()
                || current.leaseUntil() == null || !current.leaseUntil().isAfter(now)) return false;
        values.put(updated.id(), updated); return true;
    }
    public synchronized void saveLifecycle(ProjectCodingJob expected, ProjectCodingJob updated) {
        var current = values.get(expected.id());
        if (current == null || current.revision() != expected.revision())
            throw new IllegalStateException("Project Coding Job revision conflict");
        values.put(updated.id(), updated);
    }
    public static ProjectCodingJob copy(
            ProjectCodingJob v, ProjectCodingJobState state, String workspace, String run,
            String reviewerRun, int iteration, int reviewRound, String context, String pending,
            String approval, String patch, String commit, String review, String merge, String error,
            int attempt, String claimOwner, String claimToken, long fence, Instant lease,
            long revision, Instant started, Instant updated, Instant completed) {
        return new ProjectCodingJob(v.id(),v.tenantId(),v.ownerId(),v.projectId(),v.projectDirectoryId(),
                v.conversationId(),v.sourceRepositoryId(),v.rootTaskId(),v.taskId(),v.taskPlanId(),v.executionId(),
                v.planStepId(),v.agentId(),v.primaryConfigurationHash(),v.reviewerConfigurationHash(),v.baseRef(),
                v.idempotencyHash(),v.inputHash(),state,workspace,run,reviewerRun,iteration,reviewRound,
                context,pending,approval,patch,commit,review,merge,error,attempt,claimOwner,claimToken,
                fence,lease,revision,v.createdAt(),started,updated,completed,v.reviewerAgentId());
    }
}
