package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for the Project aggregate. */
public interface ProjectRepository {

    void save(Project project);

    Optional<Project> findById(String projectId);

    List<Project> findByTenantId(String tenantId);

    boolean exists(String tenantId, String name);
}
