package com.spaceagent.platform.identity.infrastructure.memory;

import com.spaceagent.platform.identity.domain.OrganizationCleanupJob;
import com.spaceagent.platform.identity.domain.OrganizationCleanupJobState;
import com.spaceagent.platform.identity.domain.OrganizationCleanupRepository;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStep;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryOrganizationCleanupRepository implements OrganizationCleanupRepository {

    private final Map<String, OrganizationCleanupJob> jobs = new LinkedHashMap<>();
    private final Map<String, OrganizationCleanupStep> steps = new LinkedHashMap<>();

    @Override
    public synchronized void enqueue(
            OrganizationCleanupJob job,
            List<OrganizationCleanupStep> declaredSteps) {
        if (jobs.putIfAbsent(job.organizationId(), job) == null) {
            declaredSteps.forEach(step -> steps.put(stepKey(step.organizationId(), step.stepKey()), step));
        }
    }

    @Override
    public synchronized Optional<OrganizationCleanupJob> findJob(String organizationId) {
        return Optional.ofNullable(jobs.get(organizationId));
    }

    @Override
    public synchronized List<OrganizationCleanupStep> findSteps(String organizationId) {
        return steps.values().stream()
                .filter(step -> organizationId.equals(step.organizationId()))
                .sorted(Comparator.comparingInt(OrganizationCleanupStep::sequence))
                .toList();
    }

    @Override
    public synchronized Optional<OrganizationCleanupJob> claimNext(
            String leaseOwner,
            String leaseToken,
            int leaseSeconds,
            Instant now) {
        jobs.replaceAll((id, job) -> exhaustedExpiredClaim(job, now));
        OrganizationCleanupJob candidate = jobs.values().stream()
                .filter(job -> eligible(job, now))
                .sorted(Comparator.comparing(OrganizationCleanupJob::nextAttemptAt)
                        .thenComparing(OrganizationCleanupJob::createdAt)
                        .thenComparing(OrganizationCleanupJob::organizationId))
                .findFirst()
                .orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        OrganizationCleanupJob claimed = new OrganizationCleanupJob(
                candidate.organizationId(), OrganizationCleanupJobState.CLAIMED,
                candidate.retentionNotBefore(), candidate.nextAttemptAt(),
                candidate.attempt() + 1, candidate.maxAttempts(), leaseOwner, leaseToken,
                candidate.fencingToken() + 1, now.plusSeconds(leaseSeconds),
                candidate.lastErrorCode(), candidate.lastErrorSummary(), candidate.revision() + 1,
                candidate.createdAt(), now, candidate.completedAt());
        jobs.put(claimed.organizationId(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized Optional<OrganizationCleanupJob> heartbeat(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds,
            Instant now) {
        OrganizationCleanupJob current = jobs.get(organizationId);
        if (!validClaim(current, leaseOwner, leaseToken, fencingToken, now)) {
            return Optional.empty();
        }
        OrganizationCleanupJob updated = new OrganizationCleanupJob(
                current.organizationId(), current.state(), current.retentionNotBefore(),
                current.nextAttemptAt(), current.attempt(), current.maxAttempts(),
                current.leaseOwner(), current.leaseToken(), current.fencingToken(),
                now.plusSeconds(leaseSeconds), current.lastErrorCode(), current.lastErrorSummary(),
                current.revision() + 1, current.createdAt(), now, current.completedAt());
        jobs.put(organizationId, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<OrganizationCleanupStep> completeStep(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant now) {
        if (!validClaim(jobs.get(organizationId), leaseOwner, leaseToken, fencingToken, now)) {
            return Optional.empty();
        }
        String key = stepKey(organizationId, stepKey);
        OrganizationCleanupStep current = steps.get(key);
        if (current == null) {
            return Optional.empty();
        }
        if (current.state() == OrganizationCleanupStepState.COMPLETED) {
            return Optional.of(current);
        }
        if (!isFirstPending(current)) {
            return Optional.empty();
        }
        OrganizationCleanupStep completed = new OrganizationCleanupStep(
                current.organizationId(), current.stepKey(), current.sequence(),
                OrganizationCleanupStepState.COMPLETED, current.attempt() + 1,
                null, null, current.createdAt(), now, now);
        steps.put(key, completed);
        return Optional.of(completed);
    }

    @Override
    public synchronized Optional<OrganizationCleanupJob> defer(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant now) {
        return releaseClaim(
                organizationId, stepKey, leaseOwner, leaseToken, fencingToken, nextAttemptAt,
                errorCode, errorSummary, now, false, false);
    }

    @Override
    public synchronized Optional<OrganizationCleanupJob> fail(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant now) {
        return releaseClaim(
                organizationId, stepKey, leaseOwner, leaseToken, fencingToken, nextAttemptAt,
                errorCode, errorSummary, now, true, false);
    }

    @Override
    public synchronized Optional<OrganizationCleanupJob> block(
            String organizationId, OrganizationCleanupStepKey stepKey, String leaseOwner,
            String leaseToken, long fencingToken, String errorCode, String errorSummary, Instant now) {
        return releaseClaim(organizationId,stepKey,leaseOwner,leaseToken,fencingToken,now,
                errorCode,errorSummary,now,true,true);
    }

    @Override
    public synchronized Optional<OrganizationCleanupJob> complete(
            String organizationId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant now) {
        OrganizationCleanupJob current = jobs.get(organizationId);
        if (!validClaim(current, leaseOwner, leaseToken, fencingToken, now)
                || countIncompleteSteps(organizationId) != 0L) {
            return Optional.empty();
        }
        OrganizationCleanupJob completed = terminal(
                current, OrganizationCleanupJobState.COMPLETED,
                current.nextAttemptAt(), current.lastErrorCode(), current.lastErrorSummary(),
                now, now);
        jobs.put(organizationId, completed);
        return Optional.of(completed);
    }

    @Override
    public synchronized long countIncompleteSteps(String organizationId) {
        return findSteps(organizationId).stream()
                .filter(step -> step.state() != OrganizationCleanupStepState.COMPLETED)
                .count();
    }

    private Optional<OrganizationCleanupJob> releaseClaim(
            String organizationId,
            OrganizationCleanupStepKey stepKey,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant now,
            boolean enforceAttempts,
            boolean forceBlock) {
        OrganizationCleanupJob current = jobs.get(organizationId);
        if (!validClaim(current, leaseOwner, leaseToken, fencingToken, now)) {
            return Optional.empty();
        }
        String key = stepKey(organizationId, stepKey);
        OrganizationCleanupStep currentStep = steps.get(key);
        if (currentStep == null || currentStep.state() != OrganizationCleanupStepState.PENDING
                || !isFirstPending(currentStep)) {
            return Optional.empty();
        }
        steps.put(key, new OrganizationCleanupStep(
                currentStep.organizationId(), currentStep.stepKey(), currentStep.sequence(),
                currentStep.state(), currentStep.attempt() + 1, errorCode, errorSummary,
                currentStep.createdAt(), now, null));
        OrganizationCleanupJobState state = forceBlock ? OrganizationCleanupJobState.BLOCKED : enforceAttempts
                && current.attempt() >= current.maxAttempts()
                ? OrganizationCleanupJobState.BLOCKED
                : OrganizationCleanupJobState.RETRY;
        OrganizationCleanupJob updated = terminal(
                current, state, nextAttemptAt, errorCode, errorSummary, now, null);
        if (!enforceAttempts && !forceBlock) {
            updated = withAttempt(updated, Math.max(0, updated.attempt() - 1));
        }
        jobs.put(organizationId, updated);
        return Optional.of(updated);
    }

    private static OrganizationCleanupJob terminal(
            OrganizationCleanupJob current,
            OrganizationCleanupJobState state,
            Instant nextAttemptAt,
            String errorCode,
            String errorSummary,
            Instant now,
            Instant completedAt) {
        return new OrganizationCleanupJob(
                current.organizationId(), state, current.retentionNotBefore(), nextAttemptAt,
                current.attempt(), current.maxAttempts(), null, null, current.fencingToken(),
                null, errorCode, errorSummary, current.revision() + 1, current.createdAt(), now,
                completedAt);
    }

    private static OrganizationCleanupJob exhaustedExpiredClaim(
            OrganizationCleanupJob job,
            Instant now) {
        if (job.state() == OrganizationCleanupJobState.CLAIMED
                && !job.leaseUntil().isAfter(now)
                && job.attempt() >= job.maxAttempts()) {
            return terminal(
                    job, OrganizationCleanupJobState.BLOCKED, job.nextAttemptAt(),
                    "CLEANUP_ATTEMPTS_EXHAUSTED", "Cleanup claim attempts exhausted",
                    now, null);
        }
        return job;
    }

    private static OrganizationCleanupJob withAttempt(
            OrganizationCleanupJob job,
            int attempt) {
        return new OrganizationCleanupJob(
                job.organizationId(), job.state(), job.retentionNotBefore(), job.nextAttemptAt(),
                attempt, job.maxAttempts(), job.leaseOwner(), job.leaseToken(),
                job.fencingToken(), job.leaseUntil(), job.lastErrorCode(),
                job.lastErrorSummary(), job.revision(), job.createdAt(), job.updatedAt(),
                job.completedAt());
    }

    private static boolean eligible(OrganizationCleanupJob job, Instant now) {
        if (job.attempt() >= job.maxAttempts()) {
            return false;
        }
        if (job.retentionNotBefore().isAfter(now) || job.nextAttemptAt().isAfter(now)) {
            return false;
        }
        return job.state() == OrganizationCleanupJobState.PENDING
                || job.state() == OrganizationCleanupJobState.RETRY
                || (job.state() == OrganizationCleanupJobState.CLAIMED
                && !job.leaseUntil().isAfter(now));
    }

    private static boolean validClaim(
            OrganizationCleanupJob job,
            String owner,
            String token,
            long fence,
            Instant now) {
        return job != null
                && job.state() == OrganizationCleanupJobState.CLAIMED
                && owner.equals(job.leaseOwner())
                && token.equals(job.leaseToken())
                && fence == job.fencingToken()
                && job.leaseUntil().isAfter(now);
    }

    private static String stepKey(String organizationId, OrganizationCleanupStepKey stepKey) {
        return organizationId + ":" + stepKey.name();
    }

    private boolean isFirstPending(OrganizationCleanupStep current) {
        return findSteps(current.organizationId()).stream()
                .filter(step -> step.state() == OrganizationCleanupStepState.PENDING)
                .mapToInt(OrganizationCleanupStep::sequence)
                .min()
                .orElse(current.sequence()) == current.sequence();
    }
}
