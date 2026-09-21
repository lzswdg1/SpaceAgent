package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshotRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentRunConfigurationSnapshotRepository
        implements AgentRunConfigurationSnapshotRepository {

    private final Map<String, AgentRunConfigurationSnapshot> values = new ConcurrentHashMap<>();

    @Override
    public AgentRunConfigurationSnapshot insertIfAbsent(AgentRunConfigurationSnapshot snapshot) {
        AgentRunConfigurationSnapshot existing = values.putIfAbsent(snapshot.runId(), snapshot);
        if (existing == null || existing.equals(snapshot)) return snapshot;
        throw new IllegalStateException("Run Agent configuration snapshot conflict");
    }

    @Override
    public Optional<AgentRunConfigurationSnapshot> findByRunId(
            String tenantId, String ownerUserId, String runId) {
        return Optional.ofNullable(values.get(runId))
                .filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> ownerUserId.equals(value.ownerUserId()));
    }
}
