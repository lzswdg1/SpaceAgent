package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;

import java.util.Objects;

/**
 * Public command for extracting an unevaluated memory candidate.
 */
public record ProposeMemoryCandidateCommand(
        MemoryScopeRef scope,
        MemoryKind kind,
        String sourceId,
        String sourceType,
        String content,
        double confidence,
        String dedupeKey) {

    public ProposeMemoryCandidateCommand {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        requireNonBlank(sourceId, "sourceId");
        requireNonBlank(sourceType, "sourceType");
        requireNonBlank(content, "content");
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        dedupeKey = dedupeKey == null || dedupeKey.isBlank()
                ? kind.name() + ":" + content
                : dedupeKey;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
