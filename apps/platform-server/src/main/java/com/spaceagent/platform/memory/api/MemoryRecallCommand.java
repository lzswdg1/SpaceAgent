package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;

import java.util.List;
import java.util.Objects;

/**
 * Public query for consolidated memory scoped to a user/project/task.
 */
public record MemoryRecallCommand(
        MemoryScopeRef scope,
        List<MemoryKind> kinds,
        int limit) {

    public MemoryRecallCommand {
        Objects.requireNonNull(scope, "scope");
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
