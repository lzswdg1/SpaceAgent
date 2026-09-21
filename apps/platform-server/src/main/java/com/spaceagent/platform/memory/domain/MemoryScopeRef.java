package com.spaceagent.platform.memory.domain;

import java.util.Objects;

/**
 * Stable value object identifying the scope and target of a memory item.
 *
 * <p>The previous {@code ownerId}/{@code scopeId} pair was ambiguous for USER
 * scoped memory, where both values referred to the same identity. A single
 * {@link MemoryScopeRef} makes the owning scope explicit:
 *
 * <ul>
 *   <li>{@link MemoryScope#USER}: {@code scopeId} is a user identity id.</li>
 *   <li>{@link MemoryScope#PROJECT}: {@code scopeId} is a project id.</li>
 *   <li>{@link MemoryScope#TASK}: {@code scopeId} is a task id.</li>
 * </ul>
 */
public record MemoryScopeRef(MemoryScope scope, String scopeId) {

    public MemoryScopeRef {
        Objects.requireNonNull(scope, "scope");
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
    }

    public static MemoryScopeRef user(String userId) {
        return new MemoryScopeRef(MemoryScope.USER, userId);
    }

    public static MemoryScopeRef project(String projectId) {
        return new MemoryScopeRef(MemoryScope.PROJECT, projectId);
    }

    public static MemoryScopeRef task(String taskId) {
        return new MemoryScopeRef(MemoryScope.TASK, taskId);
    }
}
