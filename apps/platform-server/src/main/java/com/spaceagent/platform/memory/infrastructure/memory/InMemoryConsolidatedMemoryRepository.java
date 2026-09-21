package com.spaceagent.platform.memory.infrastructure.memory;

import com.spaceagent.platform.memory.domain.ConsolidatedMemory;
import com.spaceagent.platform.memory.domain.ConsolidatedMemoryRepository;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory consolidated memory repository for tests and local development.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryConsolidatedMemoryRepository implements ConsolidatedMemoryRepository {

    private final Map<String, ConsolidatedMemory> memories = new ConcurrentHashMap<>();

    @Override
    public List<ConsolidatedMemory> findByScope(MemoryScopeRef scope) {
        return memories.values().stream()
                .filter(memory -> scope.equals(memory.scope()))
                .toList();
    }

    @Override
    public List<ConsolidatedMemory> findRecentByScope(
            MemoryScopeRef scope, List<com.spaceagent.platform.memory.domain.MemoryKind> kinds, int limit) {
        return memories.values().stream()
                .filter(memory -> scope.equals(memory.scope()))
                .filter(memory -> kinds == null || kinds.isEmpty() || kinds.contains(memory.kind()))
                .sorted(java.util.Comparator.comparing(ConsolidatedMemory::updatedAt).reversed())
                .limit(limit).toList();
    }

    @Override
    public Optional<ConsolidatedMemory> findById(String id) {
        return Optional.ofNullable(memories.get(id));
    }

    @Override
    public synchronized void save(ConsolidatedMemory memory) {
        memories.values().removeIf(existing -> existing.scope().equals(memory.scope())
                && existing.kind() == memory.kind() && existing.key().equals(memory.key()));
        memories.put(memory.id(), memory);
    }

    @Override
    public void deleteById(String id) {
        memories.remove(id);
    }
}
