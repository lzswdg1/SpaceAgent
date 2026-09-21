package com.spaceagent.platform.memory.infrastructure.memory;

import com.spaceagent.platform.memory.domain.MemoryCandidate;
import com.spaceagent.platform.memory.domain.MemoryCandidateRepository;
import com.spaceagent.platform.memory.domain.MemoryCandidateState;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory memory candidate repository for tests and local development.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryMemoryCandidateRepository implements MemoryCandidateRepository {

    private final Map<String, MemoryCandidate> candidates = new ConcurrentHashMap<>();

    @Override
    public List<MemoryCandidate> findByScope(MemoryScopeRef scope) {
        return candidates.values().stream()
                .filter(candidate -> scope.equals(candidate.scope()))
                .toList();
    }

    @Override
    public List<MemoryCandidate> findByScopeAndState(MemoryScopeRef scope, MemoryCandidateState state) {
        return candidates.values().stream()
                .filter(candidate -> scope.equals(candidate.scope()))
                .filter(candidate -> state == candidate.state())
                .toList();
    }

    @Override
    public Optional<MemoryCandidate> findById(String id) {
        return Optional.ofNullable(candidates.get(id));
    }

    @Override
    public void save(MemoryCandidate candidate) {
        candidates.put(candidate.id(), candidate);
    }
}
