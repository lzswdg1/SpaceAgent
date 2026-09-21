package com.spaceagent.platform.memory.domain;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for consolidated scoped memory persistence.
 */
public interface ConsolidatedMemoryRepository {
    List<ConsolidatedMemory> findByScope(MemoryScopeRef scope);

    List<ConsolidatedMemory> findRecentByScope(
            MemoryScopeRef scope, List<MemoryKind> kinds, int limit);

    Optional<ConsolidatedMemory> findById(String id);

    void save(ConsolidatedMemory memory);

    void deleteById(String id);
}
