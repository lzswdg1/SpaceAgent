package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectRunHandoffRepository {
    void insert(ProjectRunHandoff handoff);
    Optional<ProjectRunHandoff> findById(String id);
    Optional<ProjectRunHandoff> findByIdForUpdate(String id);
    Optional<ProjectRunHandoff> findByIdempotency(String tenantId, String ownerId, String hash);
    Optional<ProjectRunHandoff> findBySourceCodingJobId(String codingJobId);
    Optional<ProjectRunHandoff> findByTargetCodingJobId(String codingJobId);
    List<ProjectRunHandoff> findByProject(String projectId, String ownerId, int offset, int limit);
    long countByProject(String projectId, String ownerId);
    void saveLifecycle(ProjectRunHandoff expected, ProjectRunHandoff updated);
    int promoteCompletedTargets(Instant now);
    Optional<ProjectRunHandoff> claimFinalization(
            String workerId, String claimToken, Instant now, Instant leaseUntil, int maximumAttempts);
    boolean saveClaimed(ProjectRunHandoff expected, ProjectRunHandoff updated, Instant now);
}
