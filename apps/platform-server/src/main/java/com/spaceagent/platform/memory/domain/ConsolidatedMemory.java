package com.spaceagent.platform.memory.domain;

import java.time.Instant;

/**
 * Scoped durable memory produced from accepted memory candidates.
 */
public record ConsolidatedMemory(
        String id,
        MemoryScopeRef scope,
        MemoryKind kind,
        String key,
        String value,
        Instant createdAt,
        Instant updatedAt) implements ScopedMemory {

    public ConsolidatedMemory {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
