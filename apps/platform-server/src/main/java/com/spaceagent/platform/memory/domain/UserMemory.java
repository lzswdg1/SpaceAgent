package com.spaceagent.platform.memory.domain;

import java.time.Instant;

/**
 * Reusable user-scoped knowledge promoted from task memory.
 */
public record UserMemory(
        String id,
        String userId,
        MemoryKind kind,
        String key,
        String value,
        Instant createdAt,
        Instant updatedAt) implements ScopedMemory {

    public UserMemory {
        requireNonBlank(id, "id");
        requireNonBlank(userId, "userId");
        requireNonBlank(key, "key");
        requireNonBlank(value, "value");
    }

    @Override
    public MemoryScopeRef scope() {
        return MemoryScopeRef.user(userId);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
