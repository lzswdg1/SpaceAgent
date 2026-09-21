package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.ProjectExecutionContextSnapshot;
import com.spaceagent.platform.runtime.domain.ProjectExecutionContextSnapshotRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectExecutionContextSnapshotRepository
        implements ProjectExecutionContextSnapshotRepository {

    private final Map<String, ProjectExecutionContextSnapshot> snapshots =
            new ConcurrentHashMap<>();

    @Override
    public synchronized void save(ProjectExecutionContextSnapshot snapshot) {
        if (snapshots.containsKey(snapshot.id()) || snapshots.values().stream().anyMatch(value ->
                value.tenantId().equals(snapshot.tenantId())
                        && value.ownerId().equals(snapshot.ownerId())
                        && value.idempotencyHash().equals(snapshot.idempotencyHash()))) {
            throw new IllegalStateException("Project recovery snapshot identity conflict");
        }
        snapshots.put(snapshot.id(), snapshot);
    }

    @Override
    public Optional<ProjectExecutionContextSnapshot> findById(String id) {
        return Optional.ofNullable(snapshots.get(id));
    }

    @Override
    public Optional<ProjectExecutionContextSnapshot> findByIdempotencyHash(
            String tenantId, String ownerId, String idempotencyHash) {
        return snapshots.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.idempotencyHash().equals(idempotencyHash))
                .findFirst();
    }

    @Override
    public Optional<ProjectExecutionContextSnapshot> findLatestByRunId(String agentRunId) {
        return snapshots.values().stream()
                .filter(value -> value.agentRunId().equals(agentRunId))
                .max(Comparator.comparing(ProjectExecutionContextSnapshot::createdAt)
                        .thenComparing(ProjectExecutionContextSnapshot::id));
    }
}
