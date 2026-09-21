package com.spaceagent.platform.memory.domain;

import java.time.Instant;

/**
 * Reusable project-scoped knowledge promoted from task memory.
 */
public record ProjectMemory(
        String id,
        String projectId,
        MemoryKind kind,
        String key,
        String value,
        Instant createdAt,
        Instant updatedAt) implements ScopedMemory {

    public ProjectMemory {
        requireNonBlank(id, "id");
        requireNonBlank(projectId, "projectId");
        requireNonBlank(key, "key");
        requireNonBlank(value, "value");
    }

    @Override
    public MemoryScopeRef scope() {
        return MemoryScopeRef.project(projectId);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
