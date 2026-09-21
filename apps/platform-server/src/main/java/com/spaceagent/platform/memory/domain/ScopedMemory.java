package com.spaceagent.platform.memory.domain;

import java.time.Instant;

/**
 * Read contract shared by task, project, and user scoped memory projections.
 */
public interface ScopedMemory {

    MemoryScopeRef scope();

    MemoryKind kind();

    String key();

    String value();

    Instant createdAt();
}
