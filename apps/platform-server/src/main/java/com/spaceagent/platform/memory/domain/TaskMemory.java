package com.spaceagent.platform.memory.domain;

import java.time.Instant;

/**
 * Task-scoped memory retained during task-completion consolidation.
 */
public record TaskMemory(
        String id,
        String taskId,
        MemoryKind kind,
        String key,
        String value,
        Instant createdAt,
        Instant updatedAt) implements ScopedMemory {

    public TaskMemory {
        requireNonBlank(id, "id");
        requireNonBlank(taskId, "taskId");
        requireNonBlank(key, "key");
        requireNonBlank(value, "value");
    }

    @Override
    public MemoryScopeRef scope() {
        return MemoryScopeRef.task(taskId);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
