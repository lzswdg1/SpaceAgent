package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Non-authoritative local/test Project adapter. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectRepository implements ProjectRepository {

    private final Map<String, Project> values = new ConcurrentHashMap<>();

    @Override
    public void save(Project project) {
        values.put(project.id(), project);
    }

    @Override
    public Optional<Project> findById(String projectId) {
        return Optional.ofNullable(values.get(projectId));
    }

    @Override
    public List<Project> findByTenantId(String tenantId) {
        return values.values().stream()
                .filter(project -> tenantId.equals(project.tenantId()))
                .sorted(Comparator.comparing(Project::updatedAt).reversed()
                        .thenComparing(Project::id))
                .toList();
    }

    @Override
    public boolean exists(String tenantId, String name) {
        return values.values().stream()
                .anyMatch(project -> tenantId.equals(project.tenantId())
                        && name.equals(project.name()));
    }
}
