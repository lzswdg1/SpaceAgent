package com.spaceagent.platform.memory.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for memory candidate persistence.
 */
public interface MemoryCandidateRepository {
    List<MemoryCandidate> findByScope(MemoryScopeRef scope);

    List<MemoryCandidate> findByScopeAndState(MemoryScopeRef scope, MemoryCandidateState state);

    Optional<MemoryCandidate> findById(String id);

    void save(MemoryCandidate candidate);
}
