package com.spaceagent.platform.project.domain;
import java.util.List; import java.util.Optional;
public interface ProjectBlueprintRepository {
    int nextVersion(String projectId);
    void save(ProjectBlueprint blueprint);
    Optional<ProjectBlueprint> findById(String id);
    List<ProjectBlueprint> findByProjectId(String projectId);
    Optional<ProjectBlueprint> findConfirmed(String projectId);
}
