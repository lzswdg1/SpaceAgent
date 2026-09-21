package com.spaceagent.platform.runtime.domain;

import java.util.List;
import java.util.Objects;

/**
 * Typed, framework-independent state passed from one agent run to the next.
 *
 * <p>This value object is intentionally not coupled to LangGraph, Temporal,
 * LangChain4j, JSON libraries, or provider SDKs.
 */
public record HandoffSnapshot(
        String goal,
        String currentState,
        List<String> completedWork,
        List<String> decisions,
        List<String> failedAttempts,
        List<String> changedFiles,
        HandoffTestStatus testStatus,
        List<String> blockers,
        List<String> nextActions) {

    public HandoffSnapshot {
        completedWork = List.copyOf(completedWork);
        decisions = List.copyOf(decisions);
        failedAttempts = List.copyOf(failedAttempts);
        changedFiles = List.copyOf(changedFiles);
        blockers = List.copyOf(blockers);
        nextActions = List.copyOf(nextActions);
        Objects.requireNonNull(testStatus, "testStatus");
    }
}
