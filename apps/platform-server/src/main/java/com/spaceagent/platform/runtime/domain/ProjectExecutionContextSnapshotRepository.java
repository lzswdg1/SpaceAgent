package com.spaceagent.platform.runtime.domain;

import java.util.Optional;

public interface ProjectExecutionContextSnapshotRepository {
    void save(ProjectExecutionContextSnapshot snapshot);

    Optional<ProjectExecutionContextSnapshot> findById(String id);

    Optional<ProjectExecutionContextSnapshot> findByIdempotencyHash(
            String tenantId, String ownerId, String idempotencyHash);

    Optional<ProjectExecutionContextSnapshot> findLatestByRunId(String agentRunId);
}
