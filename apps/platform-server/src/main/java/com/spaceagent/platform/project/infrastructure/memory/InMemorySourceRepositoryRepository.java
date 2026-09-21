package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemorySourceRepositoryRepository implements SourceRepositoryRepository {
    private final Map<String, SourceRepository> values = new ConcurrentHashMap<>();
    public void save(SourceRepository value) { values.put(value.id(), value); }
    public Optional<SourceRepository> findById(String id) { return Optional.ofNullable(values.get(id)); }
    public List<SourceRepository> findByProjectId(String projectId) {
        return values.values().stream().filter(v -> projectId.equals(v.projectId()))
                .sorted(Comparator.comparing(SourceRepository::createdAt)).toList();
    }
    public boolean existsGithub(String projectId, String providerRepositoryId) {
        return findActiveGithub(projectId, providerRepositoryId).isPresent();
    }
    public Optional<SourceRepository> findActiveGithub(String projectId, String providerRepositoryId) {
        return values.values().stream().filter(v -> projectId.equals(v.projectId())
                && providerRepositoryId.equals(v.providerRepositoryId())
                && v.type() == com.spaceagent.platform.project.domain.SourceRepositoryType.GITHUB
                && v.state() != com.spaceagent.platform.project.domain.SourceRepositoryState.ARCHIVED)
                .findFirst();
    }
    public Optional<SourceRepository> findByMaterializationSessionId(String id) {
        return values.values().stream().filter(v -> id.equals(v.materializationSessionId())).findFirst();
    }
    public boolean existsLocal(String projectId, String bridgeId, String rootHandle) {
        return values.values().stream().anyMatch(v -> projectId.equals(v.projectId())
                && bridgeId.equals(v.workspaceBridgeId()) && rootHandle.equals(v.localRootHandle())
                && v.state() != com.spaceagent.platform.project.domain.SourceRepositoryState.ARCHIVED);
    }
}
