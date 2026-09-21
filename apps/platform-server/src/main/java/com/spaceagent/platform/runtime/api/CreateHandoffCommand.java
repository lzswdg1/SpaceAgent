package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.HandoffSnapshot;

import java.util.Objects;

/**
 * Public command for creating a durable, typed handoff from one run to another.
 */
public record CreateHandoffCommand(String sourceAgentRunId, HandoffSnapshot snapshot) {

    public CreateHandoffCommand {
        requireNonBlank(sourceAgentRunId, "sourceAgentRunId");
        Objects.requireNonNull(snapshot, "snapshot");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
