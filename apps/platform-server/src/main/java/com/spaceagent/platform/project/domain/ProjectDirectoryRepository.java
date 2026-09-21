package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

public interface ProjectDirectoryRepository {
    void save(ProjectDirectory directory);

    Optional<ProjectDirectory> findById(String id);
    default Optional<ProjectDirectory> findByIdForUpdate(String id) { return findById(id); }
    default List<ProjectDirectory> findPendingManagedRoots() { return List.of(); }

    Optional<ProjectDirectory> findDefault(String projectId);

    Optional<ProjectDirectory> findBySourceAndPath(
            String projectId, String sourceRepositoryId, String relativePath);

    List<ProjectDirectory> findByProjectId(String projectId);
}
