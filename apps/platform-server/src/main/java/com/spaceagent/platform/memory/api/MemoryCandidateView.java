package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.memory.domain.MemoryCandidateState;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;

import java.time.Instant;

/**
 * Public query result for a memory candidate.
 */
public record MemoryCandidateView(
        String id,
        MemoryScopeRef scope,
        MemoryKind kind,
        String sourceId,
        String sourceType,
        String content,
        double confidence,
        String dedupeKey,
        MemoryCandidateState state,
        Instant createdAt) {
}
