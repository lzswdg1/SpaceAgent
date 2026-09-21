package com.spaceagent.platform.agent.infrastructure.memory;

import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequest;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeRequestRepository;
import com.spaceagent.platform.agent.domain.AgentConfigurationChangeState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentConfigurationChangeRequestRepository
        implements AgentConfigurationChangeRequestRepository {

    private final Map<String, AgentConfigurationChangeRequest> values = new ConcurrentHashMap<>();

    @Override
    public synchronized Optional<AgentConfigurationChangeRequest> findById(
            String tenantId, String requestId) {
        return Optional.ofNullable(values.get(requestId))
                .filter(value -> value.tenantId().equals(tenantId));
    }

    @Override
    public synchronized Optional<AgentConfigurationChangeRequest> findByIdForUpdate(
            String tenantId, String requestId) {
        return findById(tenantId, requestId);
    }

    @Override
    public synchronized Optional<AgentConfigurationChangeRequest> findPending(
            String tenantId, String agentId, String requestedBy) {
        return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.agentId().equals(agentId))
                .filter(value -> value.requestedBy().equals(requestedBy))
                .filter(value -> value.state() == AgentConfigurationChangeState.PENDING)
                .findFirst();
    }

    @Override
    public synchronized List<AgentConfigurationChangeRequest> listByAgent(
            String tenantId, String agentId, int offset, int limit) {
        return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.agentId().equals(agentId))
                .sorted(Comparator.comparing(AgentConfigurationChangeRequest::createdAt)
                        .reversed().thenComparing(AgentConfigurationChangeRequest::id))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized void insert(AgentConfigurationChangeRequest request) {
        if (values.putIfAbsent(request.id(), request) != null) {
            throw new IllegalStateException("Agent configuration change request already exists");
        }
        if (findPending(request.tenantId(), request.agentId(), request.requestedBy()).stream()
                .anyMatch(value -> !value.id().equals(request.id()))) {
            values.remove(request.id());
            throw new IllegalStateException("Pending Agent configuration change already exists");
        }
    }

    @Override
    public synchronized Optional<AgentConfigurationChangeRequest> update(
            AgentConfigurationChangeRequest request,
            long expectedRevision,
            AgentConfigurationChangeState expectedState) {
        AgentConfigurationChangeRequest current = values.get(request.id());
        if (current == null || !current.tenantId().equals(request.tenantId())
                || current.revision() != expectedRevision || current.state() != expectedState
                || request.revision() != expectedRevision + 1) {
            return Optional.empty();
        }
        values.put(request.id(), request);
        return Optional.of(request);
    }
}
