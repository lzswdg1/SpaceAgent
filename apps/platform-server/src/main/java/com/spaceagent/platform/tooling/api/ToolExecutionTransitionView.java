package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionType;

import java.util.Objects;

/**
 * Result of a fenced ledger transition.
 */
public record ToolExecutionTransitionView(
        ToolExecutionTransitionType type,
        ToolExecutionLedgerView ledger) {

    public ToolExecutionTransitionView {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ledger, "ledger");
    }
}
