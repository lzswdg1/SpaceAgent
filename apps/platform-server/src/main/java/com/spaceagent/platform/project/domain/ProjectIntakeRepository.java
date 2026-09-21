package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectIntakeRepository {

    void insert(ProjectIntakeJob job);

    Optional<ProjectIntakeJob> findById(String id);

    Optional<ProjectIntakeJob> findByIdForUpdate(String id);

    Optional<ProjectIntakeJob> findByIdempotency(
            String tenantId, String ownerId, String idempotencyHash);

    List<ProjectIntakeJob> findByDirectory(
            String projectDirectoryId, String ownerId, int offset, int limit);

    long countByDirectory(String projectDirectoryId, String ownerId);

    Optional<ProjectIntakeJob> claim(
            String workerId,
            String claimToken,
            Instant now,
            Instant leaseUntil,
            int maximumAttempts);

    boolean updateClaimed(ProjectIntakeJob expected, ProjectIntakeJob updated, Instant now);

    void saveLifecycle(ProjectIntakeJob expected, ProjectIntakeJob updated);

    Optional<ProjectIntakeJob> findWorkspaceCleanupCandidate();

    boolean markWorkspaceCleaned(String jobId, long expectedRevision, Instant cleanedAt);
}
