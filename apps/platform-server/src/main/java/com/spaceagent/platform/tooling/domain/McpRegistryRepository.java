package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface McpRegistryRepository {
    Optional<McpRegistrySource> findSource(String sourceKey);

    Optional<McpRegistrySyncJob> findActiveJob(String sourceId);

    void insertJob(McpRegistrySyncJob job);

    Optional<McpRegistrySyncJob> claim(String workerId, String claimToken, Instant now,
                                       Instant leaseUntil, int maximumAttempts);

    boolean renewLease(String jobId, String claimOwner, String claimToken,
                       Instant now, Instant leaseUntil);

    CompletionResult complete(
            String jobId, String claimOwner, String claimToken,
            List<SnapshotImport> snapshots, Instant completedAt);

    boolean fail(String jobId, String claimOwner, String claimToken,
                 String safeErrorCode, Instant completedAt);

    List<McpRegistrySyncJob> findJobs(int offset, int limit, McpRegistrySyncState state);

    long countJobs(McpRegistrySyncState state);

    List<McpRegistryCandidate> findCandidates(
            int offset, int limit, McpRegistryReviewState state, String query);

    long countCandidates(McpRegistryReviewState state, String query);

    Optional<McpRegistryCandidate> findCandidate(String id);

    Optional<McpRegistryCandidate> findCandidateForUpdate(String id);

    Optional<McpRegistrySnapshot> findSnapshot(String id);

    boolean markApproved(String candidateId, long expectedRevision, String reviewedBy,
                         String reason, String entryId, String versionId, Instant reviewedAt);

    boolean markRejected(String candidateId, long expectedRevision, String reviewedBy,
                         String reason, Instant reviewedAt);

    record SnapshotImport(String snapshotId, String candidateId, McpRegistrySnapshot snapshot) {
    }

    record CompletionResult(int snapshotsCreated, int candidatesCreated) {
    }
}
