package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScope;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;

import java.time.Instant;

/**
 * Public query result for consolidated scoped memory.
 */
public record ScopedMemoryView(
        String id,
        MemoryScopeRef scope,
        MemoryKind kind,
        String key,
        String value,
        Instant createdAt,
        Instant updatedAt) {

    public MemoryScope scopeType() {
        return scope.scope();
    }
}
