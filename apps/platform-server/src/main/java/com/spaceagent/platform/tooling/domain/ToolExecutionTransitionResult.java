package com.spaceagent.platform.tooling.domain;

import java.util.Objects;

/**
 * Current state after a fenced completion, UNKNOWN transition, or reconciliation.
 */
public record ToolExecutionTransitionResult(
        ToolExecutionTransitionType type,
        ToolExecutionLedger ledger) {

    public ToolExecutionTransitionResult {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ledger, "ledger");
    }
}
