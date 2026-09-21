package com.spaceagent.platform.tooling.infrastructure.memory;

import com.spaceagent.platform.tooling.domain.McpRegistryCandidate;
import com.spaceagent.platform.tooling.domain.McpRegistryRepository;
import com.spaceagent.platform.tooling.domain.McpRegistryReviewState;
import com.spaceagent.platform.tooling.domain.McpRegistrySnapshot;
import com.spaceagent.platform.tooling.domain.McpRegistrySource;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncJob;
import com.spaceagent.platform.tooling.domain.McpRegistrySyncState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryMcpRegistryRepository implements McpRegistryRepository {
    private static final String SOURCE_ID = "10420000-0000-4000-8000-000000000001";
    private final Map<String, McpRegistrySyncJob> jobs = new LinkedHashMap<>();
    private final Map<String, McpRegistrySnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, McpRegistryCandidate> candidates = new LinkedHashMap<>();
    private McpRegistrySource source = new McpRegistrySource(
            SOURCE_ID, "official", "Official MCP Registry",
            "https://registry.modelcontextprotocol.io", true, null, 1,
            Instant.EPOCH, Instant.EPOCH);

    @Override
    public synchronized Optional<McpRegistrySource> findSource(String sourceKey) {
        return source.sourceKey().equals(sourceKey) ? Optional.of(source) : Optional.empty();
    }

    @Override
    public synchronized Optional<McpRegistrySyncJob> findActiveJob(String sourceId) {
        return jobs.values().stream()
                .filter(job -> job.sourceId().equals(sourceId))
                .filter(job -> job.state() == McpRegistrySyncState.PENDING
                        || job.state() == McpRegistrySyncState.RUNNING)
                .findFirst();
    }

    @Override
    public synchronized void insertJob(McpRegistrySyncJob job) {
        if (findActiveJob(job.sourceId()).isPresent()) {
            throw new IllegalStateException("An MCP Registry synchronization is already active");
        }
        jobs.put(job.id(), job);
    }

    @Override
    public synchronized Optional<McpRegistrySyncJob> claim(
            String workerId, String claimToken, Instant now, Instant leaseUntil,
            int maximumAttempts) {
        jobs.values().stream()
                .filter(value -> value.state() == McpRegistrySyncState.RUNNING
                        && !value.leaseUntil().isAfter(now)
                        && value.attempt() >= maximumAttempts)
                .toList().forEach(value -> jobs.put(value.id(), terminal(
                        value, McpRegistrySyncState.FAILED, value.fetchedCount(),
                        value.snapshotCount(), value.candidateCount(),
                        "MCP_REGISTRY_ATTEMPTS_EXHAUSTED", now)));
        McpRegistrySyncJob job = jobs.values().stream()
                .filter(value -> value.attempt() < maximumAttempts)
                .filter(value -> value.state() == McpRegistrySyncState.PENDING
                        || (value.state() == McpRegistrySyncState.RUNNING
                        && !value.leaseUntil().isAfter(now)))
                .min(Comparator.comparing(McpRegistrySyncJob::createdAt)).orElse(null);
        if (job == null) return Optional.empty();
        McpRegistrySyncJob claimed = new McpRegistrySyncJob(
                job.id(), job.sourceId(), job.sourceKey(), job.requestedBy(),
                McpRegistrySyncState.RUNNING, job.updatedSince(), job.watermarkAt(),
                job.fetchedCount(), job.snapshotCount(), job.candidateCount(), null,
                job.attempt() + 1, workerId, claimToken, leaseUntil, job.createdAt(),
                job.startedAt() == null ? now : job.startedAt(), now, null);
        jobs.put(claimed.id(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized boolean renewLease(
            String jobId, String claimOwner, String claimToken,
            Instant now, Instant leaseUntil) {
        McpRegistrySyncJob job = jobs.get(jobId);
        if (!matches(job, claimOwner, claimToken) || !job.leaseUntil().isAfter(now)) return false;
        jobs.put(job.id(), new McpRegistrySyncJob(
                job.id(), job.sourceId(), job.sourceKey(), job.requestedBy(), job.state(),
                job.updatedSince(), job.watermarkAt(), job.fetchedCount(), job.snapshotCount(),
                job.candidateCount(), job.safeErrorCode(), job.attempt(), job.claimOwner(),
                job.claimToken(), leaseUntil, job.createdAt(), job.startedAt(), now, null));
        return true;
    }

    @Override
    public synchronized CompletionResult complete(
            String jobId, String claimOwner, String claimToken,
            List<SnapshotImport> imports, Instant completedAt) {
        McpRegistrySyncJob job = requireClaim(jobId, claimOwner, claimToken);
        int snapshotCount = 0;
        int candidateCount = 0;
        for (SnapshotImport item : imports) {
            McpRegistrySnapshot existing = snapshots.values().stream()
                    .filter(value -> value.sourceId().equals(item.snapshot().sourceId())
                            && value.registryName().equals(item.snapshot().registryName())
                            && value.registryVersion().equals(item.snapshot().registryVersion())
                            && value.manifestSha256().equals(item.snapshot().manifestSha256()))
                    .findFirst().orElse(null);
            String snapshotId;
            if (existing == null) {
                snapshots.put(item.snapshotId(), item.snapshot());
                snapshotId = item.snapshotId();
                snapshotCount++;
            } else {
                snapshotId = existing.id();
            }
            boolean candidateExists = candidates.values().stream()
                    .anyMatch(value -> value.snapshotId().equals(snapshotId));
            if (!candidateExists) {
                candidates.put(item.candidateId(), new McpRegistryCandidate(
                        item.candidateId(), snapshotId, job.sourceId(), job.sourceKey(),
                        item.snapshot().registryName(), item.snapshot().registryVersion(),
                        McpRegistryReviewState.PENDING_REVIEW, null, null, null,
                        null, null, 1, completedAt, completedAt));
                candidateCount++;
            }
        }
        jobs.put(job.id(), terminal(job, McpRegistrySyncState.SUCCEEDED, imports.size(),
                snapshotCount, candidateCount, null, completedAt));
        source = new McpRegistrySource(source.id(), source.sourceKey(), source.displayName(),
                source.baseUrl(), source.enabled(), job.watermarkAt(), source.revision() + 1,
                source.createdAt(), completedAt);
        return new CompletionResult(snapshotCount, candidateCount);
    }

    @Override
    public synchronized boolean fail(
            String jobId, String claimOwner, String claimToken,
            String safeErrorCode, Instant completedAt) {
        McpRegistrySyncJob job = jobs.get(jobId);
        if (!matches(job, claimOwner, claimToken)) return false;
        jobs.put(job.id(), terminal(job, McpRegistrySyncState.FAILED, 0, 0, 0,
                safeErrorCode, completedAt));
        return true;
    }

    @Override
    public synchronized List<McpRegistrySyncJob> findJobs(
            int offset, int limit, McpRegistrySyncState state) {
        return jobs.values().stream()
                .filter(value -> state == null || value.state() == state)
                .sorted(Comparator.comparing(McpRegistrySyncJob::createdAt).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long countJobs(McpRegistrySyncState state) {
        return jobs.values().stream().filter(value -> state == null || value.state() == state).count();
    }

    @Override
    public synchronized List<McpRegistryCandidate> findCandidates(
            int offset, int limit, McpRegistryReviewState state, String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        return candidates.values().stream()
                .filter(value -> state == null || value.reviewState() == state)
                .filter(value -> normalized.isEmpty()
                        || value.registryName().toLowerCase(java.util.Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(McpRegistryCandidate::createdAt).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long countCandidates(McpRegistryReviewState state, String query) {
        return findCandidates(0, Integer.MAX_VALUE, state, query).size();
    }

    @Override
    public synchronized Optional<McpRegistryCandidate> findCandidate(String id) {
        return Optional.ofNullable(candidates.get(id));
    }

    @Override
    public synchronized Optional<McpRegistryCandidate> findCandidateForUpdate(String id) {
        return findCandidate(id);
    }

    @Override
    public synchronized Optional<McpRegistrySnapshot> findSnapshot(String id) {
        return Optional.ofNullable(snapshots.get(id));
    }

    @Override
    public synchronized boolean markApproved(
            String candidateId, long expectedRevision, String reviewedBy, String reason,
            String entryId, String versionId, Instant reviewedAt) {
        McpRegistryCandidate current = candidates.get(candidateId);
        if (current == null || current.revision() != expectedRevision
                || current.reviewState() != McpRegistryReviewState.PENDING_REVIEW) return false;
        candidates.put(candidateId, reviewed(current, McpRegistryReviewState.APPROVED,
                reviewedBy, reason, entryId, versionId, reviewedAt));
        return true;
    }

    @Override
    public synchronized boolean markRejected(
            String candidateId, long expectedRevision, String reviewedBy, String reason,
            Instant reviewedAt) {
        McpRegistryCandidate current = candidates.get(candidateId);
        if (current == null || current.revision() != expectedRevision
                || current.reviewState() != McpRegistryReviewState.PENDING_REVIEW) return false;
        candidates.put(candidateId, reviewed(current, McpRegistryReviewState.REJECTED,
                reviewedBy, reason, null, null, reviewedAt));
        return true;
    }

    private McpRegistrySyncJob requireClaim(String id, String owner, String token) {
        McpRegistrySyncJob job = jobs.get(id);
        if (!matches(job, owner, token)) throw new IllegalStateException("Registry lease was lost");
        return job;
    }

    private static boolean matches(McpRegistrySyncJob value, String owner, String token) {
        return value != null && value.state() == McpRegistrySyncState.RUNNING
                && owner.equals(value.claimOwner()) && token.equals(value.claimToken());
    }

    private static McpRegistrySyncJob terminal(
            McpRegistrySyncJob value, McpRegistrySyncState state, int fetched,
            int snapshots, int candidates, String error, Instant now) {
        return new McpRegistrySyncJob(
                value.id(), value.sourceId(), value.sourceKey(), value.requestedBy(), state,
                value.updatedSince(), value.watermarkAt(), fetched, snapshots, candidates, error,
                value.attempt(), null, null, null, value.createdAt(), value.startedAt(), now, now);
    }

    private static McpRegistryCandidate reviewed(
            McpRegistryCandidate value, McpRegistryReviewState state, String actor, String reason,
            String entryId, String versionId, Instant now) {
        return new McpRegistryCandidate(
                value.id(), value.snapshotId(), value.sourceId(), value.sourceKey(),
                value.registryName(), value.registryVersion(), state, actor, reason, now,
                entryId, versionId, value.revision() + 1, value.createdAt(), now);
    }
}
