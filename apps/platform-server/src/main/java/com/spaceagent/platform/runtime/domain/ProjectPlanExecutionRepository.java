package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectPlanExecutionRepository {
    void insert(ProjectPlanExecution execution);

    boolean insertIfAbsent(ProjectPlanExecution execution);

    Optional<ProjectPlanExecution> findById(String id);

    Optional<ProjectPlanExecution> findByIdForUpdate(String id);

    Optional<ProjectPlanExecution> findByTaskPlanId(String tenantId, String ownerId, String taskPlanId);

    Optional<ProjectPlanExecution> findByTaskPlanIdForUpdate(String tenantId, String ownerId, String taskPlanId);

    List<ProjectPlanExecution> findByProject(String projectId, String ownerId, int offset, int limit);

    List<ProjectPlanExecution> findControlTransitions(int limit);

    long countByProject(String projectId, String ownerId);

    void saveLifecycle(ProjectPlanExecution expected, ProjectPlanExecution updated);

    boolean saveStateIfMatch(String id, String tenantId, String ownerId, long revision, ProjectPlanExecution updated);

    int cleanupOldCompleted(Instant before, int limit);
}
