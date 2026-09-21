package com.spaceagent.platform.project.domain;
import java.time.Instant; import java.util.List; import java.util.Optional;
public interface WorkspaceRepository {
    void save(Workspace workspace);
    Optional<Workspace> findById(String id);
    List<Workspace> findByProjectId(String projectId);
    boolean hasActiveWritable(String taskId, String sourceRepositoryId, String isolationKey);
    List<Workspace> findStaleProvisioning(Instant updatedBefore, int limit);
    boolean failProvisioning(String id, long expectedRevision, String reason, Instant at);
}
