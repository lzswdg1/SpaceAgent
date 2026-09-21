package com.spaceagent.platform.memory.domain;

import java.time.Instant;

/**
 * An unevaluated candidate extracted from conversation/context. Memory candidates
 * are promoted through consolidation into scoped durable memory.
 */
public record MemoryCandidate(
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
