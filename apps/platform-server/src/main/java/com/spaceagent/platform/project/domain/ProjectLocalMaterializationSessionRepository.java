package com.spaceagent.platform.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Project-owned durable session store. Every state change is revision and state fenced. */
public interface ProjectLocalMaterializationSessionRepository {
    void insert(ProjectLocalMaterializationSession session);

    Optional<ProjectLocalMaterializationSession> findById(
            String tenantId, String ownerUserId, String projectId, String sessionId);

    Optional<ProjectLocalMaterializationSession> findByRequest(
            String tenantId, String ownerUserId, String projectId, String bridgeId, String requestId);

    List<ProjectLocalMaterializationSession> findActiveExpiredBefore(Instant now, int limit);

    boolean update(
            ProjectLocalMaterializationSession session,
            long expectedRevision,
            ProjectLocalMaterializationSessionState expectedState);
}
