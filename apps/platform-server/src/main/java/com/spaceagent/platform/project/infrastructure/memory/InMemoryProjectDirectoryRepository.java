package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.ProjectDirectory;
import com.spaceagent.platform.project.domain.ProjectDirectoryRepository;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectDirectoryRepository implements ProjectDirectoryRepository {
    private final Map<String, ProjectDirectory> values = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(ProjectDirectory value) {
        boolean conflict = values.values().stream()
                .filter(existing -> !existing.id().equals(value.id()))
                .filter(existing -> existing.projectId().equals(value.projectId()))
                .anyMatch(existing -> (value.defaultDirectory() && existing.defaultDirectory())
                        || (value.state() == ProjectDirectoryState.ACTIVE
                        && existing.state() == ProjectDirectoryState.ACTIVE
                        && java.util.Objects.equals(
                                existing.sourceRepositoryId(), value.sourceRepositoryId())
                        && existing.relativePath().equals(value.relativePath())));
        if (conflict) throw new IllegalStateException("ProjectDirectory uniqueness conflict");
        values.put(value.id(), value);
    }

    @Override
    public Optional<ProjectDirectory> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public Optional<ProjectDirectory> findDefault(String projectId) {
        return values.values().stream()
                .filter(value -> value.projectId().equals(projectId) && value.defaultDirectory())
                .findFirst();
    }

    @Override
    public Optional<ProjectDirectory> findBySourceAndPath(
            String projectId, String sourceRepositoryId, String relativePath) {
        return values.values().stream()
                .filter(value -> value.projectId().equals(projectId))
                .filter(value -> java.util.Objects.equals(
                        value.sourceRepositoryId(), sourceRepositoryId))
                .filter(value -> value.relativePath().equals(relativePath))
                .findFirst();
    }

    @Override
    public List<ProjectDirectory> findByProjectId(String projectId) {
        return values.values().stream()
                .filter(value -> value.projectId().equals(projectId))
                .sorted(Comparator.comparing(ProjectDirectory::defaultDirectory).reversed()
                        .thenComparing(ProjectDirectory::name)
                        .thenComparing(ProjectDirectory::id))
                .toList();
    }
}
